package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_approvals 表的行映射；parameters 只保存待执行参数，响应不返回正文。 */
public final class ApprovalDO {
    public String approvalId;
    public String tenantId;
    public String userId;
    public String runId;
    public String toolName;
    public String operationId;
    public String requestHash;
    public String parameters;
    public String status;
    public String requiredPermission;
    public long requestedAt;
    public long expiresAt;
    public String decidedBy;
    public long decidedAt;
    public String reason;
    public String operationStatus;
    public String operationResponse;
}
