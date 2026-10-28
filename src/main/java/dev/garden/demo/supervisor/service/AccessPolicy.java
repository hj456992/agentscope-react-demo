package dev.garden.demo.supervisor.service;

import dev.garden.demo.supervisor.RunControl;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 身份只由服务端令牌映射建立；模型和请求正文不能提供权限。 */
public final class AccessPolicy {
    private final Map<String, IdentityDTO> identities = new LinkedHashMap<>();

    public AccessPolicy(String mappings) {
        if (mappings == null || mappings.isBlank()) {
            throw new IllegalArgumentException("HTTP 服务必须配置 supervisor.auth-tokens");
        }
        // 格式 token=tenant:user:permission,permission；只在启动时解析，不记录令牌。
        for (String entry : mappings.split(";")) {
            String[] pair = entry.split("=", 2);
            String[] parts = pair.length == 2 ? pair[1].split(":", 3) : new String[0];
            if (parts.length != 3 || pair[0].isBlank() || identities.containsKey(pair[0])) {
                throw new IllegalArgumentException("supervisor.auth-tokens 格式无效");
            }
            IdentityDTO identity =
                    new IdentityDTO(
                            parts[0],
                            parts[1],
                            parts[2].isBlank() ? Set.of() : Set.of(parts[2].split(",")));
            for (IdentityDTO existing : identities.values()) {
                if (existing.tenantId().equals(identity.tenantId())
                        && existing.userId().equals(identity.userId())
                        && !existing.permissions().equals(identity.permissions())) {
                    throw new IllegalArgumentException("同一身份的令牌权限配置必须一致");
                }
            }
            identities.put(pair[0], identity);
        }
    }

    /** 重启恢复和执行时重新读取服务端映射，已撤销的身份不能沿用落库权限。 */
    public IdentityDTO resolve(String tenantId, String userId) {
        return identities.values().stream()
                .filter(value -> value.tenantId().equals(tenantId) && value.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new RunControl.Cancelled("任务身份已撤销"));
    }

    public IdentityDTO authenticate(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
            for (var entry : identities.entrySet()) {
                if (MessageDigest.isEqual(
                        entry.getKey().getBytes(StandardCharsets.UTF_8), supplied)) {
                    return entry.getValue();
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
