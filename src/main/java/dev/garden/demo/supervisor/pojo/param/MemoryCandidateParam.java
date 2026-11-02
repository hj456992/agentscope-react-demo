package dev.garden.demo.supervisor.pojo.param;

import java.time.Instant;

/**
 * 提交记忆候选；聊天不会自动创建长期偏好，只有候选经批准后才写入。
 *
 * <p>{@code expectedVersion} 防止候选审核期间覆盖已更新的偏好；{@code expiresAt} 为空时
 * 使用实例的偏好保留期。
 */
public record MemoryCandidateParam(
        String key, String value, String source, int expectedVersion, Instant expiresAt) {}
