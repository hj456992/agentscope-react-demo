package dev.garden.demo.supervisor.pojo.res;

/** 提示词版本元数据；刻意不返回 content，提示词正文不进入页面记录或导出。 */
public record PromptRes(
        String promptId,
        String version,
        String role,
        String owner,
        String status,
        String hash,
        String evalRecord,
        String createdBy,
        long createdAt) {}
