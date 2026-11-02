package dev.garden.demo.supervisor.pojo.dto;

/**
 * 人工审批证据。
 *
 * <p>P0 先固定字段契约，审批表落地后由审批服务填充；不返回审批人的认证令牌。
 */
public record ApprovalDTO(
        String approvalId,
        String runId,
        String toolName,
        String operationId,
        String requestHash,
        String status,
        String requestedBy,
        String requiredPermission,
        Long requestedAt,
        Long expiresAt,
        String decidedBy,
        Long decidedAt,
        String reason,
        String operationStatus,
        String operationResponse) {}
