package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/** 邮箱投递批次及其上下文快照确认标识。 */
public record AgentMailboxDrainDTO(
        List<AgentMessageDTO> messages, long consumedMailboxRevision, long deliveryId) {}
