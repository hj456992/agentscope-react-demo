package dev.garden.demo.supervisor.pojo.dto;

/**
 * 内容块信任级别证据。
 *
 * <p>{@code level} 只表示内容来源，不表示授权。工具结果、子级消息和检索资料都按数据处理，
 * 不能提升权限或替代用户确认。
 */
public record TrustEvidenceDTO(String level, String source, String handling, int count) {}
