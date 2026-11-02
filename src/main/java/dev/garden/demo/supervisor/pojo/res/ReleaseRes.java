package dev.garden.demo.supervisor.pojo.res;

/** Agent 发布、灰度或回滚记录。 */
public record ReleaseRes(
        String agentId,
        String version,
        int trafficPercent,
        String action,
        String status,
        String actor,
        long createdAt) {}
