package dev.garden.demo.supervisor.pojo.res;

import java.util.List;

/**
 * 当前实例的脱敏配置证据。
 *
 * <p>用于验收记录固定“实际运行配置”。不返回认证令牌、模型密钥、下游凭据，也不返回
 * 数据库连接串中的账号信息；认证身份只返回租户、用户和权限，用于核对身份矩阵。
 */
public record ConfigRes(
        String mode,
        long timeoutSeconds,
        int maxModelCalls,
        int maxOutputTokens,
        int maxSteps,
        int contextInputLimit,
        int maxConcurrentRequests,
        int maxConcurrentPerUser,
        int tasksPerUserMinute,
        int modelCallsPerMinute,
        int modelTokensPerMinute,
        int maxStoredTasks,
        long taskRetentionSeconds,
        long conversationRetentionSeconds,
        long preferenceRetentionSeconds,
        String databaseType,
        List<String> authIdentities,
        boolean businessRulesEnabled,
        int businessRuleCount,
        boolean factCheckEnabled,
        boolean semanticVerifierEnabled,
        boolean correctionLimitsEnabled,
        String multiAgentMode,
        boolean multiAgentPlannerEnabled,
        int multiAgentMaxAgents,
        int multiAgentMaxDepth,
        boolean topicSegmentationEnabled,
        boolean summaryFidelityAlertEnabled,
        double summaryFidelityThreshold,
        String summaryMissingConditionPolicy,
        boolean contextRecallEnabled,
        boolean executionLimitsEnabled,
        boolean loopDetectionEnabled,
        boolean semanticLoopDetectionEnabled,
        int maxRepeatedToolCalls,
        boolean acceptanceHintsEnabled,
        boolean downstreamEnabled,
        long downstreamTimeoutSeconds,
        long toolDelayMs) {}
