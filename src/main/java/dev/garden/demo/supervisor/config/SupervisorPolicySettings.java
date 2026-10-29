package dev.garden.demo.supervisor.config;

import dev.garden.demo.supervisor.SupervisorPolicy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 七项运行策略的配置绑定。
 *
 * <p>与 {@link SupervisorSettings} 分开，避免新增字段破坏既有构造器调用。
 * 全部默认关闭，部署方按需开启；配置值在构造 {@link SupervisorPolicy} 时统一校验。
 */
@ConfigurationProperties(prefix = "supervisor.policy")
public record SupervisorPolicySettings(
        @DefaultValue("false") boolean businessRulesEnabled,
        @DefaultValue("30") int businessMaxFutureDays,
        @DefaultValue("false") boolean factCheckEnabled,
        @DefaultValue("false") boolean semanticVerifierEnabled,
        @DefaultValue("query_weather,query_trains") String factHighRiskTools,
        @DefaultValue("false") boolean correctionLimitsEnabled,
        @DefaultValue("2") int maxConsecutiveSchemaErrors,
        @DefaultValue("2") int maxToolNotFound,
        @DefaultValue("2") int maxBusinessViolations,
        @DefaultValue("6") int maxTotalCorrections,
        @DefaultValue("OFF") String multiAgentMode,
        @DefaultValue("2") int multiAgentMinParallelism,
        @DefaultValue("4") int multiAgentMaxAgents,
        @DefaultValue("2") int multiAgentMaxDepth,
        @DefaultValue("2.0") double multiAgentMaxTokenMultiplier,
        @DefaultValue("500") long multiAgentMinLatencySavingMs,
        @DefaultValue("false") boolean topicSegmentationEnabled,
        @DefaultValue("6") int topicRecentTurns,
        @DefaultValue("0.6") double topicMinConfidence,
        @DefaultValue("3") int retryMaxAttempts,
        @DefaultValue("2000") long retryBackoffCapMs,
        @DefaultValue("false") boolean summaryFidelityAlertEnabled,
        @DefaultValue("1.0") double summaryFidelityThreshold) {

    /** 把扁平配置装配为运行期策略；非法值在这里抛异常，容器启动失败。 */
    public SupervisorPolicy toPolicy() {
        return new SupervisorPolicy(
                new SupervisorPolicy.BusinessPolicy(businessRulesEnabled, businessMaxFutureDays),
                new SupervisorPolicy.FactCheckPolicy(
                        factCheckEnabled, semanticVerifierEnabled, tools(factHighRiskTools)),
                new SupervisorPolicy.CorrectionPolicy(
                        correctionLimitsEnabled,
                        maxConsecutiveSchemaErrors,
                        maxToolNotFound,
                        maxBusinessViolations,
                        maxTotalCorrections),
                new SupervisorPolicy.MultiAgentPolicy(
                        mode(multiAgentMode),
                        multiAgentMinParallelism,
                        multiAgentMaxAgents,
                        multiAgentMaxDepth,
                        multiAgentMaxTokenMultiplier,
                        multiAgentMinLatencySavingMs),
                new SupervisorPolicy.TopicPolicy(
                        topicSegmentationEnabled, topicRecentTurns, topicMinConfidence),
                new SupervisorPolicy.FailurePolicy(retryMaxAttempts, retryBackoffCapMs),
                new SupervisorPolicy.SummaryPolicy(
                        summaryFidelityAlertEnabled, summaryFidelityThreshold));
    }

    private static Set<String> tools(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    result.add(part.strip());
                }
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 解析多 Agent 模式；空值按关闭处理，避免未配置时容器启动失败。
     *
     * <p>YAML 1.1 会把未加引号的 {@code OFF} 解析成布尔 {@code false}，绑定到字符串字段后
     * 得到 {@code "false"}。这里只接受三个明确模式名，其余值一律拒绝，避免把拼写错误
     * 静默降级成关闭。
     */
    private static SupervisorPolicy.MultiAgentPolicy.Mode mode(String value) {
        if (value == null || value.isBlank()) {
            return SupervisorPolicy.MultiAgentPolicy.Mode.OFF;
        }
        try {
            return SupervisorPolicy.MultiAgentPolicy.Mode.valueOf(
                    value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "supervisor.policy.multi-agent-mode 只能是 OFF、ADVISORY 或 ENFORCE（YAML 中请写成 \"OFF\"）",
                    error);
        }
    }
}
