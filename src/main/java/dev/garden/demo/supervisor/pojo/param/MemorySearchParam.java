package dev.garden.demo.supervisor.pojo.param;

/** 本地向量检索入参；limit 为空时返回前 5 条，最多 20 条。 */
public record MemorySearchParam(String query, Integer limit) {}
