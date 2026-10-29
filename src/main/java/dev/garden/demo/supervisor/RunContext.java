package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;

import reactor.core.publisher.*;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 单个会话当前轮次的结果与日志；独立取消和共享树预算由 RunControl 管理。 */
final class RunContext {
    RequestDTO request;
    java.time.LocalDate today;
    final LimitsDTO limits;
    final RunControl control;
    String query = "";
    DecisionDTO decision = new DecisionDTO(Intent.UNKNOWN, "", "", "", "", 0);
    private static final reactor.core.scheduler.Scheduler MODEL_WORK =
            reactor.core.scheduler.Schedulers.newBoundedElastic(8, 64, "model-boundary", 30, true);
    private final List<String> events = new ArrayList<>();
    private final Set<String> agents = new LinkedHashSet<>();
    private final Map<String, EvidenceDTO> evidence = new LinkedHashMap<>();
    private final Set<String> historicalPermissions = new LinkedHashSet<>();
    private final Set<String> failedChildren = new HashSet<>();
    private final Map<String, Mono<?>> pending = new ConcurrentHashMap<>();
    private volatile Status halted;

    // 只有主循环读取新输入；子 Agent 不消费或清空父任务的输入队列。
    void boundary() {
        check();
    }

    synchronized void startNewTurn() {
        // 历史工具消息仍在对话中；本轮状态和证据附件从新的执行重新累计。
        failedChildren.clear();
        evidence.clear();
        halted = null;
    }

    void acceptInput(String text) {
        List<TurnDTO> history = new ArrayList<>(request.history());
        history.add(new TurnDTO(request.question(), "本轮此前的用户要求"));
        request = new RequestDTO(request.sessionId(), text, history, request.requestId());
        query = text;
        // 历史证据保留来源和条件，由模型判断新指令如何使用；运行器不解析自然语言。
        failedChildren.clear();
        decision = new DecisionDTO(Intent.UNKNOWN, "", "", "", "", 0);
        event("新指令已加入当前会话");
    }

    RunContext(RequestDTO request, LimitsDTO limits, RunControl control) {
        this.request = request;
        this.limits = limits;
        this.control = control;
        this.query = request.question();
        this.today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    }

    synchronized void event(String text) {
        if (events.size() < 200) {
            events.add(text.length() > 2000 ? text.substring(0, 2000) + "…" : text);
        }
        try {
            control.event(text.length() > 2000 ? text.substring(0, 2000) : text);
        } catch (RunControl.Cancelled | RunControl.DeadlineExceeded ignored) {
            /* 终态日志不能覆盖原始失败。 */
        }
    }

    synchronized void agent(String name) {
        if (agents.add(name)) {
            event("进入" + name + "助手");
        }
    }

    synchronized Set<String> requiredPermissions() {
        return Set.copyOf(historicalPermissions);
    }

    synchronized void restorePermissions(Collection<String> permissions) {
        for (String permission : permissions) {
            if (!control.identity().allows(permission)) {
                throw new RunControl.Cancelled("历史内容权限已变化，不能继续回放");
            }
            historicalPermissions.add(permission);
        }
    }

    synchronized void add(EvidenceDTO item) {
        restorePermissions(List.of(item.tool()));
        if (ContextBuilder.estimate(item) > 16000) {
            throw new IllegalArgumentException("工具结果过大，需缩小查询范围");
        }
        if (item.status() == Status.SUCCESS || item.status() == Status.NO_DATA) {
            evidence.entrySet()
                    .removeIf(
                            e ->
                                    e.getValue().tool().equals(item.tool())
                                            && (e.getValue().status() == Status.INVALID_INPUT));
        }
        evidence.put(evidenceKey(item), item);
        control.checkpoint("progress", json(result(Status.RUNNING, "任务执行中")));
        event("工具结果：" + item.tool() + " / " + item.status());
    }

    private static String evidenceKey(EvidenceDTO e) {
        Map<String, Object> fields = new TreeMap<>(e.data());
        fields.keySet().retainAll(Set.of("city", "from", "to", "date", "documentId"));
        return e.tool() + ":" + fingerprint(fields);
    }

    synchronized List<EvidenceDTO> evidence() {
        return evidence.values().stream().filter(e -> control.identity().allows(e.tool())).toList();
    }

    synchronized List<String> failedCalls() {
        return List.copyOf(failedChildren);
    }

    synchronized void childFailed(String name) {
        failedChildren.add(name);
    }

    synchronized void childSucceeded(String name) {
        failedChildren.remove(name);
    }

    void check() {
        try {
            control.checkActive();
            restorePermissions(requiredPermissions());
        } catch (RuntimeException ex) {
            rememberFailure(ex);
            throw ex;
        }
    }

    void rememberFailure(Throwable ex) {
        if (ex instanceof RunControl.BudgetExceeded) {
            halted = Status.BUDGET_EXHAUSTED;
        } else if (ex instanceof RunControl.Cancelled) {
            halted = Status.CANCELLED;
        } else if (ex instanceof RunControl.ContextLimit) {
            halted = Status.CONTEXT_LIMIT;
        } else if (ex instanceof RunControl.DeadlineExceeded
                || ex instanceof java.util.concurrent.TimeoutException) {
            halted = Status.TIMEOUT;
        }
    }

    Status failureStatus() {
        return halted == null ? Status.FAILED : halted;
    }

    synchronized int increment(String key, int maximum) {
        check();
        String old = control.checkpoint(key);
        int count = old == null ? 0 : Integer.parseInt(old);
        if (count >= maximum) {
            throw new RunControl.BudgetExceeded("该逻辑步骤已达到尝试上限");
        }
        control.checkpoint(key, Integer.toString(count + 1));
        return count + 1;
    }

    void correction(String role, String reason) {
        event("工具纠错：" + reason);
    }

    @SuppressWarnings("unchecked")
    <T> Mono<T> share(String key, Supplier<Mono<T>> supplier) {
        // 单次执行合并并发；持久化成功回放由具体调用方核对权限和参数后完成。
        return (Mono<T>) pending.computeIfAbsent(key, k -> Mono.defer(supplier).share());
    }

    Model budgeted(Model delegate) {
        return budgeted(delegate, false);
    }

    /** 摘要失败的分片不向调用者泄漏；内部超窗由压缩器处理，不能提前污染终态。 */
    Model budgetedCompaction(Model delegate) {
        return budgeted(delegate, true);
    }

    private Model budgeted(Model delegate, boolean compaction) {
        return new Model() {
            public String getModelName() {
                return delegate.getModelName();
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                int input =
                        ContextBuilder.estimate(
                                Map.of(
                                        "messages",
                                        messages,
                                        "tools",
                                        tools == null ? List.of() : tools));
                String step =
                        "model:"
                                + fingerprint(
                                        Map.of(
                                                "model",
                                                delegate.getModelName(),
                                                "messages",
                                                normalized(messages)));
                java.util.concurrent.atomic.AtomicBoolean emitted =
                        new java.util.concurrent.atomic.AtomicBoolean();
                return Flux.defer(
                                () -> {
                                    boundary();
                                    if (input > ContextBuilder.INPUT_LIMIT) {
                                        throw new RunControl.ContextLimit(
                                                "输入包含工具定义后超过预算，请缩小问题或历史。");
                                    }
                                    // 模型真实发送前预留共享额度；SDK 自动重试关闭，每次重试重新计数。
                                    control.reserveModelStep(
                                            step, input + limits.outputTokens(), 3);
                                    Duration timeout =
                                            control.remaining().compareTo(Duration.ofSeconds(20))
                                                            < 0
                                                    ? control.remaining()
                                                    : Duration.ofSeconds(20);
                                    Flux<ChatResponse> response =
                                            delegate.stream(messages, tools, options)
                                                    .timeout(timeout);
                                    if (compaction) {
                                        return response.collectList()
                                                .flatMapMany(Flux::fromIterable);
                                    }
                                    return response.doOnNext(x -> emitted.set(true));
                                })
                        .retryWhen(
                                Retry.from(
                                        signals ->
                                                signals.concatMap(
                                                        signal -> {
                                                            Throwable error = signal.failure();
                                                            if (emitted.get()
                                                                    || signal.totalRetries() >= 2
                                                                    || !ToolExecutor.retryable(
                                                                            error)) {
                                                                return Mono.error(error);
                                                            }
                                                            Duration delay =
                                                                    ToolExecutor.retryDelay(
                                                                            error,
                                                                            (int)
                                                                                            signal
                                                                                                    .totalRetries()
                                                                                    + 1);
                                                            if (control.remaining().compareTo(delay)
                                                                    <= 0) {
                                                                return Mono.error(
                                                                        new RunControl
                                                                                .DeadlineExceeded());
                                                            }
                                                            return Mono.delay(delay);
                                                        })))
                        .doOnError(
                                error -> {
                                    if (!ContextCompactor.isContextOverflow(error)) {
                                        RunContext.this.rememberFailure(error);
                                    }
                                })
                        .subscribeOn(MODEL_WORK)
                        .publishOn(MODEL_WORK);
            }
        };
    }

    synchronized Status outcome() {
        if (halted != null) {
            return halted;
        }
        long successes = evidence().stream().filter(e -> e.status() == Status.SUCCESS).count();
        boolean failed =
                evidence.values().stream().anyMatch(e -> !control.identity().allows(e.tool()))
                        || !failedChildren.isEmpty()
                        || evidence.values().stream()
                                .anyMatch(
                                        e ->
                                                e.status() == Status.FAILED
                                                        || e.status() == Status.INVALID_INPUT);
        if (evidence.isEmpty()) {
            return Status.FAILED;
        }
        if (failed) {
            return successes > 0 ? Status.PARTIAL : Status.FAILED;
        }
        if (successes == 0) {
            return Status.NO_DATA;
        }
        if (successes < evidence.size()) {
            return Status.PARTIAL;
        }
        return Status.SUCCESS;
    }

    synchronized ResultDTO result(Status status, String answer) {
        return new ResultDTO(
                status,
                request.question(),
                query,
                decision.intent(),
                answer,
                List.copyOf(agents),
                evidence(),
                List.copyOf(events));
    }

    /** 逻辑步骤不能包含 SDK 随机消息号、工具调用号或创建时间。 */
    private static List<Map<String, Object>> normalized(List<Msg> messages) {
        return messages.stream()
                .map(
                        message ->
                                Map.<String, Object>of(
                                        "role",
                                        message.getRole().name(),
                                        "content",
                                        message.getContent().stream()
                                                .map(RunContext::stableBlock)
                                                .toList()))
                .toList();
    }

    private static Object stableBlock(io.agentscope.core.message.ContentBlock block) {
        if (block instanceof io.agentscope.core.message.TextBlock text) {
            return Map.of("type", "text", "text", stableText(text.getText()));
        }
        if (block instanceof io.agentscope.core.message.ToolUseBlock tool) {
            return Map.of("type", "tool_use", "name", tool.getName(), "input", tool.getInput());
        }
        if (block instanceof io.agentscope.core.message.ToolResultBlock result) {
            return Map.of(
                    "type",
                    "tool_result",
                    "name",
                    Objects.toString(result.getName(), ""),
                    "output",
                    result.getOutput().stream().map(RunContext::stableBlock).toList());
        }
        return json(block);
    }

    private static Object stableText(String text) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            if (node != null && (node.isObject() || node.isArray())) {
                return canonicalNode(node);
            }
        } catch (java.io.IOException ignored) {
            /* 普通文本按原文保留。 */
        }
        return text;
    }

    private static String canonicalJson(Object value) {
        try {
            return canonicalNode(
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json(value)))
                    .toString();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("步骤参数无法规范化", error);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode canonicalNode(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var sorted = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            java.util.TreeMap<String, com.fasterxml.jackson.databind.JsonNode> fields =
                    new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonicalNode(value)));
            return sorted;
        }
        if (node.isArray()) {
            var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(canonicalNode(item)));
            return result;
        }
        return node;
    }

    static String fingerprint(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonicalJson(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
