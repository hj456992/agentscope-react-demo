package dev.garden.demo.supervisor.pojo.dto;

/**
 * 外部副作用账本证据。
 *
 * <p>只返回操作号、状态、参数指纹和尝试次数；不返回下游请求正文或凭据。
 * {@code attempts} 是提交与查询之和；拆分字段用于证明 UNKNOWN 之后只查询、不重放。
 */
public record SideEffectEvidenceDTO(
        String tool,
        String operationId,
        String status,
        String parameterHash,
        long updatedAt,
        int attempts,
        int submitAttempts,
        int lookupAttempts,
        String lastError) {}
