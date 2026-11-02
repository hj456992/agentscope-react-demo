package dev.garden.demo.supervisor.pojo.param;

/** POST /agents/rollbacks 的唯一入参：把角色回滚到指定目录版本。 */
public record AgentRollbackParam(String agentId, String version, String actor) {}
