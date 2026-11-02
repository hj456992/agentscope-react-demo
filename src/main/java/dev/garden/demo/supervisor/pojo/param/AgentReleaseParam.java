package dev.garden.demo.supervisor.pojo.param;

/** POST /agents/releases 的唯一入参：发布一个新版本并设置灰度流量比例。 */
public record AgentReleaseParam(
        String agentId, String version, int trafficPercent, String actor) {}
