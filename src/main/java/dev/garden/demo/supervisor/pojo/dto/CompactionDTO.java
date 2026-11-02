package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/**
 * 一次上下文压缩的可核对摘要。
 *
 * <p>只返回摘要文本和相关事件，不返回被替换的完整对话；摘要超过 2000 字符时截断。
 */
public record CompactionDTO(
        String runId,
        String agentPath,
        int generation,
        long summaryBytes,
        String summary,
        List<String> events) {}
