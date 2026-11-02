package dev.garden.demo.supervisor.pojo.dto;

/**
 * 实际注入模型的提示词定义。
 *
 * <p>包含注册表版本、内容哈希和内容正文；内容正文只在进程内使用，HTTP 读取接口
 * 不返回正文，避免提示词进入页面记录和导出。
 */
public record PromptDefinitionDTO(
        String role,
        String promptId,
        String version,
        String hash,
        String content,
        String source,
        String owner,
        String status,
        String evalRecord) {}
