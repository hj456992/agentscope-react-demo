package dev.garden.demo.supervisor.pojo.param;

/** POST /prompts/releases 的唯一入参：把角色绑定到指定提示词版本。 */
public record PromptReleaseParam(String role, String promptId, String version, String actor) {}
