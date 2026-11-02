package dev.garden.demo.supervisor.pojo.dto;

/**
 * 写入工具对模型和页面的统一回执。
 *
 * <p>{@code status} 使用 PENDING_APPROVAL、APPROVED、SUCCESS、FAILED、UNKNOWN 等
 * 工具级标签，不作为对外 HTTP 错误码；{@code response} 只含脱敏订单摘要。
 */
public record BookingOutcomeDTO(
        String status,
        String approvalId,
        String operationId,
        String response,
        String message) {}
