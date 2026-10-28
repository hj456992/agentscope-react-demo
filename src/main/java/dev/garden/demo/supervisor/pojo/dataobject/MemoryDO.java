package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_memories 表的行映射；deleted 为墓碑标记。 */
public final class MemoryDO {
    public String tenantId;
    public String userId;
    public String memoryKey;
    public String memoryValue;
    public int version;
    public String source;
    public long expiresAt;
    public boolean deleted;
}
