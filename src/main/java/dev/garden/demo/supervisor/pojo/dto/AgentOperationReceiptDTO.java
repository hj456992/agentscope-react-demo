package dev.garden.demo.supervisor.pojo.dto;

/** 已受理协作操作的请求指纹和原始回执。 */
public record AgentOperationReceiptDTO(String fingerprint, AgentSnapshotDTO result) {}
