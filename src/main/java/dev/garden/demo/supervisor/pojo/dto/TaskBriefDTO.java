package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/**
 * 子运行任务书证据。
 *
 * <p>目标来自子任务实际收到的请求；计划任务、依赖和批次来自父任务当时写入的
 * {@code plan:v1:current} 检查点。继承历史只返回字节数和消息数，不返回正文。
 */
public record TaskBriefDTO(
        String objective,
        String role,
        String planTaskId,
        List<String> dependsOn,
        int batch,
        String outputFormat,
        int budgetModelCalls,
        long deadline,
        int inheritedMessages,
        int inheritedBytes,
        String forkMode,
        String spawnOperationId) {}
