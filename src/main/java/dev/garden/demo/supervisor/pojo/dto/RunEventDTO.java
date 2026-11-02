package dev.garden.demo.supervisor.pojo.dto;

/**
 * 带时间的执行事件证据。
 *
 * <p>事件正文沿用既有持久化内容；时间为服务端记录该事件的毫秒时间戳。旧任务没有时间
 * 数组时返回 {@code null}，页面不补造时间。
 */
public record RunEventDTO(int sequence, long at, String text) {}
