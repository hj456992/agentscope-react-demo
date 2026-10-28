package dev.garden.demo.supervisor.pojo.dto;

/** V2 子会话协议传输对象。 */
public record AgentSnapshotDTO(String agentId, String agentName, String agentRole, String agentStatus, String lastAnswer, ResultDTO result) {}
