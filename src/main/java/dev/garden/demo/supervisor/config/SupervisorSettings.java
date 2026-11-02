package dev.garden.demo.supervisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 所有数值均为本示例业务配置，不代表供应商吞吐保证。 */
@ConfigurationProperties(prefix = "supervisor")
public record SupervisorSettings(
        @DefaultValue("live") String mode,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("16") int maxModelCalls,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("8") int maxConcurrentRequests,
        @DefaultValue("2") int maxConcurrentPerUser,
        @DefaultValue("10") int tasksPerUserMinute,
        @DefaultValue("120") int modelCallsPerMinute,
        @DefaultValue("90000") int modelTokensPerMinute,
        @DefaultValue("10000") int maxStoredTasks,
        @DefaultValue("7d") Duration taskRetention,
        @DefaultValue("30d") Duration conversationRetention,
        @DefaultValue("90d") Duration preferenceRetention,
        @DefaultValue("jdbc:postgresql://127.0.0.1:5432/supervisor") String databaseUrl,
        @DefaultValue("sa") String databaseUsername,
        @DefaultValue("") String databasePassword,
        @DefaultValue("16") int databasePoolSize,
        @DefaultValue("") String authTokens,
        @DefaultValue("false") boolean acceptanceHintsEnabled,
        @DefaultValue("") String downstreamUrl,
        @DefaultValue("5s") Duration downstreamTimeout,
        @DefaultValue("0") long toolDelayMs) {
    public SupervisorSettings {
        if (!"offline".equals(mode) && !"live".equals(mode)) {
            throw new IllegalArgumentException("supervisor.mode 只能是 offline 或 live");
        }
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || taskRetention == null
                || taskRetention.isNegative()
                || taskRetention.isZero()
                || conversationRetention == null
                || conversationRetention.isNegative()
                || conversationRetention.isZero()
                || preferenceRetention == null
                || preferenceRetention.isNegative()
                || preferenceRetention.isZero()
                || maxModelCalls < 1
                || maxOutputTokens < 1
                || maxConcurrentRequests < 1
                || maxConcurrentPerUser < 1
                || tasksPerUserMinute < 1
                || modelCallsPerMinute < 1
                || modelTokensPerMinute < 1
                || maxStoredTasks < 1
                || databasePoolSize < 1) {
            throw new IllegalArgumentException("运行预算、存储保留和容量配置必须大于 0");
        }
        if (databaseUrl == null
                || (!databaseUrl.startsWith("jdbc:h2:")
                        && !databaseUrl.startsWith("jdbc:postgresql:"))) {
            throw new IllegalArgumentException("数据库地址必须是 jdbc:h2: 或 jdbc:postgresql:");
        }
        if (downstreamTimeout == null
                || downstreamTimeout.isNegative()
                || downstreamTimeout.isZero()
                || toolDelayMs < 0) {
            throw new IllegalArgumentException("下游超时和工具延迟必须大于 0 或为 0");
        }
        if (downstreamUrl != null
                && !downstreamUrl.isBlank()
                && !downstreamUrl.startsWith("http://")
                && !downstreamUrl.startsWith("https://")) {
            throw new IllegalArgumentException("supervisor.downstream-url 必须是 HTTP 地址");
        }
    }
}
