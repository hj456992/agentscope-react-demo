package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_prompt_releases 表的行映射：每个角色当前固定到一个提示词版本。 */
public final class PromptReleaseDO {
    public String role;
    public String promptId;
    public String version;
    public String actor;
    public long updatedAt;
}
