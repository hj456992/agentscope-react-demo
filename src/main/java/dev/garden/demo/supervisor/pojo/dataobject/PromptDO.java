package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_prompts 表的行映射；content 只供进程内装配，不进入 HTTP 响应。 */
public final class PromptDO {
    public String promptId;
    public String version;
    public String role;
    public String owner;
    public String status;
    public String content;
    public String evalRecord;
    public String contentHash;
    public String createdBy;
    public long createdAt;
}
