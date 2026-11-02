package dev.garden.demo.supervisor.pojo.res;

/**
 * 外部写操作账本快照。
 *
 * <p>{@code response} 是下游返回的脱敏摘要；{@code status} 只使用账本已有的
 * RUNNING / SUCCESS / FAILED / UNKNOWN 等值，不新增对外错误码。
 */
public record OperationRes(
        String tool,
        String operationId,
        String status,
        String parameterHash,
        long updatedAt,
        int submitAttempts,
        int lookupAttempts,
        String response) {}
