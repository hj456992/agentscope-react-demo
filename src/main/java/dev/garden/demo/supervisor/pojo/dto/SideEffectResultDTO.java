package dev.garden.demo.supervisor.pojo.dto;

/** 字符串结果不可变，避免回放时泄露可修改的嵌套对象。 */
public record SideEffectResultDTO(
        String operationId,
        SideEffectStatus status,
        String response,
        int submitAttempts,
        int lookupAttempts) {}
