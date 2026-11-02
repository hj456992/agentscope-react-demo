package dev.garden.demo.supervisor.pojo.dto;

/**
 * 记忆治理事实：保留期、嵌入实现、本地存储与墓碑数量。
 *
 * <p>只陈述当前本地实例实际做到的能力；跨副本删除、合规认证等未实现项明确标为未声明。
 */
public record MemoryGovernanceDTO(
        long conversationRetentionHours,
        long preferenceRetentionHours,
        String embedding,
        String embeddingLocation,
        String storage,
        String crossReplica,
        String dataResidency,
        int pendingCandidates,
        int approvedCandidates,
        int rejectedCandidates,
        int activeVectors,
        int tombstones) {}
