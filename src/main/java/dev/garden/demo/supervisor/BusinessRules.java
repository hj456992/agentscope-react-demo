package dev.garden.demo.supervisor;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 演示级业务规则执行器：按配置声明的规则顺序检查工具参数。
 *
 * <p>规则在工具内部、数据源之前执行；这里只解释规则，不把规则写死在某个工具里。
 * 当前支持“日期不能早于今天”“日期不能超过未来窗口”“两个字段不能相同”三类。
 * 城市白名单等平台能力尚未核实对应实现，未知城市仍由数据源返回 {@code NO_DATA}，
 * 不把未知城市误报为业务违规。
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
        for (SupervisorPolicy.BusinessRule rule : policy.rules()) {
            if (!rule.tools().contains(tool)) {
                continue;
            }
            Optional<BusinessRuleViolation> violation =
                    evaluate(rule, tool, parameters, today, policy.maxFutureDays());
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private static Optional<BusinessRuleViolation> evaluate(
            SupervisorPolicy.BusinessRule rule,
            String tool,
            Map<String, Object> parameters,
            LocalDate today,
            int maxFutureDays) {
        return switch (rule.type()) {
            case DATE_NOT_BEFORE_TODAY -> {
                LocalDate date = date(parameters, rule.field());
                if (date != null && date.isBefore(today)) {
                    yield Optional.of(
                            violation(
                                    rule,
                                    tool,
                                    "date",
                                    "查询日期早于当前参考日",
                                    suggestion(rule, "请改为今天或之后的日期。")));
                }
                yield Optional.empty();
            }
            case DATE_NOT_AFTER_DAYS -> {
                LocalDate date = date(parameters, rule.field());
                if (date != null && date.isAfter(today.plusDays(maxFutureDays))) {
                    yield Optional.of(
                            violation(
                                    rule,
                                    tool,
                                    "date",
                                    "查询日期超过允许的未来窗口",
                                    suggestion(
                                            rule,
                                            "请改为 "
                                                    + today.plusDays(maxFutureDays)
                                                    + " 之前的日期。")));
                }
                yield Optional.empty();
            }
            case FIELD_NOT_EQUAL -> {
                String left = Objects.toString(parameters.get(rule.field()), "");
                String right = Objects.toString(parameters.get(rule.otherField()), "");
                if (!left.isBlank() && left.equals(right)) {
                    yield Optional.of(
                            violation(
                                    rule,
                                    tool,
                                    rule.otherField(),
                                    "字段 " + rule.field() + " 与 " + rule.otherField() + " 不能相同",
                                    suggestion(rule, "请改为不同的取值。")));
                }
                yield Optional.empty();
            }
        };
    }

    private static LocalDate date(Map<String, Object> parameters, String field) {
        try {
            return LocalDate.parse(Objects.toString(parameters.get(field), ""));
        } catch (RuntimeException invalid) {
            // 日期格式错误由工具的统一参数校验处理，不能重复计为业务违规。
            return null;
        }
    }

    private static BusinessRuleViolation violation(
            SupervisorPolicy.BusinessRule rule,
            String tool,
            String field,
            String reason,
            String suggestion) {
        return new BusinessRuleViolation(tool, rule.id(), field, reason, suggestion);
    }

    private static String suggestion(SupervisorPolicy.BusinessRule rule, String fallback) {
        return rule.suggestion() == null || rule.suggestion().isBlank()
                ? fallback
                : rule.suggestion();
    }
}
