package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import io.agentscope.core.message.ToolResultBlock;

import java.time.LocalDate;
import java.util.*;

/**
 * 查询工具只负责取数。示例数据与 Agent 分开，方便替换数据来源和注入故障。
 *
 * <p>读工具统一返回 {@code Mono<ToolResultBlock>}：Agent 的调用链本身是响应式的，
 * 工具若用阻塞签名会把整条链拖回同步模型。{@link #weather} / {@link #trains} 的 block()
 * 只为兼容命令行与旧测试保留，Web 路径一律走 {@code *Async}。
 */
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
                if (!from.equals("杭州") || !to.equals("上海")) return List.of();
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
        Decision d = context.decision;
        String expected = d.intent() == Intent.WEATHER ? d.city() : d.to();
        return execute(
                "query_weather",
                params,
                Objects.equals(city, expected) && Objects.equals(date, d.date()),
                () -> {
                    Map<String, Object> value = data.weather(city, date);
                    Map<String, Object> result = new LinkedHashMap<>(params);
                    result.put("weather", value);
                    return new Evidence(
                            "query_weather",
                            value.isEmpty() ? Status.NO_DATA : Status.SUCCESS,
                            "demo-v1",
                            result);
                });
    }

    reactor.core.publisher.Mono<ToolResultBlock> trainsAsync(String from, String to, String date) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", Objects.toString(from, ""));
        params.put("to", Objects.toString(to, ""));
        params.put("date", Objects.toString(date, ""));
        Decision d = context.decision;
        return execute(
                "query_trains",
                params,
                Objects.equals(from, d.from())
                        && Objects.equals(to, d.to())
                        && Objects.equals(date, d.date())
                        && !blank(from)
                        && !blank(to)
                        && !from.equals(to),
                () -> {
                    List<Map<String, Object>> value = data.trains(from, to, date);
                    Map<String, Object> result = new LinkedHashMap<>(params);
                    result.put("trains", value);
                    return new Evidence(
                            "query_trains",
                            value.isEmpty() ? Status.NO_DATA : Status.SUCCESS,
                            "demo-v1",
                            result);
                });
    }

    /**
     * 统一的读工具执行入口。
     *
     * <p>{@code Mono.defer} 保证权限、参数、日期这些校验只在真正订阅时执行一次（而不是建链时），
     * 并且校验失败直接返回一个“失败的证据”而不是抛异常——工具错误要作为数据回传给模型，
     * 让它有机会修正参数，而不是让整个任务失败。
     *
     * <p>{@code onErrorResume} 只把“普通失败”收敛成证据；取消/超时/预算类异常会原样上抛，
     * 因为这些异常代表任务级终态，不能降级成一条普通查询失败。
     */
    private reactor.core.publisher.Mono<ToolResultBlock> execute(
            String tool,
            Map<String, Object> params,
            boolean matches,
            java.util.function.Supplier<Evidence> action) {
        return reactor.core.publisher.Mono.defer(
                        () -> {
                            context.check();
                            if (!context.control.identity().allows(tool)) {
                                context.correction(tool, "PERMISSION_DENIED");
                                return reactor.core.publisher.Mono.just(
                                        failure(tool, Status.FAILED, "PERMISSION_DENIED"));
                            }
                            boolean dateValid = true;
                            try {
                                LocalDate.parse((String) params.get("date"));
                            } catch (RuntimeException ex) {
                                dateValid = false;
                            }
                            if (!matches || !dateValid) {
                                context.correction(tool, "INVALID_INPUT");
                                return reactor.core.publisher.Mono.just(
                                        failure(
                                                tool,
                                                Status.INVALID_INPUT,
                                                "参数与用户已确认条件不一致，请修正或澄清。"));
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
                                    .map(e -> ToolResultBlock.text(json(e)));
                        })
                .onErrorResume(
                        ex -> {
                            context.rememberFailure(ex);
                            if (context.failureStatus() != Status.FAILED)
                                return reactor.core.publisher.Mono.error(ex);
                            return reactor.core.publisher.Mono.just(
                                    failure(tool, Status.FAILED, "查询失败；其他成功结果继续保留。"));
                        });
    }

    private ToolResultBlock failure(String tool, Status status, String message) {
        Evidence evidence = new Evidence(tool, status, "demo-v1", Map.of("message", message));
        context.add(evidence);
        return ToolResultBlock.error(json(evidence));
    }
}
