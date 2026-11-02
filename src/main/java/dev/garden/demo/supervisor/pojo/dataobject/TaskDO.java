package dev.garden.demo.supervisor.pojo.dataobject;

import dev.garden.demo.supervisor.pojo.dto.AgentCompletionDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentMessageDTO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
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
    /** 首次事件和终态时间由服务端写入；旧任务缺少时保持 0，页面不补造。 */
    public long firstEventAt, completedAt, recoveredAt;
    /** 本 Agent 自身模型调用数；calls 在根任务上表示整棵树。 */
    public int ownCalls;
    public ResultDTO result;
    public List<String> pending = new ArrayList<>();
    public List<String> userInputs = new ArrayList<>();
    public int recordedInputs;
    public Map<String, String> checkpoints = new LinkedHashMap<>();
    public List<String> events = new ArrayList<>();
    /** 与 events 一一对应的事件时间；旧任务允许为空或较短。 */
    public List<Long> eventTimes = new ArrayList<>();
    public String replacedBy;

    /** 受控验收触发标记；正常请求为空，页面据此区分受控分支。 */
    public String triggerMode, acceptanceHint;
    public List<dev.garden.demo.supervisor.pojo.dto.InstructionEvidenceDTO> instructionEvidence =
            new ArrayList<>();

    /** 子会话关系与状态独立于父轮次；rootRunId 只指共享预算所有者。 */
    public String rootRunId, parentRunId;

    public String agentPath = "/root", agentRole = "supervisor";
    /** 任务受理时固定的目录版本；旧任务缺字段时显示未登记。 */
    public String agentVersion;
    public boolean accepted = true;

    /** 已投递的最高完成代际；入队代际独立记录，等待期间允许继续新回合。 */
    public int notifiedGeneration, completionQueuedGeneration;

    public List<AgentCompletionDTO> completionOutbox = new ArrayList<>();
    public List<AgentMessageDTO> mailbox = new ArrayList<>();

    /** 未和模型上下文快照一起确认的投递批次，重启后必须重放。 */
    public List<String> inflightInputs = new ArrayList<>();

    public List<AgentMessageDTO> inflightMailbox = new ArrayList<>();
    public long inputDeliveryId, mailboxDeliveryId;
    public long mailboxRevision, consumedMailboxRevision;

    /** 逻辑轮次跨进程恢复保持不变；generation 仅隔离执行者。 */
    public String agentTurnId = java.util.UUID.randomUUID().toString();

    public Map<String, String> collaborationReceipts = new LinkedHashMap<>();
    public String acceptanceReceiptKey;
}
