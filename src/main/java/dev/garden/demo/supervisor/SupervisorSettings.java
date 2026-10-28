package dev.garden.demo.supervisor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 所有数值均为本示例业务配置，不代表供应商吞吐保证。 */
@ConfigurationProperties(prefix = "supervisor")
public record SupervisorSettings(
        @DefaultValue("offline") String mode,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("16") int maxModelCalls,
        @DefaultValue("1024") int maxOutputTokens,
        @DefaultValue("48000") int inputBytes,
        @DefaultValue("8") int maxConcurrentRequests,
        @DefaultValue("2") int maxConcurrentPerUser,
        @DefaultValue("10") int tasksPerUserMinute,
        @DefaultValue("120") int modelCallsPerMinute,
        @DefaultValue("90000") int modelTokensPerMinute,
        @DefaultValue("10000") int maxStoredTasks,
        @DefaultValue("7d") Duration taskRetention,
        @DefaultValue("30d") Duration conversationRetention,
        @DefaultValue("90d") Duration preferenceRetention,
        @DefaultValue("jdbc:h2:file:./data/supervisor") String databaseUrl,
        @DefaultValue("") String authTokens) {
    public SupervisorSettings {
        if (!"offline".equals(mode) && !"live".equals(mode))
            throw new IllegalArgumentException("supervisor.mode 只能是 offline 或 live");
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
                || inputBytes < 1024
                || maxConcurrentRequests < 1
                || maxConcurrentPerUser < 1
                || tasksPerUserMinute < 1
                || modelCallsPerMinute < 1
                || modelTokensPerMinute < 1
                || maxStoredTasks < 1) throw new IllegalArgumentException("运行预算、存储保留和容量配置必须大于 0");
        if (databaseUrl == null || !databaseUrl.startsWith("jdbc:h2:"))
            throw new IllegalArgumentException("请配置本地 H2 数据库");
    }
}
