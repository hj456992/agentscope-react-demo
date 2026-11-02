package dev.garden.demo.supervisor.pojo.dto;

/**
 * 模型或工具的执行时段。
 *
 * <p>时间来自模型会话快照的消息时间戳与供应商返回的耗时；无法解析时保持
 * {@code null}，不补造时间。脱敏后的追踪编号可直接用于页面父子关联。
 */
public record SpanDTO(
        String traceId,
        String spanId,
        String parentSpanId,
        String kind,
        String runId,
        String agentPath,
        String name,
        String status,
        Long startedAt,
        Long endedAt,
        Long durationMs,
        String errorCode) {}
