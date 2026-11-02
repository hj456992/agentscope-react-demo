package dev.garden.demo.supervisor.pojo.res;

import java.util.Map;

/**
 * 从任务、检查点和用量记录实时聚合的指标。
 *
 * <p>所有字段来自当前数据库中的真实任务；没有样本时返回 0，不补造数值。
 * {@code byState} 是按任务终态分组的计数，{@code p95DurationMs} 使用已完成任务的实际耗时。
 */
public record MetricsRes(
        long collectedAt,
        int tasks,
        int running,
        int completed,
        double failureRate,
        double p95DurationMs,
        long modelCalls,
        long promptTokens,
        long completionTokens,
        long toolCalls,
        long failedToolCalls,
        long rateLimitedEvents,
        long queueRejections,
        int approvalsPending,
        int approvalsDecided,
        Map<String, Integer> byState,
        int alertsOpen,
        java.util.List<AlertRes> alerts) {}
