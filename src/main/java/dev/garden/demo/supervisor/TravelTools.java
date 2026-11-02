package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.ToolResultBlock;

import java.time.LocalDate;
import java.util.*;

/** 查询工具只负责取数。示例数据与 Agent 分开，方便替换数据来源和注入故障。 */
public final class TravelTools {
    public interface DataSource {
        /** 更换数据快照或规则时更新版本，使旧结果无法回放。 */
        default String version() {
            return "demo-v1";
        }

        Map<String, Object> weather(String city, String date);

        List<Map<String, Object>> trains(String from, String to, String date);
    }

    public static DataSource demoData() {
        return new DataSource() {
            @Override
            public Map<String, Object> weather(String city, String date) {
                return switch (city) {
                    case "杭州" -> Map.of("temperature", 18, "condition", "多云");
                    case "上海" -> Map.of("temperature", 22, "condition", "晴");
                    default -> Map.of();
                };
            }

            @Override
            public List<Map<String, Object>> trains(String from, String to, String date) {
                if (!from.equals("杭州") || !to.equals("上海")) {
                    return List.of();
                }
                return List.of(
                        Map.of(
                                "number",
                                "G-DEMO-001",
                                "departure",
                                "08:00",
                                "arrival",
                                "09:00",
                                "durationMinutes",
                                60,
                                "seat",
                                "二等座",
                                "price",
                                80,
                                "availableSeats",
                                10,
                                "note",
                                "虚构车次，不代表实际时刻或余票"));
            }
        };
    }

    private final RunContext context;
    private final DataSource data;

    TravelTools(RunContext context, DataSource data) {
        this.context = context;
        this.data = data;
    }

    public ToolResultBlock weather(String city, String date) {
        return weatherAsync(city, date).block();
    }

    public ToolResultBlock trains(String from, String to, String date) {
        return trainsAsync(from, to, date).block();
    }

    reactor.core.publisher.Mono<ToolResultBlock> weatherAsync(String city, String date) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("city", Objects.toString(city, ""));
        params.put("date", Objects.toString(date, ""));
        return execute(
                "query_weather",
                params,
                city != null && !city.isBlank(),
                () -> {
                    Map<String, Object> value = data.weather(city, date);
                    Map<String, Object> result = new LinkedHashMap<>(params);
                    result.put("weather", value);
                    return new EvidenceDTO(
                            "query_weather",
                            value.isEmpty() ? Status.NO_DATA : Status.SUCCESS,
                            data.version(),
                            result);
                });
    }

    reactor.core.publisher.Mono<ToolResultBlock> trainsAsync(String from, String to, String date) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Objects.toString(from, ""));
        params.put("to", Objects.toString(to, ""));
        params.put("date", Objects.toString(date, ""));
        return execute(
                "query_trains",
                params,
                from != null && !from.isBlank() && to != null && !to.isBlank(),
                () -> {
                    List<Map<String, Object>> value = data.trains(from, to, date);
                    Map<String, Object> result = new LinkedHashMap<>(params);
                    result.put("trains", value);
                    return new EvidenceDTO(
                            "query_trains",
                            value.isEmpty() ? Status.NO_DATA : Status.SUCCESS,
                            data.version(),
                            result);
                });
    }

    private reactor.core.publisher.Mono<ToolResultBlock> execute(
            String tool,
            Map<String, Object> params,
            boolean matches,
            java.util.function.Supplier<EvidenceDTO> action) {
        return reactor.core.publisher.Mono.defer(
                        () -> {
                            context.check();
                            if (!context.control.identity().allows(tool)) {
                                context.correction(CorrectionType.PERMISSION_DENIED, tool);
                                return reactor.core.publisher.Mono.just(
                                        failure(tool, params, Status.FAILED, "PERMISSION_DENIED"));
                            }
                            boolean dateValid = true;
                            try {
                                LocalDate.parse((String) params.get("date"));
                            } catch (RuntimeException ex) {
                                dateValid = false;
                            }
                            if (!matches || !dateValid) {
                                context.correction(CorrectionType.INVALID_INPUT, tool);
                                return reactor.core.publisher.Mono.just(
                                        failure(
                                                tool,
                                                params,
                                                Status.INVALID_INPUT,
                                                "城市或日期参数无效，请根据工具定义修正。"));
                            }
                            Optional<BusinessRuleViolation> violation =
                                    BusinessRules.validate(
                                            tool,
                                            params,
                                            context.today,
                                            context.control.policy().business());
                            if (violation.isPresent()) {
                                BusinessRuleViolation rule = violation.get();
                                CorrectionDecision decision =
                                        context.correction(
                                                CorrectionType.BUSINESS_RULE_VIOLATION, tool);
                                if (decision == CorrectionDecision.FAIL) {
                                    throw new CorrectionExhausted(
                                            Status.FAILED,
                                            "业务参数多次不成立，当前轮次已停止，请补充需求后重试。");
                                }
                                String message = rule.reason() + "。" + rule.suggestion();
                                ToolResultBlock rejected =
                                        failure(
                                                tool,
                                                params,
                                                Status.INVALID_INPUT,
                                                message,
                                                rule.details());
                                // 同城路线属于无法通过改写参数完成的确定性拒绝；
                                // 直接结束本轮并给出规则说明，避免模型在无解输入上耗尽预算。
                                // 过去日期等规则仍返回给模型，允许改成合法日期后重试。
                                if (!"SAME_CITY".equals(rule.ruleId())) {
                                    return reactor.core.publisher.Mono.just(rejected);
                                }
                                return reactor.core.publisher.Mono.just(
                                        ToolResultBlock.of(
                                                rejected.getOutput(),
                                                Map.of(
                                                        "endTurn",
                                                        true,
                                                        "turnStatus",
                                                        context.outcome().name(),
                                                        "turnReply",
                                                        message)));
                            }
                            return new ToolExecutor(context)
                                    .read(
                                            tool,
                                            Map.of(
                                                    "parameters",
                                                    params,
                                                    "sourceVersion",
                                                    data.version()),
                                            action)
                                    .map(
                                            e -> {
                                                context.correctionSuccess(tool);
                                                return ToolResultBlock.text(json(e));
                                            });
                        })
                .onErrorResume(
                        ex -> {
                            if (ex instanceof CorrectionExhausted) {
                                return reactor.core.publisher.Mono.error(ex);
                            }
                            context.rememberFailure(ex);
                            if (context.failureStatus() != Status.FAILED) {
                                return reactor.core.publisher.Mono.error(ex);
                            }
                            return reactor.core.publisher.Mono.just(
                                    failure(
                                            tool,
                                            params,
                                            Status.FAILED,
                                            "查询失败；其他成功结果继续保留。",
                                            failureDetails(ex)));
                        });
    }

    private ToolResultBlock failure(
            String tool, Map<String, Object> params, Status status, String message) {
        return failure(tool, params, status, message, Map.of());
    }

    /**
     * 提取失败分类、供应商错误码和重试间隔，写入工具结果证据。
     *
     * <p>只保留分类名称和错误码，不记录响应正文；重试间隔来自下游的 Retry-After 或
     * 传输异常携带的时间，页面据此区分可重试限流和不可重试的输入错误。
     */
    private static Map<String, Object> failureDetails(Throwable error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("failureClass", FailureClassifier.classify(error).name());
        String code = FailureClassifier.providerCode(error);
        if (!code.isBlank()) {
            details.put("providerCode", code);
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ToolExecutor.TemporaryFailure temporary
                    && temporary.retryAfter != null) {
                details.put("retryAfterMs", temporary.retryAfter.toMillis());
                break;
            }
        }
        return details;
    }

    private ToolResultBlock failure(
            String tool,
            Map<String, Object> params,
            Status status,
            String message,
            Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(params);
        details.put("message", message);
        details.putAll(extra);
        EvidenceDTO evidence = new EvidenceDTO(tool, status, data.version(), details);
        context.add(evidence);
        return ToolResultBlock.error(json(evidence));
    }
}
