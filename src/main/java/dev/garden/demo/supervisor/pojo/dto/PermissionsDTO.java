package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限复核证据。
 *
 * <p>{@code allowedTools} 是当前令牌映射直接给出的权限；{@code requiredTools} 是运行树
 * 历史消息与证据实际需要的工具；{@code filteredTools} 为两者之差。
 */
public record PermissionsDTO(
        Set<String> allowedTools,
        Set<String> requiredTools,
        Set<String> filteredTools,
        List<String> evidenceTools,
        int evidenceCount,
        boolean evidenceRecheckPassed,
        Set<String> directoryTools,
        Set<String> executionTools,
        Map<String, String> filteredReasons) {}
