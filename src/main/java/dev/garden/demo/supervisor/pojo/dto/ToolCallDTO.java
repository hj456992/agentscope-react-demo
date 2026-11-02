package dev.garden.demo.supervisor.pojo.dto;

/**
 * 一次工具调用的页面证据。
 *
 * <p>由模型会话快照中的 {@code tool_use}/{@code tool_result} 按调用编号配对得到；
 * 参数只暴露指纹，不暴露完整请求内容。
 */
public record ToolCallDTO(
        String runId,
        String agentPath,
        String callId,
        String tool,
        String argumentFingerprint,
        String status,
        Long startedAt,
        Long endedAt,
        Long durationMs,
        int resultBytes,
        String resultSummary,
        String operationId,
        boolean paired,
        boolean reused,
        String failureClass,
        String providerCode,
        Long retryAfterMs) {}
