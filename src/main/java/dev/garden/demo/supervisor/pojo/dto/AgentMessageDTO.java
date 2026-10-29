package dev.garden.demo.supervisor.pojo.dto;

import java.util.Set;

/** V2 消息及其历史来源权限；旧持久消息缺失权限字段时使用空集合。 */
public record AgentMessageDTO(
        String sender,
        String recipient,
        String kind,
        String message,
        ResultDTO result,
        Set<String> requiredPermissions) {
    public AgentMessageDTO {
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
