package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_conversations 表的行映射。 */
public final class ConversationDO {
    public String tenantId;
    public String userId;
    public String sessionId;
    public String runId;
    public String question;
    public String answer;
    public String requiredTools;
    public long createdAt;
}
