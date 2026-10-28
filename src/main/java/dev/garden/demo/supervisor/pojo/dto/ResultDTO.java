package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/** 一轮运行的完整结果，会整体持久化到任务记录。 */
public record ResultDTO(
        Status status,
        String originalQuery,
        String rewrittenQuery,
        Intent intent,
        String answer,
        List<String> agents,
        List<EvidenceDTO> evidence,
        List<String> events) {}
