package dev.garden.demo.supervisor;

/**
 * 多 Agent 运行期硬上限触发。
 *
 * <p>只在 {@code ENFORCE} 模式下、深度或活动子会话数量超限时抛出；协作工具捕获后
 * 转成内部 {@code MULTI_AGENT_LIMIT} 工具错误，不创建子会话，也不改变任务状态机。
 */
final class MultiAgentLimit extends RuntimeException {
    MultiAgentLimit(String message) {
        super(message);
    }
}
