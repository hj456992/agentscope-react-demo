package dev.garden.demo.supervisor.pojo.dto;

/**
 * 单个 Agent 的模型用量。
 *
 * <p>只累计供应商返回的 token 字段；没有返回时保持 0，并由 {@code source} 标注
 * {@code provider} 或 {@code missing}，不按文本长度估算。
 */
public record AgentUsageDTO(
        String runId,
        String agentPath,
        String agentRole,
        int modelCalls,
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        long totalTokens,
        String source,
        int ownModelCalls,
        int subtreeModelCalls,
        int remainingModelCalls,
        boolean sharedBudget) {}
