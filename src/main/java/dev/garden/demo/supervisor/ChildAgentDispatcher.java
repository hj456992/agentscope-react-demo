package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;

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
                                            if (error instanceof RunControl.Cancelled
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
                            if (error instanceof RunControl.Cancelled
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
                String fork = Objects.toString(args.get("fork_turns"), "all");
                Map<String, String> inherited =
                        fork.equals("all")
                                ? Map.of(
                                        "agent:inherited-history",
                                        json(forkHistory()),
                                        "agent:inherited-permissions",
                                        json(context.requiredPermissions()))
                                : Map.of();
                yield context.control.spawnAgent(
                        operationId,
                        (String) args.get("task_name"),
                        message,
                        role,
                        fork,
                        inherited);
            }
            case "followup_task" ->
                    context.control
                            .sendMessage(operationId, target, message, true)
                            .thenReturn(Map.of("accepted", true));
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
