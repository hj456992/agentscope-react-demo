package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.support.AcceptanceTrigger;
import dev.garden.demo.supervisor.support.SemanticLoopGuard;
import dev.garden.demo.supervisor.support.ToolCallLoopGuard;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.AgentTool;

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
    /** 最近一次成功只读查询的紧凑证据，用于跨压缩保留关键数据。 */
    static final String LATEST_EVIDENCE_KEY = "evidence:v1:latest";
    /** 工具调用证据的检查点前缀；每个调用编号一条，先写 RUNNING 再补终态。 */
    static final String TOOL_CALL_KEY = "toolcall:v1:";

    private static final com.fasterxml.jackson.databind.ObjectMapper TOOL_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    RequestDTO request;
    java.time.LocalDate today;
    final LimitsDTO limits;
    final RunControl control;
    String query = "";
    private final String acceptanceHint;
    private int controlledInjected;
    DecisionDTO decision = new DecisionDTO(Intent.UNKNOWN, "", "", "", "", 0);
    private static final reactor.core.scheduler.Scheduler MODEL_WORK =
            reactor.core.scheduler.Schedulers.newBoundedElastic(8, 64, "model-boundary", 30, true);
    /** 协作工具和询问工具由各自的预算、邮箱容量与调用图管理，不计入重复调用检测。 */
    private static final Set<String> LOOP_EXEMPT_TOOLS =
            Set.of(
                    "spawn_agent",
                    "followup_task",
                    "send_message",
                    "interrupt_agent",
                    "list_agents",
                    "wait_agent",
                    "request_user_input",
                    "recall_context",
                    "plan_agents",
                    "remember_preference");

    private final List<String> events = new ArrayList<>();
    private final Set<String> agents = new LinkedHashSet<>();
    private final Map<String, EvidenceDTO> evidence = new LinkedHashMap<>();
    private final Set<String> historicalPermissions = new LinkedHashSet<>();
    private final Set<String> failedChildren = new HashSet<>();
    private final Map<String, Mono<?>> pending = new ConcurrentHashMap<>();
    private final CorrectionTracker corrections;
    private Map<String, AgentTool> frozenTools;
    private Set<String> frozenToolNames = Set.of();
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
        this.acceptanceHint = request.acceptanceHint();
        this.today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        this.corrections = new CorrectionTracker(control, control.policy().correction());
    }

    /**
     * 在模型边界返回一次受控验收工具请求。
     *
     * <p>仅在实例开启受控验收且本任务带有白名单提示时生效；注入同样消耗模型调用预算，
     * 工具参数仍交给真实工具链路校验和执行。返回 {@code null} 表示本次应调用真实模型。
     */
    synchronized Msg controlledReply() {
        if (acceptanceHint == null
                || acceptanceHint.isBlank()
                || controlledInjected >= AcceptanceTrigger.limit(acceptanceHint)) {
            return null;
        }
        AcceptanceTrigger.Call call =
                AcceptanceTrigger.call(
                        acceptanceHint,
                        controlledInjected + 1,
                        today.plusDays(1).toString(),
                        request.question());
        controlledInjected++;
        control.reserveModel(512);
        control.checkpoint(
                "acceptance:v1:trigger",
                json(
                        Map.of(
                                "hint", acceptanceHint,
                                "ordinal", controlledInjected,
                                "tool", call.name())));
        event(
                "受控触发："
                        + acceptanceHint
                        + "，第"
                        + controlledInjected
                        + "次，工具="
                        + call.name());
        ToolUseBlock block =
                ToolUseBlock.builder()
                        .id("acceptance-" + controlledInjected + "-" + java.util.UUID.randomUUID())
                        .name(call.name())
                        .input(call.input())
                        .build();
        return Msg.builder()
                .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                .content(block)
                .metadata(
                        Map.of(
                                "controlledTrigger",
                                acceptanceHint,
                                "acceptanceOrdinal",
                                controlledInjected))
                .build();
    }

    /**
     * 在工具真正执行前写入一条 RUNNING 记录。
     *
     * <p>记录按调用编号持久化到检查点，压缩或进程恢复后仍能证明“请求已发出、结果是否配齐”；
     * 只保存参数指纹，不保存完整参数。
     */
    void toolCallStarted(ToolUseBlock call) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("callId", call.getId());
        record.put("tool", call.getName());
        record.put("argumentFingerprint", fingerprint(new TreeMap<>(call.getInput())));
        record.put("startedAt", System.currentTimeMillis());
        record.put("status", "RUNNING");
        control.checkpoint(TOOL_CALL_KEY + call.getId(), json(record));
    }

    /** 工具返回后补齐终态、结果摘要和失败分类；结果文本只保留截断摘要。 */
    void toolCallFinished(ToolUseBlock call, io.agentscope.core.message.ToolResultBlock result) {
        Map<String, Object> record = toolCallRecord(call.getId());
        String value = toolResultText(result);
        String status =
                result.getState() == io.agentscope.core.message.ToolResultState.SUCCESS
                        ? "COMPLETED"
                        : "FAILED";
        String failureClass = null;
        String providerCode = null;
        Long retryAfterMs = null;
        boolean reused = false;
        String trimmed = value.strip();
        String jsonText = trimmed.startsWith("Error: ") ? trimmed.substring(7).strip() : trimmed;
        if (jsonText.startsWith("{")) {
            try {
                var parsed = TOOL_JSON.readTree(jsonText);
                if (parsed.hasNonNull("status")) {
                    status = parsed.path("status").asText(status);
                }
                var data = parsed.path("data");
                reused = data.path("reused").asBoolean(false);
                if (data.hasNonNull("failureClass")) {
                    failureClass = data.path("failureClass").asText();
                }
                if (data.hasNonNull("providerCode")) {
                    providerCode = data.path("providerCode").asText();
                }
                if (data.path("retryAfterMs").isNumber()) {
                    retryAfterMs = data.path("retryAfterMs").asLong();
                }
            } catch (Exception ignored) {
                /* 非 JSON 错误文本按 FAILED 记录，不在读取层推断更多语义。 */
            }
        } else if (trimmed.startsWith("TOOL_NOT_FOUND")) {
            failureClass = "NOT_FOUND";
        } else if (trimmed.startsWith("INVALID_SCHEMA")) {
            failureClass = "INPUT_INVALID";
        }
        record.put("endedAt", System.currentTimeMillis());
        record.put("status", status);
        record.put(
                "resultBytes", value.getBytes(StandardCharsets.UTF_8).length);
        record.put("resultSummary", value.length() > 500 ? value.substring(0, 500) + "…" : value);
        record.put("reused", reused);
        if (failureClass != null) {
            record.put("failureClass", failureClass);
        }
        if (providerCode != null) {
            record.put("providerCode", providerCode);
        }
        if (retryAfterMs != null) {
            record.put("retryAfterMs", retryAfterMs);
        }
        control.checkpoint(TOOL_CALL_KEY + call.getId(), json(record));
    }

    /**
     * 工具调用被取消或异常中止时写入终态，避免恢复后的证据长期停在 RUNNING。
     *
     * <p>中止记录没有工具结果，因此页面按 {@code ABORTED} 与 {@code paired=false} 展示；
     * 只保存中止原因摘要，不保存堆栈或参数。
     */
    void toolCallAborted(ToolUseBlock call, String reason) {
        Map<String, Object> record = toolCallRecord(call.getId());
        record.put("endedAt", System.currentTimeMillis());
        record.put("status", "ABORTED");
        record.put("aborted", true);
        String summary = reason == null || reason.isBlank() ? "调用被中止" : reason.strip();
        record.put("resultSummary", summary.length() > 500 ? summary.substring(0, 500) + "…" : summary);
        control.checkpoint(TOOL_CALL_KEY + call.getId(), json(record));
    }

    private Map<String, Object> toolCallRecord(String callId) {
        Map<String, Object> record = new LinkedHashMap<>();
        String saved = control.checkpoint(TOOL_CALL_KEY + callId);
        if (saved != null) {
            try {
                var parsed = TOOL_JSON.readTree(saved);
                parsed.fields()
                        .forEachRemaining(entry -> record.put(entry.getKey(), entry.getValue().isNumber()
                                ? entry.getValue().asLong()
                                : entry.getValue().asText()));
            } catch (Exception ignored) {
                /* 旧记录无法解析时按新记录覆盖，不阻断工具结果。 */
            }
        }
        record.putIfAbsent("callId", callId);
        return record;
    }

    private static String toolResultText(io.agentscope.core.message.ToolResultBlock result) {
        StringBuilder text = new StringBuilder();
        for (io.agentscope.core.message.ContentBlock block : result.getOutput()) {
            if (block instanceof io.agentscope.core.message.TextBlock value) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(value.getText());
            }
        }
        return text.toString();
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
        if (item.status() == Status.SUCCESS
                && ("query_weather".equals(item.tool()) || "query_trains".equals(item.tool()))) {
            // 摘要模型可能丢掉查询数据；把最近一次成功结果压成一行写入检查点，
            // 后续每次压缩都能确定性带回，避免模型重复查询同一条件。
            control.checkpoint(
                    LATEST_EVIDENCE_KEY,
                    ContextCompactor.compactEvidence(item.tool(), item.data()));
        }
        control.checkpoint("progress", json(result(Status.RUNNING, "任务执行中")));
        event("工具结果：" + item.tool() + " / " + item.status());
    }

    /**
     * 条件键只包含查询条件，用于判断同一条件是否出现不同结果；完整指纹再区分结果本身。
     *
     * <p>相同条件相同结果仍去重；相同条件不同结果保留为冲突证据。
     */
    static String conditionKey(EvidenceDTO e) {
        Map<String, Object> fields = new TreeMap<>(e.data());
        fields.keySet().retainAll(Set.of("city", "from", "to", "date", "documentId"));
        return fingerprint(fields);
    }

    private static String evidenceKey(EvidenceDTO e) {
        return e.tool()
                + ":"
                + conditionKey(e)
                + ":"
                + fingerprint(new TreeMap<>(e.data()));
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
        if (ex instanceof CorrectionExhausted exhausted) {
            halted = exhausted.status();
        } else if (ex instanceof RunControl.BudgetExceeded) {
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

    /**
     * 消耗一轮中的一步。
     *
     * <p>步数按回合编号分别计数，避免同一会话的后续回合继承上一回合的消耗；
     * 每个模型或工具边界调用一次，超过配置上限时抛出预算异常。
     */
    /**
     * 保存当前多 Agent 计划。
     *
     * <p>计划只写入当前任务检查点，用于恢复和审计；不创建子会话，也不改变任务状态。
     */
    synchronized void savePlan(String value) {
        control.checkpoint(MultiAgentPlanner.PLAN_KEY, value);
    }

    synchronized void beginStep() {
        SupervisorPolicy.ExecutionPolicy execution = control.policy().execution();
        if (!execution.enabled()) {
            return;
        }
        increment("step:v1:" + control.revision(), execution.maxSteps());
    }

    /**
     * 检查同一工具、同一参数是否被重复调用。
     *
     * <p>前 {@code maxRepeatedToolCalls} 次继续执行；下一次返回 {@code WARN}，
     * 让模型收到工具错误并改变策略；再下一次返回 {@code FAIL}，由主循环终止本轮。
     * 开关关闭或工具属于协作、询问、召回类时直接继续。
     */
    synchronized ToolCallLoopGuard.Decision checkToolLoop(
            String tool, Map<String, Object> arguments) {
        SupervisorPolicy.LoopPolicy loop = control.policy().loop();
        if (!loop.enabled() || LOOP_EXEMPT_TOOLS.contains(tool)) {
            return ToolCallLoopGuard.Decision.PROCEED;
        }
        ToolCallLoopGuard.Result result =
                ToolCallLoopGuard.record(
                        control.checkpoint(ToolCallLoopGuard.KEY),
                        tool,
                        fingerprint(arguments == null ? Map.of() : arguments),
                        loop.maxRepeatedToolCalls());
        control.checkpoint(ToolCallLoopGuard.KEY, result.value());
        if (result.decision() == ToolCallLoopGuard.Decision.WARN) {
            event("循环检测：" + tool + " 已重复调用 " + result.count() + " 次");
        }
        return result.decision();
    }

    /**
     * 检查工具目标是否与近期目标语义高度相似。
     *
     * <p>只比较同一工具的规范化参数文本；精确重复由 {@link #checkToolLoop} 先处理。
     * 检测关闭、工具属于协作或元工具时直接继续，不写检查点。
     */
    synchronized SemanticLoopGuard.Result checkSemanticToolLoop(
            String tool, Map<String, Object> arguments) {
        SupervisorPolicy.LoopPolicy loop = control.policy().loop();
        if (!loop.enabled()
                || !loop.semanticEnabled()
                || LOOP_EXEMPT_TOOLS.contains(tool)) {
            return SemanticLoopGuard.Result.proceed(null);
        }
        String text = json(new TreeMap<>(arguments == null ? Map.of() : arguments));
        SemanticLoopGuard.Result result =
                control.recordSemanticGoal(
                        "tool",
                        tool,
                        text,
                        loop.semanticWindow(),
                        loop.semanticThreshold(),
                        loop.semanticMaxWarnings(),
                        false);
        if (result.decision() == SemanticLoopGuard.Decision.WARN) {
            event(
                    "语义循环检测："
                            + tool
                            + " 与近期目标相似度 "
                            + String.format(java.util.Locale.ROOT, "%.2f", result.similarity()));
        }
        return result;
    }

    /**
     * 检查协作目标是否与近期目标语义高度相似。
     *
     * <p>协作目标跨不同 callee 比较，因此“换个 Agent 继续做同一件事”也会命中。
     * 检测只返回提醒或询问结论，不自行改变任务终态。
     */
    synchronized SemanticLoopGuard.Result checkSemanticAgentGoal(
            String callee, String message) {
        SupervisorPolicy.LoopPolicy loop = control.policy().loop();
        if (!loop.enabled() || !loop.semanticEnabled()) {
            return SemanticLoopGuard.Result.proceed(null);
        }
        SemanticLoopGuard.Result result =
                control.recordSemanticGoal(
                        "agent",
                        callee,
                        message,
                        loop.semanticWindow(),
                        loop.semanticThreshold(),
                        loop.semanticMaxWarnings(),
                        true);
        if (result.decision() == SemanticLoopGuard.Decision.WARN) {
            event(
                    "语义循环检测："
                            + callee
                            + " 与近期目标相似度 "
                            + String.format(java.util.Locale.ROOT, "%.2f", result.similarity()));
        }
        return result;
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

    /** 记录一次可修正错误并返回预算决定；开关关闭时只写事件，保持旧行为。 */
    synchronized CorrectionDecision correction(CorrectionType type, String tool) {
        CorrectionDecision decision = corrections.record(type, tool);
        event("工具纠错：" + type.name() + ": " + tool);
        if (decision == CorrectionDecision.DISABLE_TOOL) {
            event("工具已暂停：" + tool);
        }
        return decision;
    }

    /** 工具成功执行后重置该工具的连续 schema 错误计数。 */
    void correctionSuccess(String tool) {
        corrections.success(tool);
    }

    boolean toolDisabled(String tool) {
        return corrections.disabled(tool);
    }

    /** 在每轮开始时固定完整工具表；后续步骤只读该表，不重新发现工具。 */
    synchronized void freezeTools(Map<String, AgentTool> tools) {
        if (frozenTools != null) {
            return;
        }
        frozenTools = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(tools));
        frozenToolNames = Set.copyOf(frozenTools.keySet());
    }

    synchronized Map<String, AgentTool> frozenTools() {
        return frozenTools == null ? Map.of() : frozenTools;
    }

    synchronized Set<String> toolSnapshot() {
        return frozenToolNames;
    }

    synchronized Map<String, Integer> correctionStats() {
        return corrections.stats();
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
                SupervisorPolicy.FailurePolicy failure = control.policy().failure();
                int maxAttempts = failure.maxAttempts();
                java.util.concurrent.atomic.AtomicBoolean emitted =
                        new java.util.concurrent.atomic.AtomicBoolean();
                return Flux.defer(
                                () -> {
                                    boundary();
                                    int inputLimit = ContextBuilder.inputLimit(control);
                                    if (input > inputLimit) {
                                        throw new RunControl.ContextLimit(
                                                "输入包含工具定义后超过预算（当前 "
                                                        + input
                                                        + " 字节，上限 "
                                                        + inputLimit
                                                        + "），请缩小问题或历史。");
                                    }
                                    // 模型真实发送前预留共享额度；SDK 自动重试关闭，每次重试重新计数。
                                    control.reserveModelStep(
                                            step, input + limits.outputTokens(), maxAttempts);
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
                                                                    || signal.totalRetries()
                                                                            >= maxAttempts - 1
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
                                                                                    + 1,
                                                                            failure.backoffCapMs());
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
