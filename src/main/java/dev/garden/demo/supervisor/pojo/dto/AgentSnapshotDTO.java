package dev.garden.demo.supervisor.pojo.dto;

import java.util.Set;

/** V2 会话快照及返回内容所需的历史权限。 */
public record AgentSnapshotDTO(
        String agentId,
        String agentName,
        String agentRole,
        String agentStatus,
        String lastAnswer,
        ResultDTO result,
        Set<String> requiredPermissions) {
    public AgentSnapshotDTO {
        java.util.LinkedHashSet<String> permissions = new java.util.LinkedHashSet<>();
        if (requiredPermissions != null) {
            permissions.addAll(requiredPermissions);
        }
        if (result != null) {
            result.evidence().forEach(evidence -> permissions.add(evidence.tool()));
        }
        requiredPermissions = Set.copyOf(permissions);
    }
}
