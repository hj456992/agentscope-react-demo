package dev.garden.demo.supervisor.pojo.dto;

import java.util.Objects;

/** 下游返回给账本的一次操作结果。 */
public record SideEffectOutcomeDTO(SideEffectOutcomeStatus status, String response) {
    public SideEffectOutcomeDTO {
        Objects.requireNonNull(status);
    }

    public static SideEffectOutcomeDTO success(String response) {
        return new SideEffectOutcomeDTO(SideEffectOutcomeStatus.SUCCESS, response);
    }
}
