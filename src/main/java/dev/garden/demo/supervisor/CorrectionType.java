package dev.garden.demo.supervisor;

/** 可计入运行期纠错预算的错误类别；权限拒绝只记事件，不计入可修正次数。 */
enum CorrectionType {
    INVALID_SCHEMA,
    TOOL_NOT_FOUND,
    BUSINESS_RULE_VIOLATION,
    INVALID_INPUT,
    PERMISSION_DENIED
}
