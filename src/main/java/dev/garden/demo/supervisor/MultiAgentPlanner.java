package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import dev.garden.demo.supervisor.pojo.dto.AgentSnapshotDTO;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 确定性的多 Agent 计划器。
 *
 * <p>计划器只读取模型显式给出的目标和子任务，不读取模型隐藏状态，也不额外调用模型。
 * 它负责四件事：校验任务名、Agent 类型、权限和依赖关系；按依赖计算可并行批次；
 * 用 {@link MultiAgentGate} 复算 token 与时延收益；把计划写入检查点供恢复读取。
 * 计划本身不创建子会话，模型确认后再按批次调用 {@code spawn_agent}。
 */
final class MultiAgentPlanner {
    /** 当前计划在任务检查点中的键。 */
    static final String PLAN_KEY = "plan:v1:current";

    private static final int MAX_PLAN_TEXT = 2000;

    private final RunContext context;

    MultiAgentPlanner(RunContext context) {
        this.context = context;
    }

    /** 构造注册到工具表的 {@code plan_agents} 工具。 */
    static AgentTool tool(RunContext context) {
        MultiAgentPlanner planner = new MultiAgentPlanner(context);
        return new AgentTool() {
            public String getName() {
                return "plan_agents";
            }

            public String getDescription() {
                return "校验多 Agent 计划并写入任务检查点 plan:v1:current，返回计划、依赖批次、"
                        + "token 与时延估算；不创建或派发子 Agent。";
            }

            public Map<String, Object> getParameters() {
                return parameters(context.control.policy().multiAgent().maxPlanTasks());
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                return Mono.fromSupplier(() -> planner.plan(call.getInput()))
                        .map(result -> ToolResultBlock.text(json(result.toolResult())))
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
                                                            error.getMessage(), "计划生成失败")));
                                });
            }
        };
    }

    /**
     * 生成计划；返回结构里 {@code decision} 只会是 {@code MULTI_AGENT}、
     * {@code SINGLE_AGENT} 或 {@code REJECTED}，不新增任务状态或 HTTP 错误码。
     */
    Plan plan(Map<String, Object> input) {
        SupervisorPolicy.MultiAgentPolicy policy =
                context.control.policy().multiAgent();
        String goal = text(input.get("goal"));
        List<Task> tasks = tasks(input.get("tasks"));
        if (goal.isBlank()) {
            return rejected("INVALID_PLAN", "goal 不能为空");
        }
        if (tasks.isEmpty()) {
            return single(goal, List.of(), "NO_SUBTASKS", "没有子任务，使用单 Agent 完成");
        }
        if (tasks.size() > policy.maxPlanTasks()) {
            return rejected(
                    "PLAN_TOO_LARGE",
                    "子任务数量 " + tasks.size() + " 超过上限 " + policy.maxPlanTasks());
        }
        String invalid = validateTasks(tasks);
        if (invalid != null) {
            return rejected("INVALID_PLAN", invalid);
        }
        List<List<String>> batches;
        try {
            batches = batches(tasks);
        } catch (IllegalArgumentException error) {
            return rejected("DEPENDENCY_CYCLE", error.getMessage());
        }
        String permission = permissionError(tasks);
        if (permission != null) {
            return rejected("PERMISSION_DENIED", permission);
        }
        int depth = depth(context.control.agentPath()) + 1;
        int active = activeChildren(context.control.agentPath());
        if (active + tasks.size() > policy.maxAgents()) {
            return single(
                    goal,
                    steps(tasks, batches),
                    "AGENT_LIMIT_EXCEEDED",
                    "活动子会话 " + active + " 个，再加 " + tasks.size() + " 个超过上限");
        }
        if (depth > policy.maxDepth()) {
            return single(
                    goal,
                    steps(tasks, batches),
                    "DEPTH_LIMIT_EXCEEDED",
                    "当前深度 " + depth + " 超过策略上限 " + policy.maxDepth());
        }
        if (policy.mode() == SupervisorPolicy.MultiAgentPolicy.Mode.OFF) {
            return single(goal, steps(tasks, batches), "MULTI_AGENT_OFF", "多 Agent 模式已关闭");
        }
        int parallelism = batches.stream().mapToInt(List::size).max().orElse(1);
        long singleTokens = tokenEstimate(goal, tasks);
        long multiTokens = singleTokens + tasks.size() * 64L;
        long latencySaving = Math.max(0, tasks.size() - batches.size()) * 400L;
        RoutingInput routing =
                new RoutingInput(
                        false,
                        tasks.size(),
                        parallelism,
                        depth,
                        singleTokens,
                        multiTokens,
                        latencySaving,
                        false);
        RoutingDecision decision = MultiAgentGate.evaluate(routing, policy);
        context.event(
                "多Agent计划："
                        + decision.reason()
                        + "，tasks="
                        + tasks.size()
                        + "，batches="
                        + batches.size()
                        + "，estimatedTokens="
                        + multiTokens
                        + "，latencySavingMs="
                        + latencySaving);
        if (!decision.allowed()) {
            return single(goal, steps(tasks, batches), decision.reason().name(), "量化门槛未通过");
        }
        List<Map<String, Object>> steps = steps(tasks, batches);
        Plan result =
                new Plan(
                        "MULTI_AGENT",
                        decision.reason().name(),
                        steps,
                        batches,
                        parallelism,
                        multiTokens,
                        latencySaving,
                        "计划已写入检查点 plan:v1:current；按 batches 顺序调用 spawn_agent，"
                                + "同一批次的子任务可以并行。", true);
        context.savePlan(json(result.toMap()));
        return result;
    }

    private Plan rejected(String reason, String detail) {
        return new Plan(
                "REJECTED",
                reason,
                List.of(),
                List.of(),
                0,
                0,
                0,
                "计划不合法：" + detail,
                false);
    }

    private Plan single(
            String goal, List<Map<String, Object>> steps, String reason, String detail) {
        Plan result =
                new Plan(
                        "SINGLE_AGENT",
                        reason,
                        steps,
                        List.of(),
                        0,
                        tokenEstimate(goal, List.of()),
                        0,
                        "计划已写入检查点 plan:v1:current；不建议创建子 Agent：" + detail,
                        true);
        context.savePlan(json(result.toMap()));
        return result;
    }

    private List<Task> tasks(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Task> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fields.put(Objects.toString(entry.getKey(), ""), entry.getValue());
            }
            result.add(
                    new Task(
                            text(fields.get("task_name")),
                            text(fields.get("agent_type")),
                            text(fields.get("objective")),
                            strings(fields.get("depends_on"))));
        }
        return result;
    }

    private static String validateTasks(List<Task> tasks) {
        Set<String> names = new LinkedHashSet<>();
        for (Task task : tasks) {
            if (!task.name().matches("[a-z0-9_]+") || task.name().equals("root")) {
                return "任务名只能使用小写字母、数字和下划线，且不能是 root：" + task.name();
            }
            if (!names.add(task.name())) {
                return "任务名重复：" + task.name();
            }
            if (!Set.of("weather_agent", "train_agent", "itinerary_agent")
                    .contains(task.agentType())) {
                return "未知 Agent 类型：" + task.agentType();
            }
            if (task.objective().isBlank()) {
                return "任务 " + task.name() + " 缺少 objective";
            }
            if (task.objective().length() > MAX_PLAN_TEXT) {
                return "任务 " + task.name() + " 的 objective 过长";
            }
        }
        for (Task task : tasks) {
            for (String dependency : task.dependsOn()) {
                if (!names.contains(dependency)) {
                    return "任务 " + task.name() + " 依赖不存在的任务：" + dependency;
                }
                if (dependency.equals(task.name())) {
                    return "任务不能依赖自身：" + task.name();
                }
            }
        }
        return null;
    }

    /** 计算拓扑批次；同时出现“还有任务但没有任何可执行任务”说明存在依赖环。 */
    private static List<List<String>> batches(List<Task> tasks) {
        Map<String, Task> byName = new LinkedHashMap<>();
        for (Task task : tasks) {
            byName.put(task.name(), task);
        }
        Set<String> done = new LinkedHashSet<>();
        List<List<String>> result = new ArrayList<>();
        while (done.size() < tasks.size()) {
            List<String> batch = new ArrayList<>();
            for (Task task : tasks) {
                if (done.contains(task.name())) {
                    continue;
                }
                if (done.containsAll(task.dependsOn())) {
                    batch.add(task.name());
                }
            }
            if (batch.isEmpty()) {
                throw new IllegalArgumentException("任务依赖存在环");
            }
            result.add(List.copyOf(batch));
            done.addAll(batch);
        }
        return result;
    }

    private List<Map<String, Object>> steps(List<Task> tasks, List<List<String>> batches) {
        Map<String, Integer> batchIndex = new LinkedHashMap<>();
        for (int index = 0; index < batches.size(); index++) {
            for (String name : batches.get(index)) {
                batchIndex.put(name, index);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task task : tasks) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("task_name", task.name());
            step.put("agent_type", task.agentType());
            step.put("objective", task.objective());
            step.put("depends_on", task.dependsOn());
            step.put("batch", batchIndex.getOrDefault(task.name(), 0));
            result.add(step);
        }
        return result;
    }

    private String permissionError(List<Task> tasks) {
        for (Task task : tasks) {
            if (!allowed(task.agentType())) {
                return "当前身份不能使用 " + task.agentType();
            }
        }
        return null;
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

    private static long tokenEstimate(String goal, List<Task> tasks) {
        long chars = goal.length();
        for (Task task : tasks) {
            chars += task.objective().length();
        }
        return Math.max(256L, chars * 2L);
    }

    private static int depth(String path) {
        int depth = 0;
        for (int index = 1; index < path.length(); index++) {
            if (path.charAt(index) == '/' && index + 1 < path.length()) {
                depth++;
            }
        }
        return depth;
    }

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

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = text(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static String text(Object value) {
        return Objects.toString(value, "").strip();
    }

    private static Map<String, Object> parameters(int maxTasks) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put(
                "type",
                "object");
        task.put(
                "properties",
                Map.of(
                        "task_name",
                        Map.of("type", "string", "minLength", 1),
                        "agent_type",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of("weather_agent", "train_agent", "itinerary_agent")),
                        "objective",
                        Map.of("type", "string", "minLength", 1),
                        "depends_on",
                        Map.of(
                                "type",
                                "array",
                                "items",
                                Map.of("type", "string"))));
        task.put(
                "required",
                List.of("task_name", "agent_type", "objective"));
        task.put("additionalProperties", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("goal", Map.of("type", "string", "minLength", 1));
        properties.put(
                "tasks",
                Map.of(
                        "type",
                        "array",
                        "items",
                        task,
                        "minItems",
                        1,
                        "maxItems",
                        Math.max(1, Math.min(maxTasks, 32))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", List.of("goal", "tasks"));
        result.put("additionalProperties", false);
        return result;
    }

    /** 规划结果；只包含计划数据，不持有运行期对象。 */
    record Plan(
            String decision,
            String reason,
            List<Map<String, Object>> steps,
            List<List<String>> batches,
            int parallelism,
            long estimatedTokens,
            long latencySavingMs,
            String advice,
            boolean persisted) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("decision", decision);
            result.put("reason", reason);
            result.put("steps", steps);
            result.put("batches", batches);
            result.put("parallelism", parallelism);
            result.put("estimatedTokens", estimatedTokens);
            result.put("latencySavingMs", latencySavingMs);
            result.put("advice", advice);
            result.put("persisted", persisted);
            result.put("persistedKey", persisted ? PLAN_KEY : "");
            return result;
        }

        /**
         * 工具结果视图：只返回派发所需的计划、批次与预算字段，不重复任务书全文。
         *
         * <p>完整计划已写入检查点并在 RunDetail 可查；工具结果保持精简，避免计划正文
         * 与工具定义一起挤占模型输入预算。
         */
        Map<String, Object> toolResult() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("decision", decision);
            result.put("reason", reason);
            result.put("persisted", persisted);
            result.put("persistedKey", persisted ? PLAN_KEY : "");
            result.put("batches", batches);
            result.put("parallelism", parallelism);
            result.put("estimatedTokens", estimatedTokens);
            result.put("latencySavingMs", latencySavingMs);
            result.put("advice", advice);
            List<Map<String, Object>> summary = new ArrayList<>();
            for (Map<String, Object> step : steps) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("task_name", step.get("task_name"));
                item.put("agent_type", step.get("agent_type"));
                item.put("batch", step.get("batch"));
                item.put("depends_on", step.get("depends_on"));
                summary.add(item);
            }
            result.put("tasks", summary);
            return result;
        }
    }

    /** 计划中的一个子任务。 */
    record Task(String name, String agentType, String objective, List<String> dependsOn) {}
}
