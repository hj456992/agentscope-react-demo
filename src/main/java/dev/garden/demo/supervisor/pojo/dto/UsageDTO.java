package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/** 运行树的总用量与逐 Agent 明细；总用量只做求和，不推断价格。 */
public record UsageDTO(
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        long totalTokens,
        String source,
        List<AgentUsageDTO> agents) {}
