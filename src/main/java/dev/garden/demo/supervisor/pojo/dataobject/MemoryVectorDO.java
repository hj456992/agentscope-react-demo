package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_memory_vectors 的数据库行；vectorJson 只在服务端使用，不进入页面。 */
public class MemoryVectorDO {
    public String tenantId;
    public String userId;
    public String memoryKey;
    public String vectorJson;
    public int dimensions;
    public String source;
    public int version;
    public long updatedAt;
    public boolean deleted;
}
