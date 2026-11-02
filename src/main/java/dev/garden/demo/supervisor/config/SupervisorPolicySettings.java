package dev.garden.demo.supervisor.config;

import dev.garden.demo.supervisor.SupervisorPolicy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 运行策略的配置绑定。
 *
 * <p>与 {@link SupervisorSettings} 分开，避免新增字段破坏既有构造器调用。
 * 全部默认关闭，部署方按需开启；配置值在构造 {@link SupervisorPolicy} 时统一校验。
 * 业务规则可以通过 {@code supervisor.policy.business-rules} 列表声明，未声明时使用默认规则。
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
        @DefaultValue("false") boolean multiAgentPlannerEnabled,
        @DefaultValue("8") int multiAgentMaxPlanTasks,
        @DefaultValue("false") boolean topicSegmentationEnabled,
        @DefaultValue("6") int topicRecentTurns,
        @DefaultValue("0.6") double topicMinConfidence,
        @DefaultValue("3") int retryMaxAttempts,
        @DefaultValue("2000") long retryBackoffCapMs,
        @DefaultValue("false") boolean summaryFidelityAlertEnabled,
        @DefaultValue("1.0") double summaryFidelityThreshold,
        @DefaultValue("KEEP_CONDITION_VERBATIM") String summaryMissingConditionPolicy,
        @DefaultValue("false") boolean executionLimitsEnabled,
        @DefaultValue("64") int maxSteps,
        @DefaultValue("8192") int contextInputLimit,
        @DefaultValue("false") boolean loopDetectionEnabled,
        @DefaultValue("3") int maxRepeatedToolCalls,
        @DefaultValue("true") boolean agentCycleDetectionEnabled,
        @DefaultValue("false") boolean semanticLoopDetectionEnabled,
        @DefaultValue("12") int semanticLoopWindow,
        @DefaultValue("0.85") double semanticLoopThreshold,
        @DefaultValue("2") int semanticLoopMaxWarnings,
        @DefaultValue("false") boolean contextRecallEnabled,
        List<BusinessRuleSetting> businessRules) {

    /** 把扁平配置装配为运行期策略；非法值在这里抛异常，容器启动失败。 */
    public SupervisorPolicy toPolicy() {
        return new SupervisorPolicy(
                new SupervisorPolicy.BusinessPolicy(
                        businessRulesEnabled, businessMaxFutureDays, rules(businessRules)),
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
                        multiAgentMinLatencySavingMs,
                        multiAgentPlannerEnabled,
                        multiAgentMaxPlanTasks),
                new SupervisorPolicy.TopicPolicy(
                        topicSegmentationEnabled, topicRecentTurns, topicMinConfidence),
                new SupervisorPolicy.FailurePolicy(retryMaxAttempts, retryBackoffCapMs),
                new SupervisorPolicy.SummaryPolicy(
                        summaryFidelityAlertEnabled,
                        summaryFidelityThreshold,
                        contextRecallEnabled,
                        missingConditionPolicy(summaryMissingConditionPolicy)),
                new SupervisorPolicy.ExecutionPolicy(
                        executionLimitsEnabled, maxSteps, contextInputLimit),
                new SupervisorPolicy.LoopPolicy(
                        loopDetectionEnabled,
                        maxRepeatedToolCalls,
                        agentCycleDetectionEnabled,
                        semanticLoopDetectionEnabled,
                        semanticLoopWindow,
                        semanticLoopThreshold,
                        semanticLoopMaxWarnings));
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

    /** 未声明规则列表时沿用默认规则；声明后完全以配置为准，便于按租户替换规则集。 */
    private static List<SupervisorPolicy.BusinessRule> rules(List<BusinessRuleSetting> settings) {
        if (settings == null || settings.isEmpty()) {
            return SupervisorPolicy.BusinessRule.defaults();
        }
        List<SupervisorPolicy.BusinessRule> result = new ArrayList<>();
        for (BusinessRuleSetting setting : settings) {
            if (setting == null) {
                continue;
            }
            result.add(
                    new SupervisorPolicy.BusinessRule(
                            setting.id(),
                            tools(setting.tools()),
                            ruleType(setting.type()),
                            setting.field(),
                            setting.otherField(),
                            setting.suggestion()));
        }
        return List.copyOf(result);
    }

    /** 规则类型必须显式声明，拼写错误直接拒绝，不能静默变成空规则。 */
    private static SupervisorPolicy.BusinessRule.Type ruleType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("supervisor.policy.business-rules[].type 不能为空");
        }
        try {
            return SupervisorPolicy.BusinessRule.Type.valueOf(
                    value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "未知业务规则类型：" + value, error);
        }
    }

    /**
     * 解析多 Agent 模式；空值按关闭处理，避免未配置时容器启动失败。
     *
     * <p>YAML 1.1 会把未加引号的 {@code OFF} 解析成布尔 {@code false}，绑定到字符串字段后
     * 得到 {@code "false"}。这里只接受三个明确模式名，其余值一律拒绝，避免把拼写错误
     * 静默降级成关闭。
     */
    /** 解析关键条件缺失策略；拼写错误直接拒绝，不静默降级为只告警。 */
    private static SupervisorPolicy.SummaryPolicy.MissingConditionPolicy missingConditionPolicy(
            String value) {
        if (value == null || value.isBlank()) {
            return SupervisorPolicy.SummaryPolicy.MissingConditionPolicy.KEEP_CONDITION_VERBATIM;
        }
        try {
            return SupervisorPolicy.SummaryPolicy.MissingConditionPolicy.valueOf(
                    value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "supervisor.policy.summary-missing-condition-policy 只能是 ALERT_ONLY、"
                            + "RETRY_ON_MISSING、KEEP_CONDITION_VERBATIM 或 FAIL_FAST",
                    error);
        }
    }

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
