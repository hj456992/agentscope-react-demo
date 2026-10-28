package dev.garden.demo.supervisor.pojo.param;

import dev.garden.demo.supervisor.pojo.dto.Action;

/** POST /api/supervisor/instructions 的唯一入参；runId 由请求体携带，路径不再重复。 */
public record InstructionParam(
        String runId, String instructionId, int expectedRevision, Action action, String message) {}
