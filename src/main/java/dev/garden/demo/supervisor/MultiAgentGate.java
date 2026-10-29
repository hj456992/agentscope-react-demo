package dev.garden.demo.supervisor;

/**
 * 多 Agent 的量化决策函数。
 *
 * <p>按固定顺序检查：紧耦合、深度、子任务数量、可并行度、token 成本、时延收益。
 * 先触发的原因即为最终原因，避免同时命中多个条件时结论不稳定。参数来自
 * {@link SupervisorPolicy.MultiAgentPolicy}；{@code OFF} 模式调用方不应调用本类。
 */
final class MultiAgentGate {
    private MultiAgentGate() {}

    static RoutingDecision evaluate(
            RoutingInput input, SupervisorPolicy.MultiAgentPolicy policy) {
        RoutingDecision.Reason reason;
        if (input.tightlyCoupled()) {
            reason = RoutingDecision.Reason.TASK_COUPLED;
        } else if (input.depth() > policy.maxDepth()) {
            reason = RoutingDecision.Reason.DEPTH_LIMIT_EXCEEDED;
        } else if (input.subtaskCount() > policy.maxAgents()) {
            reason = RoutingDecision.Reason.AGENT_LIMIT_EXCEEDED;
        } else if (input.parallelism() < policy.minParallelism()) {
            reason = RoutingDecision.Reason.PARALLELISM_TOO_LOW;
        } else if (input.multiAgentTokens()
                > input.singleAgentTokens() * policy.maxTokenMultiplier()) {
            reason = RoutingDecision.Reason.COST_EXCEEDS_BENEFIT;
        } else if (!input.latencyCritical()
                && input.latencySavingMs() < policy.minLatencySavingMs()) {
            reason = RoutingDecision.Reason.LATENCY_GAIN_TOO_LOW;
        } else {
            reason = RoutingDecision.Reason.BENEFIT_POSITIVE;
        }
        return new RoutingDecision(
                reason == RoutingDecision.Reason.BENEFIT_POSITIVE,
                reason,
                input.parallelism(),
                input.multiAgentTokens(),
                input.latencySavingMs());
    }
}
