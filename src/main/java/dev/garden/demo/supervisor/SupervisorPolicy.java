package dev.garden.demo.supervisor;

import java.util.Objects;
import java.util.Set;

/**
 * 每次运行可独立开关的七项策略。
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
        SummaryPolicy summary) {
    public SupervisorPolicy {
        Objects.requireNonNull(business, "business");
        Objects.requireNonNull(factCheck, "factCheck");
        Objects.requireNonNull(correction, "correction");
        Objects.requireNonNull(multiAgent, "multiAgent");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(summary, "summary");
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
                SummaryPolicy.disabled());
    }

    /** 应用配置的安全开启值；语义事实校验仍默认关闭，避免普通任务增加模型调用。 */
    public static SupervisorPolicy standard() {
        return new SupervisorPolicy(
                new BusinessPolicy(true, 30),
                new FactCheckPolicy(true, false, Set.of("query_weather", "query_trains")),
                new CorrectionPolicy(true, 2, 2, 2, 6),
                new MultiAgentPolicy(MultiAgentPolicy.Mode.ADVISORY, 2, 4, 2, 2.0, 500),
                new TopicPolicy(true, 6, 0.6),
                FailurePolicy.defaults(),
                new SummaryPolicy(true, 1.0));
    }

    public SupervisorPolicy withBusiness(BusinessPolicy value) {
        return new SupervisorPolicy(
                value, factCheck, correction, multiAgent, topic, failure, summary);
    }

    public SupervisorPolicy withFactCheck(FactCheckPolicy value) {
        return new SupervisorPolicy(
                business, value, correction, multiAgent, topic, failure, summary);
    }

    public SupervisorPolicy withCorrection(CorrectionPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, value, multiAgent, topic, failure, summary);
    }

    public SupervisorPolicy withMultiAgent(MultiAgentPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, value, topic, failure, summary);
    }

    public SupervisorPolicy withTopic(TopicPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, value, failure, summary);
    }

    public SupervisorPolicy withFailure(FailurePolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, topic, value, summary);
    }

    public SupervisorPolicy withSummary(SummaryPolicy value) {
        return new SupervisorPolicy(
                business, factCheck, correction, multiAgent, topic, failure, value);
    }

    /** 工具执行前的确定性业务规则开关。 */
    public record BusinessPolicy(boolean enabled, int maxFutureDays) {
        public BusinessPolicy {
            if (maxFutureDays < 1) {
                throw new IllegalArgumentException("业务日期未来窗口必须大于 0");
            }
        }

        public static BusinessPolicy disabled() {
            return new BusinessPolicy(false, 30);
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

    /** 多 Agent 门槛；OFF 完全保留既有 spawn 行为。 */
    public record MultiAgentPolicy(
            Mode mode,
            int minParallelism,
            int maxAgents,
            int maxDepth,
            double maxTokenMultiplier,
            long minLatencySavingMs) {
        public MultiAgentPolicy {
            Objects.requireNonNull(mode, "mode");
            if (minParallelism < 1
                    || maxAgents < 1
                    || maxDepth < 1
                    || maxTokenMultiplier < 1
                    || minLatencySavingMs < 0) {
                throw new IllegalArgumentException("多 Agent 门槛参数无效");
            }
        }

        public static MultiAgentPolicy off() {
            return new MultiAgentPolicy(Mode.OFF, 2, 4, 2, 2.0, 500);
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

    /** 摘要忠实度评测与告警。 */
    public record SummaryPolicy(boolean alertEnabled, double fidelityThreshold) {
        public SummaryPolicy {
            if (fidelityThreshold < 0 || fidelityThreshold > 1) {
                throw new IllegalArgumentException("摘要忠实度阈值必须在 0 到 1 之间");
            }
        }

        public static SummaryPolicy disabled() {
            return new SummaryPolicy(false, 1.0);
        }
    }
}
