package dev.garden.demo.supervisor.pojo.res;

import java.time.Instant;

/** 显式偏好响应。 */
public record MemoryRes(
        String key, String value, int version, String source, Instant expiresAt) {}
