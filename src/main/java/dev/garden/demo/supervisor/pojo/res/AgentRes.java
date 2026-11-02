package dev.garden.demo.supervisor.pojo.res;

import java.util.List;

/** Agent 目录项只读视图；不返回清单签名密钥。 */
public record AgentRes(
        String agentId,
        String version,
        String role,
        String owner,
        String oncall,
        long sloMs,
        double costBudget,
        String dataClass,
        String deployUnit,
        String status,
        boolean signatureVerified,
        List<String> allowedTools,
        List<String> networkAllowlist,
        int schemaBytes,
        long createdAt) {}
