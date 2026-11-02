package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_memory_candidates 的数据库行。 */
public class MemoryCandidateDO {
    public String candidateId;
    public String tenantId;
    public String userId;
    public String memoryKey;
    public String memoryValue;
    public String source;
    public String status;
    public int expectedVersion;
    public long requestedAt;
    public long expiresAt;
    public long decidedAt;
    public String decidedBy;
    public String reason;
    public Integer appliedVersion;
}
