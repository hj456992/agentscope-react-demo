package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.tool.subagent.*;
import io.agentscope.core.util.JsonUtils;

import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/** 子 Agent 是模型可选的工具。每次调用独立条件，身份与预算只由服务端传入。 */
final class ChildAgentDispatcher {
    private final RunContext context;
    private final TravelAgentFactory factory;

    ChildAgentDispatcher(RunContext context, TravelAgentFactory factory) {
        this.context = context;
        this.factory = factory;
    }

    AgentTool tool(String name) {
        String label =
                switch (name) {
                    case "weather_agent" -> "天气查询";
                    case "train_agent" -> "火车票查询";
                    case "itinerary_agent" -> "行程规划";
                    default -> throw new IllegalArgumentException("未注册的子 Agent");
                };
        return new AgentTool() {
            public String getName() {
                return name;
            }

            public String getDescription() {
                return label + "助手；只传用户明确要求或历史中可核实的条件，日期为 yyyy-MM-dd。";
            }

            public Map<String, Object> getParameters() {
                List<String> fields =
                        name.equals("weather_agent")
                                ? List.of("city", "date")
                                : name.equals("train_agent")
                                        ? List.of("from", "to", "date")
                                        : List.of("from", "to", "date", "days");
                Map<String, Object> properties = new LinkedHashMap<>();
                for (String field : fields)
                    properties.put(
                            field,
                            field.equals("days")
                                    ? Map.of("type", "integer", "minimum", 1, "maximum", 30)
                                    : Map.of("type", "string", "minLength", 1, "maxLength", 100));
                return Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        fields,
                        "additionalProperties",
                        false);
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam input) {
                return Mono.defer(
                                () -> {
                                    context.check();
                                    if (!allowed(name))
                                        return Mono.just(
                                                ToolResultBlock.error("PERMISSION_DENIED"));
                                    if (ToolValidator.validateInput(
                                                    json(input.getInput()), getParameters())
                                            != null) {
                                        context.correction(name, "INVALID_SCHEMA");
                                        return Mono.just(
                                                ToolResultBlock.error(
                                                        "INVALID_SCHEMA：请按字段定义修正参数。"));
                                    }
                                    Decision conditions = conditions(name, input.getInput());
                                    String problem =
                                            BusinessConditions.validate(
                                                    context.request, conditions, context.today);
                                    if (!problem.isEmpty()) {
                                        context.correction(name, "INVALID_INPUT");
                                        return Mono.just(
                                                ToolResultBlock.error("INVALID_INPUT：" + problem));
                                    }
                                    // 归类只用于结果展示；执行路径由模型请求的工具名决定。
                                    if (context.decision.intent() == Intent.UNKNOWN
                                            || context.decision.intent() == conditions.intent())
                                        context.decision = conditions;
                                    else if (context.decision.intent() != Intent.ITINERARY)
                                        context.decision =
                                                new Decision(
                                                        Intent.ITINERARY,
                                                        "",
                                                        "",
                                                        "",
                                                        conditions.date(),
                                                        0);
                                    context.agent(label);
                                    String key = childKey(name, input.getInput());
                                    String saved = context.control.checkpoint(key);
                                    if (saved != null) {
                                        CachedChild cached =
                                                JsonUtils.getJsonCodec()
                                                        .fromJson(saved, CachedChild.class);
                                        if (cached.evidence().stream()
                                                .allMatch(
                                                        e ->
                                                                context.control
                                                                        .identity()
                                                                        .allows(e.tool()))) {
                                            cached.evidence().forEach(context::add);
                                            cached.agents().forEach(context::agent);
                                            context.childSucceeded(key);
                                            context.event("复用已完成子任务：" + label);
                                            return Mono.just(ToolResultBlock.text(cached.answer()));
                                        }
                                        context.control.checkpoint(key, null);
                                    }
                                    return context.share(
                                            key, () -> invoke(name, label, key, conditions));
                                })
                        .onErrorResume(
                                ex -> {
                                    context.rememberFailure(ex);
                                    if (context.failureStatus() != Status.FAILED)
                                        return Mono.error(ex);
                                    context.childFailed(childKey(name, input.getInput()));
                                    return Mono.just(
                                            ToolResultBlock.error(label + "助手执行失败，其他成功结果保留。"));
                                });
            }
        };
    }

    private Mono<ToolResultBlock> invoke(
            String name, String label, String key, Decision conditions) {
        RunContext child = context.child(conditions);
        TravelAgentFactory childFactory = factory.forContext(child);
        SubAgentTool delegate =
                new SubAgentTool(
                        () -> childFactory.child(name),
                        SubAgentConfig.builder()
                                .toolName(name)
                                .description(label + "助手")
                                .forwardEvents(false)
                                .build());
        String task =
                json(
                        Map.of(
                                "runId",
                                context.control.runId(),
                                "revision",
                                context.control.revision(),
                                "query",
                                context.request.question(),
                                "decision",
                                conditions));
        return delegate.callAsync(ToolCallParam.builder().input(Map.of("message", task)).build())
                .map(
                        result -> {
                            context.check();
                            if (result.getState() == ToolResultState.ERROR
                                    || result.getState() == ToolResultState.DENIED
                                    || result.getState() == ToolResultState.INTERRUPTED) {
                                context.childFailed(key);
                                return ToolResultBlock.error(label + "助手失败；已完成证据仍然保留。");
                            }
                            context.childSucceeded(key);
                            List<Evidence> facts = child.evidence();
                            if (facts.isEmpty()) {
                                context.childFailed(key);
                                return ToolResultBlock.error("子 Agent 没有提供实际查询证据。");
                            }
                            String answer = json(facts);
                            if (facts.stream()
                                    .allMatch(
                                            e ->
                                                    e.status() == Status.SUCCESS
                                                            || e.status() == Status.NO_DATA))
                                context.control.checkpoint(
                                        key,
                                        json(
                                                new CachedChild(
                                                        answer,
                                                        facts,
                                                        child.result(Status.SUCCESS, "")
                                                                .agents())));
                            return ToolResultBlock.text(answer);
                        });
    }

    private String childKey(String name, Map<String, Object> params) {
        return "child:"
                + name
                + ":"
                + RunContext.fingerprint(
                        Map.of("parameters", params, "sourceVersion", factory.cacheVersion()));
    }

    private static Decision conditions(String name, Map<String, Object> input) {
        String date = (String) input.get("date");
        return switch (name) {
            case "weather_agent" ->
                    new Decision(Intent.WEATHER, (String) input.get("city"), "", "", date, 0);
            case "train_agent" ->
                    new Decision(
                            Intent.TRAIN,
                            "",
                            (String) input.get("from"),
                            (String) input.get("to"),
                            date,
                            0);
            default ->
                    new Decision(
                            Intent.ITINERARY,
                            "",
                            (String) input.get("from"),
                            (String) input.get("to"),
                            date,
                            ((Number) input.get("days")).intValue());
        };
    }

    boolean allowed(String name) {
        if (name.equals("weather_agent")) return context.control.identity().allows("query_weather");
        if (name.equals("train_agent")) return context.control.identity().allows("query_trains");
        return context.control.identity().allows("query_weather")
                || context.control.identity().allows("query_trains");
    }

    public record CachedChild(String answer, List<Evidence> evidence, List<String> agents) {}

    static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"))
                .replaceFirst("^session_id: [^\\n]*\\n\\n", "");
    }
}
