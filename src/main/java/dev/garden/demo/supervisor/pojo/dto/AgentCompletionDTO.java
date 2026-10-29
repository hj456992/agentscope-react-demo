package dev.garden.demo.supervisor.pojo.dto;

/** 已结束回合的待投递通知；独立于当前回合结果，避免后续任务覆盖。 */
public record AgentCompletionDTO(int generation, AgentMessageDTO message) {}
