package dev.garden.demo.supervisor.pojo.dto;

import java.util.Set;

/** 身份只由服务端令牌映射建立；模型和请求正文不能提供权限。 */
public record IdentityDTO(String tenantId, String userId, Set<String> permissions) {
    public IdentityDTO {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("身份不能为空");
        }
        permissions = Set.copyOf(permissions);
    }

    public static IdentityDTO local() {
        return new IdentityDTO(
                "local",
                "cli",
                Set.of("query_weather", "query_trains", "search_knowledge", "memory:write"));
    }

    public boolean allows(String tool) {
        return permissions.contains(tool);
    }
}
