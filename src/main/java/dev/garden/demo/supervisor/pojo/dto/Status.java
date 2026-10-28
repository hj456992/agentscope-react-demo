package dev.garden.demo.supervisor.pojo.dto;

/** 任务与证据的终态；状态名称会作为持久化字符串，不能随意重命名。 */
public enum Status {
    SUCCESS,
    NEEDS_INPUT,
    UNKNOWN_INTENT,
    NO_DATA,
    PARTIAL,
    INVALID_INPUT,
    FAILED,
    TIMEOUT,
    RUNNING,
    CANCELLED,
    REPLACED,
    BUDGET_EXHAUSTED,
    CONTEXT_LIMIT,
    RESULT_UNKNOWN
}
