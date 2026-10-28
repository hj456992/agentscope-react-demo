package dev.garden.demo.supervisor.pojo.dto;

import java.time.Duration;

/** 单轮运行预算；所有子 Agent 共用同一份。 */
public record LimitsDTO(Duration timeout, int modelCalls, int outputTokens) {
    public LimitsDTO {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || modelCalls < 1
                || outputTokens < 1) {
            throw new IllegalArgumentException("运行预算必须为正数");
        }
    }

    public static LimitsDTO defaults() {
        return new LimitsDTO(Duration.ofSeconds(60), 16, 1024);
    }
}
