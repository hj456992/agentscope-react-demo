package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_registry_audit 表的行映射；只记录动作、对象、结论和脱敏说明。 */
public final class RegistryAuditDO {
    public long auditId;
    public String action;
    public String subject;
    public String outcome;
    public String detail;
    public String actor;
    public long createdAt;
}
