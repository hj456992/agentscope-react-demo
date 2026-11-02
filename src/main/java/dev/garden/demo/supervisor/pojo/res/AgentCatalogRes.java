package dev.garden.demo.supervisor.pojo.res;

import java.util.List;

/**
 * 动态候选目录及 Schema 注入统计。
 *
 * <p>{@code schemaBytes} 是当前身份可见目录项的清单字节数总和；超过
 * {@code schemaBudgetBytes} 时 {@code overBudget=true} 并记录事件，不静默截断。
 */
public record AgentCatalogRes(
        int total,
        int executable,
        int candidatesAfterPermission,
        int filteredByPermission,
        int schemaBytes,
        int schemaBudgetBytes,
        boolean overBudget,
        List<AgentRes> entries,
        List<String> events) {}
