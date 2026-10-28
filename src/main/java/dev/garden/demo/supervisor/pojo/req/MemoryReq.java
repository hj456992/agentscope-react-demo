package dev.garden.demo.supervisor.pojo.req;

import java.time.Instant;

/** 写入显式偏好的请求体。 */
public record MemoryReq(String value, int expectedVersion, Instant expiresAt) {}
