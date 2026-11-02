package dev.garden.demo.supervisor.pojo.dto;

/**
 * 一条指令应用证据。
 *
 * <p>只记录动作、修订版本、终态和指纹，不保存指令正文或认证信息。相同指令编号重放时
 * 直接复用首次回执，不新增第二条证据。
 */
public record InstructionEvidenceDTO(
        String instructionId,
        String action,
        int expectedRevision,
        int resultRevision,
        String resultState,
        String resultRunId,
        String result,
        String fingerprint,
        long createdAt) {}
