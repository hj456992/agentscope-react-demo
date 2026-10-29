package dev.garden.demo.supervisor;

/**
 * 多 Agent 路由评估所需的确定性输入。
 *
 * <p>字段全部由调用方给出，不读取模型隐藏状态：是否紧耦合、子任务数量、可并行度、
 * 当前会话深度、单 Agent 与多 Agent 的预计 token、并行带来的预计时延收益，以及时延是否关键。
 * 预计值为演示级估算，只用于记录决策与执行硬上限，不代表真实计费口径。
 */
record RoutingInput(
        boolean tightlyCoupled,
        int subtaskCount,
        int parallelism,
        int depth,
        long singleAgentTokens,
        long multiAgentTokens,
        long latencySavingMs,
        boolean latencyCritical) {
    RoutingInput {
        if (subtaskCount < 0 || parallelism < 0 || depth < 0) {
            throw new IllegalArgumentException("路由输入的数量、并行度和深度不能为负");
        }
        if (singleAgentTokens < 0 || multiAgentTokens < 0 || latencySavingMs < 0) {
            throw new IllegalArgumentException("路由输入的预计 token 与时延收益不能为负");
        }
    }
}
