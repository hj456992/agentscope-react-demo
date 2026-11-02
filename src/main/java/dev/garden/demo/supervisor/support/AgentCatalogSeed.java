package dev.garden.demo.supervisor.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 Agent 目录种子。
 *
 * <p>四个角色可以真实执行；其余二十个是只读占位目录项，用于验证动态候选规模、
 * Schema 注入字节和超预算反馈。每个目录项声明能力白名单、网络出口白名单、数据分级、
 * 负责人、SLO 和部署单元；注册表服务据此签名、验签和审计。
 */
public final class AgentCatalogSeed {
    /** 目录 Schema 的注入预算；超过时目录服务发出超预算事件而不截断身份权限。 */
    public static final int SCHEMA_BUDGET_BYTES = 6_656;

    /** 单个目录项的静态定义。 */
    public record Entry(
            String agentId,
            String version,
            String role,
            String owner,
            String oncall,
            long sloMs,
            double costBudgetUsd,
            String dataClass,
            String deployUnit,
            String status,
            String manifest) {}

    private AgentCatalogSeed() {}

    /** 返回全部目录项；顺序固定，便于页面和审计核对。 */
    public static List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        result.add(
                entry(
                        "travel.supervisor",
                        "supervisor",
                        "travel-platform",
                        "travel-oncall",
                        2_000,
                        0.05,
                        List.of("query_weather", "query_trains", "search_knowledge", "book_ticket")));
        result.add(
                entry(
                        "travel.weather",
                        "weather_agent",
                        "travel-platform",
                        "weather-oncall",
                        1_500,
                        0.02,
                        List.of("query_weather")));
        result.add(
                entry(
                        "travel.train",
                        "train_agent",
                        "travel-platform",
                        "train-oncall",
                        1_500,
                        0.02,
                        List.of("query_trains")));
        result.add(
                entry(
                        "travel.itinerary",
                        "itinerary_agent",
                        "travel-platform",
                        "itinerary-oncall",
                        3_000,
                        0.08,
                        List.of("query_weather", "query_trains", "search_knowledge")));
        result.add(placeholder("travel.flight", "flight_agent", "travel-platform", "flight-oncall"));
        result.add(placeholder("travel.hotel", "hotel_agent", "travel-platform", "hotel-oncall"));
        result.add(placeholder("travel.cruise", "cruise_agent", "travel-platform", "cruise-oncall"));
        result.add(placeholder("travel.bus", "bus_agent", "travel-platform", "bus-oncall"));
        result.add(placeholder("travel.metro", "metro_agent", "travel-platform", "metro-oncall"));
        result.add(
                placeholder("travel.car-rental", "car_rental_agent", "travel-platform", "car-oncall"));
        result.add(placeholder("travel.visa", "visa_agent", "travel-platform", "visa-oncall"));
        result.add(
                placeholder("travel.insurance", "insurance_agent", "travel-platform", "insurance-oncall"));
        result.add(placeholder("travel.expense", "expense_agent", "travel-platform", "expense-oncall"));
        result.add(placeholder("travel.meeting", "meeting_agent", "travel-platform", "meeting-oncall"));
        result.add(placeholder("travel.dining", "dining_agent", "travel-platform", "dining-oncall"));
        result.add(
                placeholder("travel.attraction", "attraction_agent", "travel-platform", "attraction-oncall"));
        result.add(
                placeholder("travel.translation", "translation_agent", "travel-platform", "translation-oncall"));
        result.add(
                placeholder(
                        "travel.accessibility",
                        "accessibility_agent",
                        "travel-platform",
                        "accessibility-oncall"));
        result.add(placeholder("travel.carbon", "carbon_agent", "travel-platform", "carbon-oncall"));
        result.add(placeholder("travel.refund", "refund_agent", "travel-platform", "refund-oncall"));
        result.add(placeholder("travel.loyalty", "loyalty_agent", "travel-platform", "loyalty-oncall"));
        result.add(
                placeholder(
                        "travel.group", "group_travel_agent", "travel-platform", "group-oncall"));
        result.add(
                placeholder(
                        "travel.corporate-policy",
                        "corporate_policy_agent",
                        "travel-platform",
                        "corporate-oncall"));
        result.add(
                placeholder(
                        "travel.emergency", "emergency_agent", "travel-platform", "emergency-oncall"));
        return List.copyOf(result);
    }

    /** 返回可执行角色当前固定的目录版本。 */
    public static String versionFor(String role) {
        for (Entry entry : entries()) {
            if (entry.status().equals("PUBLISHED") && entry.role().equals(role)) {
                return entry.version();
            }
        }
        return "unregistered";
    }

    private static Entry entry(
            String agentId,
            String role,
            String owner,
            String oncall,
            long sloMs,
            double costBudgetUsd,
            List<String> allowedTools) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", agentId);
        manifest.put("version", "1");
        manifest.put("role", role);
        manifest.put("allowedTools", allowedTools);
        manifest.put("networkAllowlist", List.of("127.0.0.1"));
        manifest.put("dataClass", "internal-demo");
        manifest.put("schemaBytes", schemaBytes(agentId, role, allowedTools));
        return new Entry(
                agentId,
                "1",
                role,
                owner,
                oncall,
                sloMs,
                costBudgetUsd,
                "internal-demo",
                "local-supervisor",
                "PUBLISHED",
                Json.write(manifest));
    }

    private static Entry placeholder(String agentId, String role, String owner, String oncall) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", agentId);
        manifest.put("version", "1");
        manifest.put("role", role);
        manifest.put("allowedTools", List.of());
        manifest.put("networkAllowlist", List.of());
        manifest.put("dataClass", "internal-demo");
        // 只读占位目录项保留较大的 Schema 描述，用于真实触发 6656 字节注入预算。
        manifest.put("schemaBytes", 420);
        return new Entry(
                agentId,
                "1",
                role,
                owner,
                oncall,
                5_000,
                0.0,
                "internal-demo",
                "local-catalog-only",
                "READONLY_PLACEHOLDER",
                Json.write(manifest));
    }

    private static int schemaBytes(String agentId, String role, List<String> tools) {
        return (agentId + role + String.join(",", tools)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + 64;
    }

    /** 极小的确定性 JSON 写入，避免目录种子依赖容器内的 Jackson 配置。 */
    private static final class Json {
        private Json() {}

        static String write(Object value) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("目录清单无法序列化", error);
            }
        }
    }
}
