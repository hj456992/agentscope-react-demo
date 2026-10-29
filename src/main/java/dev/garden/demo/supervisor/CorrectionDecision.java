package dev.garden.demo.supervisor;

/** 纠错预算对当前工具调用的处理决定。 */
enum CorrectionDecision {
    PROCEED,
    DISABLE_TOOL,
    FORCE_CLARIFY,
    FAIL
}
