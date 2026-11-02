package dev.garden.demo.supervisor.pojo.dto;

/**
 * 角色提示词版本。
 *
 * <p>P0 阶段来自本地静态目录；P3 接入提示词注册表后，这里返回任务创建时固定的
 * 注册表版本与内容哈希，字段形态保持不变。
 */
public record PromptVersionDTO(
        String role, String promptId, String version, String hash, String source) {}
