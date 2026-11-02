package dev.garden.demo.supervisor.support;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 受控验收分支白名单。
 *
 * <p>只在实例显式开启 {@code supervisor.acceptance-hints-enabled} 时使用。提示只替换
 * 一次模型边界上的工具请求，工具仍走真实参数校验、权限、预算、重试和证据链路；
 * 不会跳过鉴权、不会增加预算，也不会伪造工具结果。
 */
public final class AcceptanceTrigger {
    /** 允许的提示及其最大注入次数。 */
    private static final Map<String, Integer> LIMITS =
            Map.ofEntries(
                    Map.entry("SPAWN_CHILD", 1),
                    Map.entry("UNKNOWN_TOOL_1", 1),
                    Map.entry("UNKNOWN_TOOL_2", 2),
                    Map.entry("UNKNOWN_TOOL_3", 3),
                    Map.entry("INVALID_SCHEMA_1", 1),
                    Map.entry("INVALID_SCHEMA_2", 2),
                    Map.entry("SAME_CITY", 1),
                    Map.entry("REPEAT_TOOL_4", 4),
                    Map.entry("REPEAT_TOOL_5", 5),
                    Map.entry("SEMANTIC_LOOP_2", 2),
                    Map.entry("BOOK_TICKET", 1));

    private static final Set<String> ALL = LIMITS.keySet();

    private AcceptanceTrigger() {}

    /** 是否在本实例允许的受控分支白名单内。 */
    public static boolean allowed(String hint) {
        return hint != null && ALL.contains(normalize(hint));
    }

    /** 单次运行允许注入的次数；未知提示返回 0。 */
    public static int limit(String hint) {
        return hint == null ? 0 : LIMITS.getOrDefault(normalize(hint), 0);
    }

    /**
     * 按第 {@code ordinal} 次注入生成工具名和参数。
     *
     * <p>日期取运行上下文中的业务日期，避免把某一天的验收样本写死进生产代码。
     */
    public static Call call(String hint, int ordinal, String today, String question) {
        String name = normalize(hint);
        String date = today;
        if (name.startsWith("UNKNOWN_TOOL")) {
            return new Call("unknown_acceptance_tool", Map.of("probe", "controlled"));
        }
        if (name.startsWith("INVALID_SCHEMA")) {
            return new Call("query_weather", Map.of("city", "杭州"));
        }
        if (name.equals("SAME_CITY")) {
            return new Call(
                    "query_trains", Map.of("from", "杭州", "to", "杭州", "date", date));
        }
        if (name.startsWith("REPEAT_TOOL")) {
            return new Call(
                    "query_weather", Map.of("city", "杭州", "date", date));
        }
        if (name.equals("SEMANTIC_LOOP_2") && ordinal >= 2) {
            return new Call(
                    "query_weather", Map.of("city", "杭州市", "date", date));
        }
        if (name.equals("SEMANTIC_LOOP_2")) {
            return new Call(
                    "query_weather", Map.of("city", "杭州", "date", date));
        }
        if (name.equals("BOOK_TICKET")) {
            // 写入工具需要人工审批；受控提示只固定业务参数，审批、账本和本地下游仍走真实链路。
            return new Call(
                    "book_ticket",
                    Map.of(
                            "from", "杭州",
                            "to", "上海",
                            "date", date,
                            "trainNumber", "G7501",
                            "passenger", "张三"));
        }
        if (name.equals("SPAWN_CHILD")) {
            return new Call(
                    "spawn_agent",
                    Map.of(
                            "task_name",
                            "acceptance_child",
                            "message",
                            "查询杭州明天的天气并返回结论。用户的原始问题是：" + question,
                            "agent_type",
                            "weather_agent",
                            "fork_turns",
                            "none"));
        }
        throw new IllegalArgumentException("未知受控验收提示：" + hint);
    }

    /** 受控工具请求；{@code name} 必须是当前固定工具快照中的名字或有意未知的名字。 */
    public record Call(String name, Map<String, Object> input) {}

    private static String normalize(String hint) {
        return hint == null ? "" : hint.strip().toUpperCase(Locale.ROOT);
    }
}
