package dev.garden.demo.supervisor.pojo.dto;

/** 下游适配器能够给出的权威结果。 */
public enum SideEffectOutcomeStatus {
    SUCCESS,
    FAILED,
    PROCESSING,
    UNKNOWN,
    CONFIRMED_NOT_EXECUTED
}
