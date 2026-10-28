package dev.garden.demo.supervisor.pojo.res;

import java.util.List;

/** 偏好分页响应；nextCursor 为空表示没有下一页。 */
public record MemoryPageRes(List<MemoryRes> items, String nextCursor) {}
