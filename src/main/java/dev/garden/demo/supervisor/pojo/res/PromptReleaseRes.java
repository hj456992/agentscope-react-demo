package dev.garden.demo.supervisor.pojo.res;

/** 角色当前绑定的提示词版本；任务创建后仍按任务记录的固定版本执行。 */
public record PromptReleaseRes(
        String role, String promptId, String version, String actor, long updatedAt) {}
