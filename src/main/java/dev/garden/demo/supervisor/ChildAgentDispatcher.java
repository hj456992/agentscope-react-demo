package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.support.SemanticLoopGuard;

import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;

import reactor.core.publisher.Mono;

import java.util.*;

/** Codex V2 协作工具。受理、运行、消息和取消归独立会话运行器管理。 */
final class ChildAgentDispatcher {
    private final RunContext context;
    private final LoopConversation conversation;

    ChildAgentDispatcher(RunContext context, LoopConversation conversation) {
        this.context = context;
        this.conversation = conversation;
    }

    Map<String, AgentTool> tools() {
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        add(
                tools,
                "spawn_agent",
                "创建独立子会话并返回标识；随后用消息或等待工具协作。",
                Map.of(
                        "task_name",
                        string(),
                        "message",
                        string(),
                        "agent_type",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of("weather_agent", "train_agent", "itinerary_agent")),
                        "fork_turns",
                        Map.of("type", "string", "enum", List.of("all", "none"))),
                List.of("task_name", "message"));
        add(
                tools,
                "followup_task",
                "给子会话追加任务；闲置时启动，运行时排队。",
                Map.of("target", string(), "message", string()),
                List.of("target", "message"));
        add(
                tools,
                "send_message",
                "给已有会话发普通消息，不启动闲置会话。",
                Map.of("target", string(), "message", string()),
                List.of("target", "message"));
        add(
                tools,
                "interrupt_agent",
                "中断目标当前轮次，保留会话供继续使用，返回中断前状态。",
                Map.of("target", string()),
                List.of("target"));
        add(tools, "list_agents", "查看任务树中已知会话与状态。", Map.of("path_prefix", string()), List.of());
        add(
                tools,
                "wait_agent",
                "等待本会话消息或新用户输入；超时不取消任何子会话。",
                Map.of(
                        "timeout_ms",
                        Map.of("type", "integer", "minimum", 10000, "maximum", 3600000)),
                List.of());
        return tools;
    }

    private void add(
            Map<String, AgentTool> registry,
            String name,
            String description,
            Map<String, Object> fields,
            List<String> required) {
        registry.put(
                name,
                new AgentTool() {
                    public String getName() {
                        return name;
                    }

                    public String getDescription() {
                        return description;
                    }

                    public Map<String, Object> getParameters() {
                        return Map.of(
                                "type",
                                "object",
                                "properties",
                                fields,
                                "required",
                                required,
                                "additionalProperties",
                                false);
                    }

                    public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                        return Mono.defer(() -> execute(name, call.getInput(), null))
                                .map(ChildAgentDispatcher.this::result)
                                .onErrorResume(
                                        error -> {
                                            if (error instanceof CorrectionExhausted
                                                    || error instanceof RunControl.Cancelled
                                                    || error instanceof RunControl.DeadlineExceeded
                                                    || error instanceof RunControl.BudgetExceeded
                                                    || error instanceof RunControl.ContextLimit) {
                                                return Mono.error(error);
                                            }
                                            return Mono.just(
                                                    ToolResultBlock.error(
                                                            Objects.toString(
                                                                    error.getMessage(),
                                                                    "协作请求未完成")));
                                        });
                    }
                });
    }

    Mono<ToolResultBlock> invoke(ToolUseBlock call) {
        return Mono.defer(() -> execute(call.getName(), call.getInput(), call.getId()))
                .map(this::result)
                .onErrorResume(
                        error -> {
                            if (error instanceof CorrectionExhausted
                                    || error instanceof RunControl.Cancelled
                                    || error instanceof RunControl.DeadlineExceeded
                                    || error instanceof RunControl.BudgetExceeded
                                    || error instanceof RunControl.ContextLimit) {
                                return Mono.error(error);
                            }
                            return Mono.just(
                                    ToolResultBlock.error(
                                            Objects.toString(error.getMessage(), "协作请求未完成")));
                        });
    }

    private ToolResultBlock result(Object value) {
        if (value instanceof AgentSnapshotDTO snapshot) {
            context.restorePermissions(snapshot.requiredPermissions());
        } else if (value instanceof List<?> snapshots) {
            for (Object item : snapshots) {
                if (item instanceof AgentSnapshotDTO snapshot) {
                    context.restorePermissions(snapshot.requiredPermissions());
                }
            }
        }
        return ToolResultBlock.text(json(value));
    }

    private Mono<?> execute(String name, Map<String, Object> args, String operationId) {
        context.check();
        String target = Objects.toString(args.get("target"), "");
        String message = Objects.toString(args.get("message"), "");
        return switch (name) {
            case "spawn_agent" -> {
                String role = Objects.toString(args.get("agent_type"), "itinerary_agent");
                if (!allowed(role)) {
                    yield Mono.error(new IllegalArgumentException("PERMISSION_DENIED"));
                }
                SupervisorPolicy.MultiAgentPolicy multiAgent =
                        context.control.policy().multiAgent();
                if (multiAgent.mode() != SupervisorPolicy.MultiAgentPolicy.Mode.OFF) {
                    RoutingDecision decision = routingDecision(message, multiAgent);
                    context.event(decision.event());
                    if (multiAgent.mode() == SupervisorPolicy.MultiAgentPolicy.Mode.ENFORCE
                            && (decision.reason()
                                            == RoutingDecision.Reason.DEPTH_LIMIT_EXCEEDED
                                    || decision.reason()
                                            == RoutingDecision.Reason.AGENT_LIMIT_EXCEEDED)) {
                        yield Mono.error(
                                new MultiAgentLimit(
                                        "MULTI_AGENT_LIMIT：子会话深度或活动数量达到上限，未创建新会话。"));
                    }
                }
                String taskName = Objects.toString(args.get("task_name"), "");
                String fork = Objects.toString(args.get("fork_turns"), "all");
                Map<String, String> inherited =
                        fork.equals("all")
                                ? Map.of(
                                        "agent:inherited-history",
                                        json(forkHistory()),
                                        "agent:inherited-permissions",
                                        json(context.requiredPermissions()))
                                : Map.of();
                String callee = AgentLifecycle.childPath(context.control.agentPath(), taskName);
                guardAgentEdge(callee);
                guardSemanticGoal(callee, message);
                yield context.control.spawnAgent(
                        operationId, taskName, message, role, fork, inherited);
            }
            case "followup_task" -> {
                String callee = AgentLifecycle.resolve(context.control.agentPath(), target);
                guardAgentEdge(callee);
                guardSemanticGoal(callee, message);
                yield context.control
                        .sendMessage(operationId, target, message, true)
                        .thenReturn(Map.of("accepted", true));
            }
            case "send_message" ->
                    context.control
                            .sendMessage(operationId, target, message, false)
                            .thenReturn(Map.of("accepted", true));
            case "interrupt_agent" -> context.control.interruptAgent(operationId, target);
            case "list_agents" ->
                    Mono.fromSupplier(
                            () -> context.control.listAgents((String) args.get("path_prefix")));
            case "wait_agent" ->
                    context.control.waitAgent(
                            ((Number) args.getOrDefault("timeout_ms", 30000)).longValue());
            default -> Mono.error(new IllegalArgumentException("TOOL_NOT_FOUND"));
        };
    }

    /**
     * 拒绝会形成回边的协作调用。
     *
     * <p>只对 {@code spawn_agent} 和 {@code followup_task} 生效，因为只有这两种调用会开启
     * 目标的新回合；{@code send_message} 只写入邮箱，由邮箱容量和模型预算兜底。
     * 检测开关关闭时不读取、也不写入调用图。
     */
    /**
     * 拒绝与近期协作目标语义高度相似的派发。
     *
     * <p>先提醒一次，让模型改变任务描述；达到阈值次数后转为 {@code NEEDS_INPUT}，
     * 由用户确认是否继续。检测本身不直接判失败，最终仍受预算、总步数和期限约束。
     */
    private void guardSemanticGoal(String callee, String message) {
        SemanticLoopGuard.Result result = context.checkSemanticAgentGoal(callee, message);
        if (result.decision() == SemanticLoopGuard.Decision.WARN) {
            String text =
                    "LOOP_DETECTED：协作目标与近期目标高度相似（相似度 "
                            + String.format(java.util.Locale.ROOT, "%.2f", result.similarity())
                            + "），请改变任务描述或直接使用已有结果。";
            context.event(text);
            throw new IllegalArgumentException(text);
        }
        if (result.decision() == SemanticLoopGuard.Decision.ASK) {
            throw new CorrectionExhausted(
                    Status.NEEDS_INPUT,
                    "检测到重复的相似协作目标，已暂停派发。请确认是否继续、调整任务或结束当前需求。");
        }
    }

    private void guardAgentEdge(String callee) {
        if (!context.control.policy().loop().agentCycleDetectionEnabled()) {
            return;
        }
        String caller = context.control.agentPath();
        if (context.control.recordAgentEdge(caller, callee)) {
            String message =
                    "LOOP_DETECTED：协作调用 "
                            + caller
                            + " -> "
                            + callee
                            + " 会形成环或超过调用图容量，已拒绝。";
            context.event(message);
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 用当前会话树估算并行度、token 与时延收益。
     *
     * <p>演示级估算：单 Agent token 按消息长度两倍计且不低于 256；多 Agent token 随并行度线性增长；
     * 每多一个并行子任务预计节省 400 毫秒。数值只进入决策事件和硬上限判断，不参与计费。
     */
    private RoutingDecision routingDecision(
            String message, SupervisorPolicy.MultiAgentPolicy policy) {
        String path = context.control.agentPath();
        int depth = depth(path);
        int children = activeChildren(path);
        int parallelism = children + 1;
        long singleAgentTokens =
                Math.max(256L, (long) message.codePointCount(0, message.length()) * 2L);
        long multiAgentTokens = singleAgentTokens * parallelism;
        long latencySavingMs = Math.max(0, parallelism - 1) * 400L;
        RoutingInput input =
                new RoutingInput(
                        false,
                        parallelism,
                        parallelism,
                        depth,
                        singleAgentTokens,
                        multiAgentTokens,
                        latencySavingMs,
                        false);
        return MultiAgentGate.evaluate(input, policy);
    }

    /** 路径深度：{@code /root} 为 0，每多一级子会话加 1。 */
    private static int depth(String path) {
        int depth = 0;
        for (int index = 1; index < path.length(); index++) {
            if (path.charAt(index) == '/' && index + 1 < path.length()) {
                depth++;
            }
        }
        return depth;
    }

    /** 当前会话下仍活动的直接子会话数量；已完成、失败或中断的子会话不占用名额。 */
    private int activeChildren(String path) {
        int count = 0;
        for (AgentSnapshotDTO agent : context.control.listAgents(path)) {
            String child = Objects.toString(agent.agentName(), "");
            if (!child.startsWith(path + "/")) {
                continue;
            }
            String tail = child.substring(path.length() + 1);
            if (tail.contains("/")) {
                continue;
            }
            if (Set.of("completed", "errored", "interrupted", "shutdown")
                    .contains(agent.agentStatus())) {
                continue;
            }
            count++;
        }
        return count;
    }

    private List<Msg> forkHistory() {
        // 继承完整前缀，并为父轮次尚未返回的工具补中断结果，避免子会话重放。
        return ContextCompactor.normalize(new ArrayList<>(conversation.messages));
    }

    private boolean allowed(String role) {
        return switch (role) {
            case "weather_agent" -> context.control.identity().allows("query_weather");
            case "train_agent" -> context.control.identity().allows("query_trains");
            case "itinerary_agent" ->
                    context.control.identity().allows("query_weather")
                            || context.control.identity().allows("query_trains")
                            || context.control.identity().allows("search_knowledge");
            default -> false;
        };
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string", "minLength", 1);
    }

    static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}
