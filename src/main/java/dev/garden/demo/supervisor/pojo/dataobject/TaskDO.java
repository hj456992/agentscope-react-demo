package dev.garden.demo.supervisor.pojo.dataobject;

import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentMessageDTO;
import dev.garden.demo.supervisor.pojo.dto.RequestDTO;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** supervisor_tasks.payload 的聚合对象；整条任务以 JSON 持久化。 */
public final class TaskDO {
    public String runId, tenantId, userId, sessionId, requestId, requestHash;
    public RequestDTO request;
    public IdentityDTO identity;
    public String state = "RUNNING";
    public int revision = 1, consumedRevision = 1, generation = 1, calls;
    public int maxCalls;
    public long createdAt, deadline;
    public ResultDTO result;
    public List<String> pending = new ArrayList<>();
    public List<String> userInputs = new ArrayList<>();
    public int recordedInputs;
    public Map<String, String> checkpoints = new LinkedHashMap<>();
    public List<String> events = new ArrayList<>();
    public String replacedBy;
    /** 子会话关系与状态独立于父轮次；rootRunId 只指共享预算所有者。 */
    public String rootRunId, parentRunId;
    public String agentPath = "/root", agentRole = "supervisor";
    public boolean accepted = true;
    public int notifiedGeneration;
    public List<AgentMessageDTO> mailbox = new ArrayList<>();
    /** 未和模型上下文快照一起确认的投递批次，重启后必须重放。 */
    public List<String> inflightInputs = new ArrayList<>();
    public List<AgentMessageDTO> inflightMailbox = new ArrayList<>();
    public long inputDeliveryId, mailboxDeliveryId;
    public long mailboxRevision, consumedMailboxRevision;
}
