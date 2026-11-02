package dev.garden.demo.supervisor.pojo.param;

/** POST /approvals 的唯一入参：批准或拒绝一个待审批的写入操作。 */
public record ApprovalParam(String approvalId, String decision, String reason) {}
