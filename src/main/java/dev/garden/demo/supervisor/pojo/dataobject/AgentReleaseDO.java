package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_agent_releases 表的行映射；一条记录对应一次发布、灰度或回滚。 */
public final class AgentReleaseDO {
    public long releaseId;
    public String agentId;
    public String version;
    public int trafficPercent;
    public String action;
    public String status;
    public String actor;
    public long createdAt;
}
