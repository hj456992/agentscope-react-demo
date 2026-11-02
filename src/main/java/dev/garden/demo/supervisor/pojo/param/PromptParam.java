package dev.garden.demo.supervisor.pojo.param;

/** POST /prompts 的唯一入参：登记一个新提示词版本。 */
public record PromptParam(
        String promptId,
        String version,
        String role,
        String owner,
        String content,
        String evalRecord,
        String status,
        String actor) {}
