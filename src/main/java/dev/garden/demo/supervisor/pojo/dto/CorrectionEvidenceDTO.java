package dev.garden.demo.supervisor.pojo.dto;

/** 来自持久化纠错检查点的单类纠错计数。 */
public record CorrectionEvidenceDTO(String type, String tool, int count) {}
