package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;
import java.util.Set;

/**
 * 子运行摘要。
 *
 * <p>来自同一根运行下的任务记录；{@code answer}、{@code evidenceTools} 只在该身份拥有
 * 子运行全部所需权限时返回，否则保持 {@code null}/空列表，避免详情接口绕过证据权限。
 */
public record ChildRunDTO(
        String runId,
        String parentRunId,
        String agentPath,
        String agentRole,
        String agentVersion,
        String state,
        String agentStatus,
        int generation,
        int revision,
        int modelCalls,
        int maxModelCalls,
        long deadline,
        long createdAt,
        boolean accepted,
        String answer,
        String resultStatus,
        String resultDigest,
        List<String> evidenceTools,
        Set<String> requiredPermissions,
        TaskBriefDTO taskBrief,
        String spawnOperationId,
        String acceptanceReceiptKey,
        long completedAt,
        boolean recovered) {}
