package dev.garden.demo.supervisor.pojo.dto;

/** 压缩前原始记录的存储证据；只返回代数、字符数与是否存在，不返回原文。 */
public record RecallDTO(
        String runId, String agentPath, int generation, long storedChars, boolean available) {}
