package dev.garden.demo.supervisor;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 每次运行可独立开关的运行策略。
 *
 * <p>默认值 {@link #defaults()} 关闭本轮新增能力，保证直接构造 Supervisor 的既有调用行为不变；
 * Spring 应用通过 {@code supervisor.policy.*} 绑定后使用 {@link #standard()} 的安全阈值。
 * 策略只影响运行期判断，不改变状态机取值和对外 HTTP 错误码。
 */
public record SupervisorPolicy(
        BusinessPolicy business,
        FactCheckPolicy factCheck,
        CorrectionPolicy correction,
        MultiAgentPolicy multiAgent,
        TopicPolicy topic,
        FailurePolicy failure,
        SummaryPolicy summary,
        ExecutionPolicy execution,
        LoopPolicy loop) {
    public SupervisorPolicy {
        Objects.requireNonNull(business, "business");
        Objects.requireNonNull(factCheck, "factCheck");
        Objects.requireNonNull(correction, "correction");
        Objects.requireNonNull(multiAgent, "multiAgent");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(loop, "loop");
    }

    /** 直接调用 Supervisor 时的保守默认值：新能力关闭，失败分类行为与现状等价。 */
    public static SupervisorPolicy defaults() {
        return new SupervisorPolicy(
                BusinessPolicy.disabled(),
                FactCheckPolicy.disabled(),
                CorrectionPolicy.disabled(),
                MultiAgentPolicy.off(),
                TopicPolicy.disabled(),
                FailurePolicy.defaults(),
                SummaryPolicy.disabled(),
                ExecutionPolicy.disabled(),
                LoopPolicy.disabled());
    }

    /** 应用配置的安全开启值；语义事实校验仍默认关闭，避免普通任务增加模型调用。 */
    public static SupervisorPolicy standard() {
        return new SupervisorPolicy(
                new BusinessPolicy(true, 30, BusinessRule.defaults()),
                new FactCheckPolicy(true, false, Set.of("query_weather", "query_trains")),
                new CorrectionPolicy(true, 2, 2, 2, 6),
                new MultiAgentPolicy(
                        MultiAgentPolicy.Mode.ADVISORY, 2, 4, 2, 2.0, 500, false, 8),
                new TopicPolicy(true, 6, 0.6),
                FailurePolicy.defaults(),
                new SummaryPolicy(
                        true, 1.0, false, SummaryPolicy.MissingConditionPolicy.KEEP_CONDITION_VERBATIM),
                new ExecutionPolicy(true, 64, ExecutionPolicy.DEFAULT_CONTEXT_INPUT_LIMIT),
                new LoopPolicy(true, 3, true, false, 12, 0.85, 2));
    }

    public SupervisorPolicy withBusiness(BusinessPolicy value) {
        return new SupervisorPolicy(
                value, factCheck, correction, multiAgent, topic, failure, summary, execution, loop);
    }

    public SupervisorPolicy withFactCheck(FactCheckPolicy value) {
        return new SupervisorPolicy(
                business, value, correction, multiAgent, topic, failure, summary, execution, loop);
    }

    public SupervisorPolicy withCorrection(CorrectionPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, value, multiAgent, topic, failure, summary, execution, loop);
    }

    public SupervisorPolicy withMultiAgent(MultiAgentPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, value, topic, failure, summary, execution, loop);
    }

    public SupervisorPolicy withTopic(TopicPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, value, failure, summary, execution, loop);
    }

    public SupervisorPolicy withFailure(FailurePolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, topic, value, summary, execution, loop);
    }

    public SupervisorPolicy withSummary(SummaryPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, topic, failure, value, execution, loop);
    }

    public SupervisorPolicy withExecution(ExecutionPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, topic, failure, summary, value, loop);
    }

    public SupervisorPolicy withLoop(LoopPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, topic, failure, summary, execution, value);
    }

    /** 工具执行前的确定性业务规则开关；规则来自配置声明，不写死在工具内部。 */
    public record BusinessPolicy(boolean enabled, int maxFutureDays, List<BusinessRule> rules) {
        public BusinessPolicy {
            if (maxFutureDays < 1) {
                throw new IllegalArgumentException("业务日期未来窗口必须大于 0");
            }
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        }

        public static BusinessPolicy disabled() {
            return new BusinessPolicy(false, 30, BusinessRule.defaults());
        }

        /** 开启默认规则；测试和配置只关心窗口大小时使用这个入口。 */
        public static BusinessPolicy enabled(int maxFutureDays) {
            return new BusinessPolicy(true, maxFutureDays, BusinessRule.defaults());
        }
    }

    /**
     * 一条可声明的业务规则。
     *
     * <p>规则只描述“哪个工具的哪个字段、在什么条件下不成立”，具体执行由
     * {@link BusinessRules} 完成。规则标识会进入内部证据和事件，不进入对外 HTTP 错误码。
     */
    public record BusinessRule(
            String id,
            Set<String> tools,
            Type type,
            String field,
            String otherField,
            String suggestion) {
        public BusinessRule {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("业务规则标识不能为空");
            }
            tools = Set.copyOf(Objects.requireNonNull(tools, "tools"));
            if (tools.isEmpty()) {
                throw new IllegalArgumentException("业务规则至少绑定一个工具");
            }
            Objects.requireNonNull(type, "type");
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("业务规则字段不能为空");
            }
            if (type == Type.FIELD_NOT_EQUAL
                    && (otherField == null || otherField.isBlank())) {
                throw new IllegalArgumentException("字段比较规则必须声明另一个字段");
            }
        }

        public static List<BusinessRule> defaults() {
            return List.of(
                    new BusinessRule(
                            "DATE_IN_PAST",
                            Set.of("query_weather", "query_trains"),
                            Type.DATE_NOT_BEFORE_TODAY,
                            "date",
                            null,
                            "请改为今天或之后的日期。"),
                    new BusinessRule(
                            "DATE_TOO_FAR",
                            Set.of("query_weather", "query_trains"),
                            Type.DATE_NOT_AFTER_DAYS,
                            "date",
                            null,
                            "请改到允许的未来窗口内。"),
                    new BusinessRule(
                            "SAME_CITY",
                            Set.of("query_trains"),
                            Type.FIELD_NOT_EQUAL,
                            "from",
                            "to",
                            "请改为不同的出发城市和到达城市。"));
        }

        public enum Type {
            DATE_NOT_BEFORE_TODAY,
            DATE_NOT_AFTER_DAYS,
            FIELD_NOT_EQUAL
        }
    }

    /** 最终回答的事实校验；语义校验独立开关，默认关闭。 */
    public record FactCheckPolicy(
            boolean enabled, boolean semanticVerifierEnabled, Set<String> highRiskTools) {
        public FactCheckPolicy {
            highRiskTools = Set.copyOf(Objects.requireNonNull(highRiskTools, "highRiskTools"));
        }

        public static FactCheckPolicy disabled() {
            return new FactCheckPolicy(false, false, Set.of());
        }
    }

    /** 纠错次数上限；关闭时只记录事件，不影响既有预算与终态。 */
    public record CorrectionPolicy(
            boolean enabled,
            int maxConsecutiveSchemaErrors,
            int maxToolNotFound,
            int maxBusinessViolations,
            int maxTotalCorrections) {
        public CorrectionPolicy {
            if (maxConsecutiveSchemaErrors < 1
                    || maxToolNotFound < 1
                    || maxBusinessViolations < 1
                    || maxTotalCorrections < 1) {
                throw new IllegalArgumentException("纠错上限必须大于 0");
            }
        }

        public static CorrectionPolicy disabled() {
            return new CorrectionPolicy(false, 2, 2, 2, 6);
        }
    }

    /**
     * 多 Agent 门槛与规划器。
     *
     * <p>{@code plannerEnabled} 只控制 {@code plan_agents} 工具是否进入工具表；
     * {@code maxPlanTasks} 限制一次计划中的子任务数量。规划器只输出经过校验的计划，
     * 不自动创建子会话，实际派发仍由模型调用 {@code spawn_agent} 完成。
     */
    public record MultiAgentPolicy(
            Mode mode,
            int minParallelism,
            int maxAgents,
            int maxDepth,
            double maxTokenMultiplier,
            long minLatencySavingMs,
            boolean plannerEnabled,
            int maxPlanTasks) {
        public MultiAgentPolicy {
            Objects.requireNonNull(mode, "mode");
            if (minParallelism < 1
                    || maxAgents < 1
                    || maxDepth < 1
                    || maxTokenMultiplier < 1
                    || minLatencySavingMs < 0
                    || maxPlanTasks < 1
                    || maxPlanTasks > 32) {
                throw new IllegalArgumentException("多 Agent 门槛参数无效");
            }
        }

        public static MultiAgentPolicy off() {
            return new MultiAgentPolicy(Mode.OFF, 2, 4, 2, 2.0, 500, false, 8);
        }

        public enum Mode {
            OFF,
            ADVISORY,
            ENFORCE
        }
    }

    /** 话题切分只影响历史装配，不删除会话。 */
    public record TopicPolicy(boolean enabled, int recentTurns, double minConfidence) {
        public TopicPolicy {
            if (recentTurns < 1 || minConfidence < 0 || minConfidence > 1) {
                throw new IllegalArgumentException("话题切分配置无效");
            }
        }

        public static TopicPolicy disabled() {
            return new TopicPolicy(false, 6, 0.6);
        }
    }

    /** 工具与模型共用的重试上限；默认值与既有实现保持一致。 */
    public record FailurePolicy(int maxAttempts, long backoffCapMs) {
        public FailurePolicy {
            if (maxAttempts < 1 || backoffCapMs < 0) {
                throw new IllegalArgumentException("失败重试配置无效");
            }
        }

        public static FailurePolicy defaults() {
            return new FailurePolicy(3, 2000);
        }
    }

    /** 摘要忠实度评测、告警以及压缩原文召回。 */
    /**
     * 上下文压缩与摘要策略。
     *
     * <p>{@code missingConditionPolicy} 控制模型摘要遗漏关键条件时的处理方式：
     * {@code ALERT_ONLY} 只记录告警；{@code RETRY_ON_MISSING} 追加缺失条件重试一次；
     * {@code KEEP_CONDITION_VERBATIM} 把用户原文中的日期、城市、座席、金额等条件块
     * 原样注入替换历史，不依赖模型复述；{@code FAIL_FAST} 在摘要仍缺失关键条件时
     * 明确失败，不安装可能丢失条件的摘要。无论哪种模式，条件块无法放入窗口时都不会
     * 静默丢弃，而是保留原历史并返回上下文超限。
     */
    public record SummaryPolicy(
            boolean alertEnabled,
            double fidelityThreshold,
            boolean recallEnabled,
            MissingConditionPolicy missingConditionPolicy) {
        public SummaryPolicy {
            Objects.requireNonNull(missingConditionPolicy, "missingConditionPolicy");
            if (fidelityThreshold < 0 || fidelityThreshold > 1) {
                throw new IllegalArgumentException("摘要忠实度阈值必须在 0 到 1 之间");
            }
        }

        /** 关键条件缺失时的处理方式；名称进入配置和事件，不使用内部错误码。 */
        public enum MissingConditionPolicy {
            ALERT_ONLY,
            RETRY_ON_MISSING,
            KEEP_CONDITION_VERBATIM,
            FAIL_FAST
        }

        public static SummaryPolicy disabled() {
            return new SummaryPolicy(
                    false, 1.0, false, MissingConditionPolicy.ALERT_ONLY);
        }
    }

    /**
     * 单轮总步数与模型输入上限。
     *
     * <p>{@code contextInputLimit} 按 JSON UTF-8 字节估算，绑定
     * {@code supervisor.policy.context-input-limit}；每次运行读取本次绑定的值，
     * 不在上下文构建器里写死。目录 Schema 注入预算由
     * {@code AgentCatalogSeed.SCHEMA_BUDGET_BYTES} 单独控制。
     */
    public record ExecutionPolicy(boolean enabled, int maxSteps, int contextInputLimit) {
        /** 未显式配置时的输入上限；仅为配置默认值，部署方可按模型窗口覆盖。 */
        public static final int DEFAULT_CONTEXT_INPUT_LIMIT = 8192;

        public ExecutionPolicy {
            if (maxSteps < 1) {
                throw new IllegalArgumentException("总步数上限必须大于 0");
            }
            if (contextInputLimit < 1) {
                throw new IllegalArgumentException("模型输入上限必须大于 0");
            }
        }

        public static ExecutionPolicy disabled() {
            return new ExecutionPolicy(false, 64, DEFAULT_CONTEXT_INPUT_LIMIT);
        }
    }

    /**
     * 循环检测阈值。
     *
     * <p>{@code maxRepeatedToolCalls} 是同一工具同一参数允许执行的次数；超过一次先返回
     * {@code LOOP_DETECTED} 工具错误，再超过一次终止本轮。{@code agentCycleDetectionEnabled}
     * 控制协作调用图回边检测。{@code semanticEnabled} 控制轻量语义指纹检测；
     * {@code semanticWindow}、{@code semanticThreshold}、{@code semanticMaxWarnings}
     * 分别控制近期目标窗口、双元组 Jaccard 阈值和提醒次数。
     */
    public record LoopPolicy(
            boolean enabled,
            int maxRepeatedToolCalls,
            boolean agentCycleDetectionEnabled,
            boolean semanticEnabled,
            int semanticWindow,
            double semanticThreshold,
            int semanticMaxWarnings) {
        public LoopPolicy {
            if (maxRepeatedToolCalls < 1) {
                throw new IllegalArgumentException("重复工具调用上限必须大于 0");
            }
            if (semanticWindow < 1 || semanticWindow > 32) {
                throw new IllegalArgumentException("语义循环窗口必须在 1 到 32 之间");
            }
            if (semanticThreshold <= 0 || semanticThreshold > 1) {
                throw new IllegalArgumentException("语义循环阈值必须在 0 到 1 之间");
            }
            if (semanticMaxWarnings < 1) {
                throw new IllegalArgumentException("语义循环提醒次数必须大于 0");
            }
        }

        public static LoopPolicy disabled() {
            return new LoopPolicy(false, 3, false, false, 12, 0.85, 2);
        }
    }
}
