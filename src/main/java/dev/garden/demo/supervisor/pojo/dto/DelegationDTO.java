package dev.garden.demo.supervisor.pojo.dto;

import java.util.Set;

/**
 * 委派回执证据。
 *
 * <p>来自父任务的 {@code collaborationReceipts}；键由逻辑轮次编号和操作号组成，
 * 用于证明“同一操作号只派发一次”以及子运行编号。
 */
public record DelegationDTO(
        String parentRunId,
        String parentAgentPath,
        String operationId,
        String receiptKey,
        String fingerprint,
        String childRunId,
        String childAgentPath,
        String childRole,
        String childStatus,
        Set<String> requiredPermissions) {}
