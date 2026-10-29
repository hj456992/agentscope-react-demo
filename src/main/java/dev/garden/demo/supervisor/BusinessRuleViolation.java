package dev.garden.demo.supervisor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数在业务语义上不成立的确定性结论。
 *
 * <p>{@code ruleId} 只用于内部工具结果与事件，不进入公开 HTTP 错误码表；
 * {@code suggestion} 是给模型修正参数的安全提示，不包含内部规则实现。
 */
record BusinessRuleViolation(
        String tool, String ruleId, String field, String reason, String suggestion) {
    Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("errorCode", "BUSINESS_RULE_VIOLATION");
        details.put("ruleId", ruleId);
        details.put("field", field);
        details.put("reason", reason);
        details.put("suggestion", suggestion);
        return details;
    }
}
