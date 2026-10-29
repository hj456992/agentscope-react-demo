package dev.garden.demo.supervisor;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 演示级业务语义规则：日期不能早于参考日、不能超过未来窗口，车票起终点不能同城。
 *
 * <p>规则在工具内部执行，不新增平台级规则表；城市白名单等平台能力尚未核实对应实现，
 * 本轮仍由数据源返回 {@code NO_DATA}，不把未知城市误报为业务违规。
 */
final class BusinessRules {
    private BusinessRules() {}

    static Optional<BusinessRuleViolation> validate(
            String tool,
            Map<String, Object> parameters,
            LocalDate today,
            SupervisorPolicy.BusinessPolicy policy) {
        if (!policy.enabled()) {
            return Optional.empty();
        }
        String dateText = Objects.toString(parameters.get("date"), "");
        LocalDate date;
        try {
            date = LocalDate.parse(dateText);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
        if (date.isBefore(today)) {
            return Optional.of(
                    new BusinessRuleViolation(
                            tool,
                            "DATE_IN_PAST",
                            "date",
                            "查询日期早于当前参考日",
                            "请改为今天或之后的日期。"));
        }
        if (date.isAfter(today.plusDays(policy.maxFutureDays()))) {
            return Optional.of(
                    new BusinessRuleViolation(
                            tool,
                            "DATE_TOO_FAR",
                            "date",
                            "查询日期超过允许的未来窗口",
                            "请改为 " + today.plusDays(policy.maxFutureDays()) + " 之前的日期。"));
        }
        if ("query_trains".equals(tool)) {
            String from = Objects.toString(parameters.get("from"), "");
            String to = Objects.toString(parameters.get("to"), "");
            if (!from.isBlank() && from.equals(to)) {
                return Optional.of(
                        new BusinessRuleViolation(
                                tool,
                                "SAME_CITY",
                                "to",
                                "出发城市与到达城市相同",
                                "请改为不同的出发城市和到达城市。"));
            }
        }
        return Optional.empty();
    }
}
