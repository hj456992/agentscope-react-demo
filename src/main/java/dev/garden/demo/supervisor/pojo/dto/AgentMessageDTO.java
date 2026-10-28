package dev.garden.demo.supervisor.pojo.dto;

/** V2 子会话协议传输对象。 */
public record AgentMessageDTO(String sender, String recipient, String kind, String message, ResultDTO result) {}
