package dev.garden.demo.supervisor;

/**
 * 多 Agent 路由评估结果。
 *
 * <p>{@code allowed} 只在收益为正时为真；拒绝原因进入内部事件与测试断言，不新增对外
 * HTTP 错误码或状态机取值。并行度、预计 token 和时延收益一并保留，便于复盘决策依据。
 */
record RoutingDecision(
        boolean allowed,
        Reason reason,
        int parallelism,
        long estimatedTokens,
        long latencySavingMs) {
    /** 决策原因；除 {@link #BENEFIT_POSITIVE} 外都表示不建议创建子 Agent。 */
    enum Reason {
        BENEFIT_POSITIVE,
        TASK_COUPLED,
        DEPTH_LIMIT_EXCEEDED,
        AGENT_LIMIT_EXCEEDED,
        PARALLELISM_TOO_LOW,
        COST_EXCEEDS_BENEFIT,
        LATENCY_GAIN_TOO_LOW
    }

    /** 供运行事件与测试读取的单行描述，不包含用户原文。 */
    String event() {
        return "多Agent决策："
                + reason
                + "，parallelism="
                + parallelism
                + "，estimatedTokens="
                + estimatedTokens
                + "，latencySavingMs="
                + latencySavingMs;
    }
}
