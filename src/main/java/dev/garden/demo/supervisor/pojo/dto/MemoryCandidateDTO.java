package dev.garden.demo.supervisor.pojo.dto;

import java.time.Instant;

/** 记忆候选：必须经人工批准后才写入长期偏好。 */
public record MemoryCandidateDTO(
        String candidateId,
        String key,
        String value,
        String source,
        String status,
        int expectedVersion,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt,
        String decidedBy,
        String reason,
        Integer appliedVersion) {}
