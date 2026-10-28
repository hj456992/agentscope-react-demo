package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_tasks 表的行映射，payload 列存放 TaskDO 的 JSON。 */
public final class TaskRowDO {
    public String runId;
    public String tenantId;
    public String userId;
    public String sessionId;
    public String requestId;
    public long createdAt;
    public String payload;
}
