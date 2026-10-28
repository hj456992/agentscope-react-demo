package dev.garden.demo.supervisor;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 身份只由服务端令牌映射建立；模型和请求正文不能提供权限。
 *
 * <p>认证的“触发点”只有一个：{@link SupervisorAuthFilter}。所有请求进入 Controller 之前，
 * 过滤器调用 {@link #authenticate(String)} 校验令牌，把 {@link Identity} 放进 exchange 属性；
 * Controller 用 {@link CurrentIdentity} 声明参数、由解析器注入。也就是说：
 *
 * <ul>
 *   <li><b>规则只有一份</b>：令牌匹配、常量时间比较、权限集合构造都在本类；
 *   <li><b>触发只有一处</b>：新增接口默认受保护，不依赖开发者“记得写认证”；
 *   <li><b>放行名单</b>：仅有静态演示页等明确列出的公开路径，见
 *       {@link SupervisorAuthFilter} 中的 {@code PUBLIC_PATHS}；
 *   <li><b>本类仍可单独使用</b>：命令行或 Service 层测试可以直接构造 {@link Identity}，
 *       不必启动 Web 容器。
 * </ul>
 *
 * <p>如果以后引入 Spring Security，本类可以作为自定义 {@code AuthenticationManager} 的
 * 内核替换掉过滤器，规则本身不用改。
 */
public final class AccessPolicy {
    public record Identity(String tenantId, String userId, Set<String> permissions) {
        public Identity {
            if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank())
                throw new IllegalArgumentException("身份不能为空");
            permissions = Set.copyOf(permissions);
        }

        public static Identity local() {
            return new Identity(
                    "local",
                    "cli",
                    Set.of("query_weather", "query_trains", "search_knowledge", "memory:write"));
        }

        public boolean allows(String tool) {
            return permissions.contains(tool);
        }
    }

    private final Map<String, Identity> identities = new LinkedHashMap<>();

    public AccessPolicy(String mappings) {
        if (mappings == null || mappings.isBlank())
            throw new IllegalArgumentException("HTTP 服务必须配置 supervisor.auth-tokens");
        // 格式 token=tenant:user:permission,permission；只在启动时解析，不记录令牌。
        for (String entry : mappings.split(";")) {
            String[] pair = entry.split("=", 2);
            String[] parts = pair.length == 2 ? pair[1].split(":", 3) : new String[0];
            if (parts.length != 3 || pair[0].isBlank() || identities.containsKey(pair[0]))
                throw new IllegalArgumentException("supervisor.auth-tokens 格式无效");
            Identity identity =
                    new Identity(
                            parts[0],
                            parts[1],
                            parts[2].isBlank() ? Set.of() : Set.of(parts[2].split(",")));
            for (Identity existing : identities.values()) {
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
    public Identity resolve(String tenantId, String userId) {
        return identities.values().stream()
                .filter(value -> value.tenantId().equals(tenantId) && value.userId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new RunControl.Cancelled("任务身份已撤销"));
    }

    public Identity authenticate(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
            for (var entry : identities.entrySet()) {
                if (MessageDigest.isEqual(
                        entry.getKey().getBytes(StandardCharsets.UTF_8), supplied))
                    return entry.getValue();
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
