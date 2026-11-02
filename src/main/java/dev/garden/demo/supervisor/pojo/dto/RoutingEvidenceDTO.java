package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/**
 * 路由证据。
 *
 * <p>{@code decision} 和 {@code reason} 来自已持久化的计划或当前单 Agent 根运行；
 * 没有计划时明确返回 {@code SINGLE_AGENT}，不伪造多 Agent 选择。
 */
public record RoutingEvidenceDTO(
        String decision,
        String reason,
        String confidence,
        List<RoutingCandidateDTO> candidates) {}
