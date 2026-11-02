package dev.garden.demo.supervisor.pojo.param;

import dev.garden.demo.supervisor.pojo.dto.Action;

/**
 * POST /api/supervisor/instructions 的唯一入参；runId 由请求体携带，路径不再重复。
 *
 * <p>{@code action} 可省略，省略时按 {@link Action#MESSAGE} 处理，即把 {@code message}
 * 当作一条新的用户输入交给运行中的任务。只有 {@link Action#CANCEL} 不需要
 * {@code expectedRevision}：停止是兜底操作，不能因为页面版本号过期而失败。
 */
public record InstructionParam(
        String runId,
        String instructionId,
        Integer expectedRevision,
        Action action,
        String message) {}
