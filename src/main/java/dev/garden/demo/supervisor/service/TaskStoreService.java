package dev.garden.demo.supervisor.service;

import dev.garden.demo.supervisor.AgentLifecycle;
import dev.garden.demo.supervisor.RunControl;
import dev.garden.demo.supervisor.config.SupervisorSettings;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.dto.Action;
import dev.garden.demo.supervisor.pojo.dto.AgentCompletionDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentInputDrainDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentMailboxDrainDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentMessageDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentOperationReceiptDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentSnapshotDTO;
import dev.garden.demo.supervisor.pojo.dto.DrainResultDTO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.Intent;
import dev.garden.demo.supervisor.pojo.dto.RequestDTO;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;
import dev.garden.demo.supervisor.pojo.dto.Status;
import dev.garden.demo.supervisor.pojo.dto.TurnDTO;
import dev.garden.demo.supervisor.pojo.param.InstructionParam;
import dev.garden.demo.supervisor.pojo.res.InstructionRes;
import dev.garden.demo.supervisor.pojo.res.RunRes;
import dev.garden.demo.supervisor.repo.TaskRepository;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 任务持久化的唯一事务边界：所有短事务在此声明，调度与模型执行仍留在 TaskService。
 *
 * <p>本类必须是非 final 的公有类，{@code @Transactional} 方法必须是 public，才能由 CGLIB 代理生效。
 */
public class TaskStoreService {
    private final TaskRepository repository;
    private final ConversationService conversations;
    private final SupervisorSettings settings;

    public TaskStoreService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings) {
        this.repository = repository;
        this.conversations = conversations;
        this.settings = settings;
    }

    @Transactional
    public synchronized TaskDO create(
            IdentityDTO identity, RequestDTO request, Runnable capacityCheck) {
        validateRequest(request);
        String requestId =
                request.requestId() == null || request.requestId().isBlank()
                        ? UUID.randomUUID().toString()
                        : request.requestId();
        String hash = requestHash(request);
        for (TaskDO old : repository.all()) {
            if (sameIdentity(old, identity) && old.requestId.equals(requestId)) {
                if (!old.requestHash.equals(hash)) {
                    throw conflict("请求编号的内容不同");
                }
                return old;
            }
        }
        for (TaskDO old : repository.all()) {
            if (sameIdentity(old, identity)
                    && old.sessionId.equals(request.sessionId())
                    && old.state.equals("RUNNING")) {
                throw conflict("会话已有活动任务：" + old.runId);
            }
        }
        capacityCheck.run();
        if (repository.all().size() >= settings.maxStoredTasks()) {
            throw busy();
        }
        reserveNewTask(identity);
        TaskDO fresh = newTask(identity, request, requestId, hash);
        repository.save(fresh);
        repository.currentSession(fresh);
        return fresh;
    }

    private TaskDO newTask(
            IdentityDTO identity, RequestDTO request, String requestId, String hash) {
        TaskDO task = new TaskDO();
        task.runId = UUID.randomUUID().toString();
        task.rootRunId = task.runId;
        task.identity = identity;
        task.tenantId = identity.tenantId();
        task.userId = identity.userId();
        task.sessionId = request.sessionId();
        task.requestId = requestId;
        task.requestHash = hash;
        task.createdAt = System.currentTimeMillis();
        task.deadline = task.createdAt + settings.timeout().toMillis();
        task.maxCalls = settings.maxModelCalls();
        task.userInputs.add(request.question());
        var history =
                conversations.enrichWithPermissions(
                        identity,
                        new RequestDTO(
                                request.sessionId(),
                                request.question(),
                                request.history(),
                                requestId));
        task.request = history.request();
        // HTTP 新请求仍拥有新的 runId/预算；同一会话取消后的模型上下文必须原样接续。
        String priorId = repository.currentSession(identity, request.sessionId());
        TaskDO prior = priorId == null ? null : repository.get(priorId);
        if (prior != null && prior.parentRunId == null && prior.state.equals("CANCELLED")) {
            String loop = prior.checkpoints.get("loop:v3.3");
            if (loop != null) {
                task.checkpoints.put("loop:v3.3", loop);
                task.checkpoints.put("agent:turn-start", "true");
                task.checkpoints.put("agent:interrupted", "The previous turn was interrupted.");
                task.checkpoints.put("agent:resume-user-input", request.question());
            }
        }
        if (!task.checkpoints.containsKey("loop:v3.3")
                && !history.requiredPermissions().isEmpty()) {
            task.checkpoints.put(
                    "agent:inherited-permissions",
                    repository.encode(history.requiredPermissions()));
        }
        return task;
    }

    @Transactional
    public synchronized InstructionRes applyInstruction(
            IdentityDTO identity, InstructionParam input, Runnable capacityCheck) {
        if (input == null
                || isBlank(input.runId())
                || isBlank(input.instructionId())
                || input.instructionId().length() > 200
                || input.action() == null
                || input.expectedRevision() < 1
                || (input.action() != Action.CANCEL && isBlank(input.message()))
                || (input.message() != null && input.message().length() > 2000)) {
            throw invalid();
        }
        String id = input.runId();
        String fingerprint = hash(repository.encode(input));
        TaskDO task = visible(identity, id);
        String[] prior = repository.instruction(id, input.instructionId());
        if (prior != null) {
            if (!prior[0].equals(fingerprint)) {
                throw conflict("指令编号的内容不同");
            }
            return repository.decode(prior[1], InstructionRes.class);
        }
        if (task.state.equals("CANCELLED") && input.action() == Action.CANCEL) {
            InstructionRes repeated = new InstructionRes(id, task.revision, task.state, "任务已停止");
            repository.instruction(id, input.instructionId(), fingerprint, repeated);
            return repeated;
        }
        if (task.revision != input.expectedRevision()) {
            throw conflict("任务版本已更新");
        }
        if (!task.state.equals("RUNNING") && !task.state.equals("NEEDS_INPUT")) {
            throw conflict("任务已结束");
        }
        if (task.deadline <= System.currentTimeMillis()) {
            throw conflict("任务已过截止时间");
        }
        if (!id.equals(repository.currentSession(identity, task.sessionId))) {
            throw conflict("会话已开始后续任务");
        }
        if (input.action() == Action.AUTO) {
            InstructionRes clarification =
                    new InstructionRes(id, task.revision, task.state, "请明确选择补充、修改、切换或停止；自动动作未执行。");
            repository.instruction(id, input.instructionId(), fingerprint, clarification);
            return clarification;
        }
        InstructionRes answer;
        if (input.action() == Action.CANCEL) {
            task.state = "CANCELLED";
            task.generation++;
            task.revision++;
            task.pending.clear();
            task.inflightInputs.clear();
            task.result = emptyResult(task, "CANCELLED", "任务已停止");
            repository.save(task);
            answer = new InstructionRes(id, task.revision, task.state, "任务已停止");
        } else if (input.action() == Action.SWITCH) {
            // 新任务必须先取得用户速率额度；原执行订阅释放后才启动替代任务。
            capacityCheck.run();
            if (repository.all().size() >= settings.maxStoredTasks()) {
                throw busy();
            }
            reserveNewTask(identity);
            RequestDTO replacement =
                    new RequestDTO(
                            task.sessionId,
                            input.message(),
                            List.of(),
                            UUID.randomUUID().toString());
            TaskDO next =
                    newTask(
                            identity,
                            replacement,
                            replacement.requestId(),
                            requestHash(replacement));
            task.state = "REPLACED";
            task.generation++;
            task.revision++;
            task.replacedBy = next.runId;
            task.result = emptyResult(task, "REPLACED", "已切换新任务");
            repository.save(task);
            repository.save(next);
            repository.currentSession(next);
            answer = new InstructionRes(next.runId, next.revision, next.state, "已切换新任务");
        } else {
            capacityCheck.run();
            if (task.pending.size() >= 32) {
                throw busy();
            }
            int pendingCharacters =
                    task.pending.stream().mapToInt(String::length).sum()
                            + task.pending.size()
                            + input.message().length();
            if (pendingCharacters > 2000) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            }
            task.checkpoints.remove("progress");
            task.pending.add(input.message());
            task.userInputs.add(input.message());
            task.revision++;
            task.state = "RUNNING";
            task.result = null;
            repository.save(task);
            answer = new InstructionRes(id, task.revision, task.state, "指令已排队，将在执行边界处理");
        }
        repository.instruction(id, input.instructionId(), fingerprint, answer);
        return answer;
    }

    @Transactional
    public synchronized boolean commitResult(
            String id,
            int generation,
            int consumedRevision,
            long mailboxRevision,
            ResultDTO result) {
        TaskDO current = repository.get(id);
        if (current == null
                || current.generation != generation
                || !current.state.equals("RUNNING")) {
            return true;
        }
        if (current.revision != consumedRevision || current.mailboxRevision != mailboxRevision) {
            return false;
        }
        current.result = result;
        current.state = result.status().name();
        repository.save(current);
        if (current.parentRunId == null) {
            saveConversation(current, result);
        } else {
            notifyParent(current);
        }
        return true;
    }

    private void saveConversation(TaskDO task, ResultDTO result) {
        if (task.userInputs.isEmpty()) {
            task.userInputs.add(task.request.question());
        }
        for (int index = task.recordedInputs; index < task.userInputs.size(); index++) {
            String answer = index == task.userInputs.size() - 1 ? result.answer() : "";
            ResultDTO turnResult =
                    new ResultDTO(
                            result.status(),
                            task.userInputs.get(index),
                            "",
                            result.intent(),
                            answer,
                            List.of(),
                            index == task.userInputs.size() - 1 ? result.evidence() : List.of(),
                            List.of());
            conversations.complete(
                    task.identity,
                    new RequestDTO(
                            task.sessionId, task.userInputs.get(index), List.of(), task.requestId),
                    turnResult,
                    task.runId + ":input:" + index,
                    index == task.userInputs.size() - 1
                            ? AgentLifecycle.requiredPermissions(task.checkpoints, result)
                            : java.util.Set.of());
        }
        task.recordedInputs = task.userInputs.size();
        repository.save(task);
    }

    @Transactional
    public synchronized void fail(String id, int generation, Throwable error) {
        TaskDO task = repository.get(id);
        if (task == null || !task.state.equals("RUNNING") || task.generation != generation) {
            return;
        }
        String status =
                error instanceof RunControl.BudgetExceeded
                        ? "BUDGET_EXHAUSTED"
                        : error instanceof RunControl.ContextLimit
                                ? "CONTEXT_LIMIT"
                                : error instanceof TimeoutException
                                                || error instanceof RunControl.DeadlineExceeded
                                        ? "TIMEOUT"
                                        : error instanceof RunControl.Cancelled
                                                ? "CANCELLED"
                                                : "FAILED";
        task.state = status;
        task.result = emptyResult(task, status, "任务未能完成，请查看状态后重试。");
        repository.save(task);
        notifyParent(task);
    }

    @Transactional
    public synchronized boolean expire(String id) {
        TaskDO current = repository.get(id);
        if (current == null
                || (!current.state.equals("RUNNING") && !current.state.equals("NEEDS_INPUT"))
                || current.deadline > System.currentTimeMillis()) {
            return false;
        }
        current.state = "TIMEOUT";
        current.generation++;
        current.result = emptyResult(current, "TIMEOUT", "任务已超过截止时间");
        repository.save(current);
        notifyParent(current);
        return true;
    }

    @Transactional
    public void cleanup(long oldest) {
        repository.cleanup(oldest);
    }

    @Transactional
    public void cleanupConversations() {
        conversations.cleanup();
    }

    @Transactional(readOnly = true)
    public TaskDO get(String id) {
        return repository.get(id);
    }

    @Transactional(readOnly = true)
    public List<TaskDO> all() {
        return repository.all();
    }

    @Transactional(readOnly = true)
    public TaskDO visible(IdentityDTO identity, String id) {
        TaskDO task = repository.get(id);
        if (task == null || !sameIdentity(task, identity)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return task;
    }

    @Transactional(readOnly = true)
    public RunRes view(IdentityDTO identity, TaskDO task) {
        ResultDTO result =
                task.result == null ? emptyResult(task, task.state, "任务执行中") : task.result;
        for (String permission : AgentLifecycle.requiredPermissions(task.checkpoints, result)) {
            if (!identity.allows(permission)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
        }
        return new RunRes(
                result,
                task.runId,
                task.revision,
                task.requestId,
                task.state,
                task.calls,
                task.deadline,
                task.replacedBy);
    }

    /** 读取执行中的任务；调用方须先完成身份解析与撤销检查。 */
    @Transactional(readOnly = true)
    public TaskDO active(String id, int generation) {
        TaskDO task = repository.get(id);
        if (task == null || task.generation != generation || !task.state.equals("RUNNING")) {
            throw new RunControl.Cancelled("任务已取消或替换");
        }
        if (task.deadline <= System.currentTimeMillis()) {
            throw new RunControl.DeadlineExceeded();
        }
        return task;
    }

    @Transactional
    public synchronized void reserveModel(String id, int generation, int estimatedTokens) {
        TaskDO task = active(id, generation);
        reserveTreeModel(task, estimatedTokens);
        repository.save(task);
    }

    @Transactional
    public synchronized void reserveModelStep(
            String id, int generation, String stepKey, int estimatedTokens, int maxAttempts) {
        TaskDO task = active(id, generation);
        int attempts = Integer.parseInt(task.checkpoints.getOrDefault(stepKey, "0"));
        if (attempts >= maxAttempts) {
            throw new RunControl.BudgetExceeded("模型步骤尝试预算已耗尽");
        }
        reserveTreeModel(task, estimatedTokens);
        task.checkpoints.put(stepKey, Integer.toString(attempts + 1));
        repository.save(task);
    }

    /** 树预算读取不校验父回合是否活动；子回合必须先通过自己的 active 校验。 */
    private void reserveTreeModel(TaskDO task, int estimatedTokens) {
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("Token 预留不能为负");
        }
        TaskDO root = rootId(task).equals(task.runId) ? task : repository.get(rootId(task));
        if (root == null) {
            throw new RunControl.Cancelled("任务树已不存在");
        }
        if (root.calls >= root.maxCalls) {
            throw new RunControl.BudgetExceeded("模型调用预算已耗尽");
        }
        repository.reserve(
                "models:global",
                1,
                estimatedTokens,
                settings.modelCallsPerMinute(),
                settings.modelTokensPerMinute());
        root.calls++;
        if (root != task) {
            task.calls++;
            repository.save(root);
        }
    }

    @Transactional(readOnly = true)
    public String checkpoint(String id, int generation, String key) {
        return active(id, generation).checkpoints.get(key);
    }

    @Transactional
    public synchronized void checkpoint(
            String id, int generation, int consumedRevision, String key, String value) {
        TaskDO task = active(id, generation);
        if (key.equals("progress") && task.revision != consumedRevision) {
            return;
        }
        if (key.length() > 1024 || (value != null && value.length() > 131072)) {
            throw new RunControl.ContextLimit("检查点内容超过存储上限");
        }
        if (!task.checkpoints.containsKey(key) && task.checkpoints.size() >= 512) {
            throw new RunControl.ContextLimit("检查点数量超过上限");
        }
        if (value == null) {
            task.checkpoints.remove(key);
        } else {
            task.checkpoints.put(key, value);
        }
        repository.save(task);
    }

    @Transactional
    public synchronized DrainResultDTO drainInput(String id, int generation) {
        TaskDO task = active(id, generation);
        if (task.pending.isEmpty()) {
            return null;
        }
        String text = String.join("\n", task.pending);
        task.pending.clear();
        task.consumedRevision = task.revision;
        List<TurnDTO> history = new ArrayList<>(task.request.history());
        history.add(new TurnDTO(task.request.question(), "当前任务此前的用户要求"));
        task.request = new RequestDTO(task.sessionId, text, history, task.requestId);
        repository.save(task);
        return new DrainResultDTO(text, task.consumedRevision);
    }

    @Transactional
    public synchronized void event(String id, int generation, String event) {
        TaskDO task = active(id, generation);
        if (task.events.size() < 200) {
            task.events.add(event.length() > 512 ? event.substring(0, 512) : event);
        }
        repository.save(task);
    }

    @Transactional(readOnly = true)
    public int modelCalls(String id) {
        TaskDO task = repository.get(id);
        if (task == null) {
            return 0;
        }
        TaskDO root = repository.get(rootId(task));
        return root == null ? task.calls : root.calls;
    }

    /** 创建未受理的子会话；调用者必须随后接受或清理，不能把它交给恢复执行器。 */
    @Transactional
    public synchronized TaskDO prepareChild(
            String parentId,
            int generation,
            String taskName,
            String message,
            String role,
            String forkTurns,
            Map<String, String> inherited) {
        TaskDO parent = active(parentId, generation);
        AgentLifecycle.validateMessage(message);
        String path = AgentLifecycle.childPath(parent.agentPath, taskName);
        if (repository.all().stream()
                .anyMatch(
                        t ->
                                rootId(t).equals(rootId(parent))
                                        && t.agentPath.equals(path)
                                        && !t.state.equals("CLOSED"))) {
            throw new IllegalArgumentException("任务名已存在：" + path);
        }
        if (repository.all().size() >= settings.maxStoredTasks()) {
            throw busy();
        }
        TaskDO child = new TaskDO();
        child.runId = UUID.randomUUID().toString();
        child.rootRunId = rootId(parent);
        child.parentRunId = parent.runId;
        child.agentPath = path;
        child.agentRole = AgentLifecycle.role(role);
        child.identity = parent.identity;
        child.tenantId = parent.tenantId;
        child.userId = parent.userId;
        child.sessionId = child.runId;
        child.requestId = child.runId;
        child.requestHash = hash(message);
        child.request = new RequestDTO(child.sessionId, message, List.of(), child.requestId);
        child.createdAt = System.currentTimeMillis();
        child.deadline = parent.deadline;
        child.maxCalls = parent.maxCalls;
        child.accepted = false;
        child.state = "PROVISIONAL";
        if (!"none".equalsIgnoreCase(forkTurns)) {
            child.checkpoints.putAll(inherited);
        }
        repository.save(child);
        return child;
    }

    @Transactional
    public synchronized TaskDO acceptChild(String id) {
        TaskDO child = repository.get(id);
        if (child == null || !child.state.equals("PROVISIONAL")) {
            throw new IllegalStateException("子会话不处于待受理状态");
        }
        child.accepted = true;
        child.state = "RUNNING";
        repository.save(child);
        return child;
    }

    @Transactional
    public synchronized void discardChild(String id) {
        TaskDO child = repository.get(id);
        if (child != null && (!child.accepted || child.generation == 1)) {
            if (child.acceptanceReceiptKey != null) {
                TaskDO parent = repository.get(child.parentRunId);
                if (parent != null) {
                    parent.collaborationReceipts.remove(child.acceptanceReceiptKey);
                    repository.save(parent);
                }
            }
            child.accepted = false;
            child.state = "CLOSED";
            child.checkpoints.clear();
            repository.save(child);
        }
    }

    public String operationFingerprint(Object... arguments) {
        return hash(repository.encode(java.util.Arrays.asList(arguments)));
    }

    private String receiptKey(TaskDO caller, String operationId) {
        return caller.agentTurnId + ":" + operationId;
    }

    @Transactional(readOnly = true)
    public AgentOperationReceiptDTO operationReceipt(
            String callerId, String operationId, String fingerprint) {
        if (operationId == null) {
            return null;
        }
        TaskDO caller = repository.get(callerId);
        String saved = caller.collaborationReceipts.get(receiptKey(caller, operationId));
        if (saved == null) {
            if (caller.collaborationReceipts.size() >= 512) {
                throw new RunControl.ContextLimit("协作操作回执数量超过上限");
            }
            return null;
        }
        AgentOperationReceiptDTO receipt = repository.decode(saved, AgentOperationReceiptDTO.class);
        if (!receipt.fingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException("相同工具调用编号对应不同协作请求");
        }
        return receipt;
    }

    private void saveReceipt(
            String callerId, String operationId, String fingerprint, AgentSnapshotDTO result) {
        if (operationId != null) {
            TaskDO caller = repository.get(callerId);
            if (caller.collaborationReceipts.size() >= 512) {
                throw new RunControl.ContextLimit("协作操作回执数量超过上限");
            }
            caller.collaborationReceipts.put(
                    receiptKey(caller, operationId),
                    repository.encode(new AgentOperationReceiptDTO(fingerprint, result)));
            repository.save(caller);
        }
    }

    /** 子受理与调用方回执同一事务提交，恢复未写入模型上下文的工具结果时不再次派发。 */
    @Transactional
    public synchronized TaskDO acceptChildWithReceipt(
            String id, String callerId, String operationId, String fingerprint) {
        TaskDO child = acceptChild(id);
        if (operationId != null) {
            TaskDO caller = repository.get(callerId);
            child.acceptanceReceiptKey = receiptKey(caller, operationId);
            repository.save(child);
        }
        saveReceipt(callerId, operationId, fingerprint, agentSnapshot(child));
        return child;
    }

    @Transactional
    public synchronized TaskDO sendAgentWithReceipt(
            String callerId,
            String operationId,
            String fingerprint,
            String target,
            String message,
            boolean trigger) {
        AgentOperationReceiptDTO prior = operationReceipt(callerId, operationId, fingerprint);
        if (prior != null) {
            return repository.get(prior.result().agentId());
        }
        TaskDO receiver = sendAgent(callerId, target, message, trigger);
        saveReceipt(callerId, operationId, fingerprint, agentSnapshot(receiver));
        return receiver;
    }

    @Transactional
    public synchronized AgentSnapshotDTO interruptAgentWithReceipt(
            String callerId, String operationId, String fingerprint, String target) {
        AgentOperationReceiptDTO prior = operationReceipt(callerId, operationId, fingerprint);
        if (prior != null) {
            return prior.result();
        }
        AgentSnapshotDTO previous = interruptAgent(callerId, target);
        saveReceipt(callerId, operationId, fingerprint, previous);
        return previous;
    }

    /** 恢复或启动时建立新的执行代际，防止旧订阅在重启交接后提交。 */
    @Transactional
    public synchronized TaskDO claimExecution(String id) {
        TaskDO task = repository.get(id);
        if (task == null || !task.accepted || !task.state.equals("RUNNING")) {
            throw new RunControl.Cancelled("任务不再可执行");
        }
        task.generation++;
        repository.save(task);
        return task;
    }

    @Transactional(readOnly = true)
    public TaskDO agentTarget(String callerId, String reference) {
        TaskDO caller = repository.get(callerId);
        if (caller == null) {
            throw new IllegalArgumentException("调用会话不存在");
        }
        TaskDO byId = repository.get(reference);
        if (byId != null && !rootId(byId).equals(rootId(caller))) {
            throw new IllegalArgumentException("目标不在当前任务树中");
        }
        String path =
                byId == null ? AgentLifecycle.resolve(caller.agentPath, reference) : byId.agentPath;
        return repository.all().stream()
                .filter(
                        t ->
                                t.accepted
                                        && !t.state.equals("CLOSED")
                                        && rootId(t).equals(rootId(caller)))
                .filter(t -> t.agentPath.equals(path))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目标会话不存在"));
    }

    @Transactional
    public synchronized TaskDO sendAgent(
            String callerId, String target, String message, boolean trigger) {
        AgentLifecycle.validateMessage(message);
        TaskDO caller = repository.get(callerId);
        TaskDO receiver = agentTarget(callerId, target);
        if (trigger && receiver.parentRunId == null) {
            throw new IllegalArgumentException("追加任务不能发给根 Agent");
        }
        if (receiver.mailbox.size() + receiver.inflightMailbox.size() >= 512) {
            throw new RunControl.ContextLimit("消息箱已满");
        }
        receiver.mailbox.add(
                new AgentMessageDTO(
                        caller.agentPath,
                        receiver.agentPath,
                        trigger ? "FOLLOWUP" : "MESSAGE",
                        message,
                        null,
                        AgentLifecycle.requiredPermissions(caller.checkpoints, caller.result)));
        receiver.mailboxRevision++;
        if (!receiver.state.equals("RUNNING") && trigger) {
            receiver.agentTurnId = UUID.randomUUID().toString();
            receiver.state = "RUNNING";
            receiver.result = null;
            receiver.request =
                    new RequestDTO(receiver.sessionId, message, List.of(), receiver.requestId);
            receiver.checkpoints.put("agent:turn-start", "true");
        }
        repository.save(receiver);
        return receiver;
    }

    @Transactional
    public synchronized AgentSnapshotDTO interruptAgent(String callerId, String target) {
        TaskDO receiver = agentTarget(callerId, target);
        if (receiver.parentRunId == null || receiver.runId.equals(callerId)) {
            throw new IllegalArgumentException("不能中断根 Agent 或自己");
        }
        AgentSnapshotDTO snapshot = agentSnapshot(receiver);
        if (receiver.state.equals("RUNNING")) {
            receiver.state = "CANCELLED";
            receiver.generation++;
            receiver.checkpoints.put("agent:interrupted", "The previous turn was interrupted.");
            repository.save(receiver);
        }
        return snapshot;
    }

    @Transactional(readOnly = true)
    public List<AgentSnapshotDTO> listAgents(String callerId, String prefix) {
        TaskDO caller = repository.get(callerId);
        if (caller == null) {
            return List.of();
        }
        String path = prefix == null ? "/root" : AgentLifecycle.resolve(caller.agentPath, prefix);
        return repository.all().stream()
                .filter(
                        t ->
                                t.accepted
                                        && !t.state.equals("CLOSED")
                                        && rootId(t).equals(rootId(caller)))
                .filter(t -> t.agentPath.equals(path) || t.agentPath.startsWith(path + "/"))
                .sorted(Comparator.comparing(t -> t.agentPath))
                .map(this::agentSnapshot)
                .toList();
    }

    public AgentSnapshotDTO agentSnapshot(TaskDO task) {
        return new AgentSnapshotDTO(
                task.runId,
                task.agentPath,
                task.agentRole,
                AgentLifecycle.status(task.state),
                task.result == null ? null : task.result.answer(),
                task.result,
                AgentLifecycle.requiredPermissions(task.checkpoints, task.result));
    }

    @Transactional
    public synchronized AgentMailboxDrainDTO deliverMailbox(String id, int generation) {
        TaskDO task = repository.get(id);
        if (task == null) {
            return new AgentMailboxDrainDTO(List.of(), 0, 0);
        }
        if (task.state.equals("RUNNING") && task.generation != generation) {
            throw new RunControl.Cancelled("旧回合不能消费消息");
        }
        if (task.inflightMailbox.isEmpty() && !task.mailbox.isEmpty()) {
            task.inflightMailbox.addAll(task.mailbox);
            task.mailbox.clear();
            task.mailboxDeliveryId++;
        }
        if (task.mailbox.isEmpty()) {
            task.consumedMailboxRevision = task.mailboxRevision;
        }
        repository.save(task);
        return new AgentMailboxDrainDTO(
                List.copyOf(task.inflightMailbox),
                task.consumedMailboxRevision,
                task.inflightMailbox.isEmpty() ? 0 : task.mailboxDeliveryId);
    }

    @Transactional
    public synchronized AgentInputDrainDTO drainInputs(String id, int generation) {
        TaskDO task = active(id, generation);
        if (task.inflightInputs.isEmpty() && !task.pending.isEmpty()) {
            List<String> inputs = List.copyOf(task.pending);
            List<TurnDTO> history = new ArrayList<>(task.request.history());
            history.add(new TurnDTO(task.request.question(), ""));
            for (int i = 0; i < inputs.size() - 1; i++) {
                history.add(new TurnDTO(inputs.get(i), ""));
            }
            task.request =
                    new RequestDTO(
                            task.sessionId, inputs.get(inputs.size() - 1), history, task.requestId);
            task.inflightInputs.addAll(inputs);
            task.pending.clear();
            task.inputDeliveryId++;
        }
        if (task.pending.isEmpty()) {
            task.consumedRevision = task.revision;
        }
        repository.save(task);
        return new AgentInputDrainDTO(
                List.copyOf(task.inflightInputs),
                task.consumedRevision,
                task.inflightInputs.isEmpty() ? 0 : task.inputDeliveryId);
    }

    /** 模型上下文保存与消息确认在同一事务完成；未读过批次的构造阶段不会误确认。 */
    @Transactional
    public synchronized void checkpointWithDeliveryAck(
            String id,
            int generation,
            int revision,
            String key,
            String value,
            long inputAck,
            long mailboxAck) {
        checkpoint(id, generation, revision, key, value);
        if (!key.equals("loop:v3.3")) {
            return;
        }
        TaskDO task = active(id, generation);
        if (inputAck != 0 && inputAck == task.inputDeliveryId) {
            task.inflightInputs.clear();
        }
        if (mailboxAck != 0 && mailboxAck == task.mailboxDeliveryId) {
            task.inflightMailbox.clear();
        }
        repository.save(task);
    }

    /** 每轮完成先独立入待投队列，再按序投递；新回合不会覆盖尚未投递的旧结果。 */
    private void notifyParent(TaskDO child) {
        if (child.parentRunId == null) {
            return;
        }
        if (child.result != null
                && !child.state.equals("RUNNING")
                && !AgentLifecycle.status(child.state).equals("interrupted")
                && child.generation
                        > Math.max(child.completionQueuedGeneration, child.notifiedGeneration)) {
            TaskDO parent = repository.get(child.parentRunId);
            String recipient = parent == null ? "" : parent.agentPath;
            child.completionOutbox.add(
                    new AgentCompletionDTO(
                            child.generation,
                            AgentLifecycle.result(
                                    child.agentPath, recipient, child.result, child.checkpoints)));
            child.completionQueuedGeneration = child.generation;
        }
        if (child.completionOutbox.isEmpty()) {
            return;
        }
        TaskDO parent = repository.get(child.parentRunId);
        while (!child.completionOutbox.isEmpty()) {
            if (parent != null && parent.mailbox.size() + parent.inflightMailbox.size() >= 512) {
                String event = "完成通知等待父会话邮箱空间";
                if (!child.events.contains(event) && child.events.size() < 200) {
                    child.events.add(event);
                }
                break;
            }
            AgentCompletionDTO completion = child.completionOutbox.remove(0);
            if (parent != null) {
                parent.mailbox.add(completion.message());
                parent.mailboxRevision++;
            }
            child.notifiedGeneration = completion.generation();
        }
        if (parent != null) {
            repository.save(parent);
        }
        repository.save(child);
    }

    /** 正在执行新回合的子会话也可能有旧待投通知；只投直接父，不提升给祖先。 */
    @Transactional
    public synchronized void retryPendingNotifications() {
        for (TaskDO listed : repository.all()) {
            // 前面的后代投递可能已修改此父聚合；不可把扫描开始时的旧 mailbox 写回。
            TaskDO task = repository.get(listed.runId);
            if (task != null && task.accepted && task.parentRunId != null) {
                notifyParent(task);
            }
        }
    }

    /** 本地调用次数额度已满且本子树无活动后代时，等待不能再产生模型结果。 */
    @Transactional(readOnly = true)
    public boolean exhaustedWaitBudget(String id) {
        TaskDO caller = repository.get(id);
        TaskDO root = caller == null ? null : repository.get(rootId(caller));
        if (root == null) {
            return false;
        }
        List<TaskDO> descendants =
                repository.all().stream()
                        .filter(
                                t ->
                                        t.accepted
                                                && rootId(t).equals(rootId(caller))
                                                && t.agentPath.startsWith(caller.agentPath + "/"))
                        .toList();
        boolean exhausted =
                root.calls >= root.maxCalls
                        || descendants.stream().anyMatch(t -> t.state.equals("BUDGET_EXHAUSTED"));
        return exhausted && descendants.stream().noneMatch(t -> t.state.equals("RUNNING"));
    }

    private static String rootId(TaskDO task) {
        return task.rootRunId == null ? task.runId : task.rootRunId;
    }

    private ResultDTO emptyResult(TaskDO task, String status, String answer) {
        String saved = task.checkpoints.get("progress");
        if (saved != null) {
            ResultDTO progress = repository.decode(saved, ResultDTO.class);
            return new ResultDTO(
                    Status.valueOf(status),
                    progress.originalQuery(),
                    progress.rewrittenQuery(),
                    progress.intent(),
                    answer,
                    progress.agents(),
                    progress.evidence(),
                    List.copyOf(task.events));
        }
        return new ResultDTO(
                Status.valueOf(status),
                task.request.question(),
                "",
                Intent.UNKNOWN,
                answer,
                List.of(),
                List.of(),
                List.copyOf(task.events));
    }

    private void reserveNewTask(IdentityDTO identity) {
        try {
            repository.reserve(
                    "tasks:" + identity.tenantId() + ":" + identity.userId(),
                    1,
                    0,
                    settings.tasksPerUserMinute(),
                    Long.MAX_VALUE);
        } catch (RunControl.BudgetExceeded error) {
            throw busy();
        }
    }

    private String requestHash(RequestDTO request) {
        return hash(
                repository.encode(
                        new RequestDTO(
                                request.sessionId(), request.question(), request.history(), null)));
    }

    private static boolean sameIdentity(TaskDO task, IdentityDTO identity) {
        return task.tenantId.equals(identity.tenantId()) && task.userId.equals(identity.userId());
    }

    public static void validateRequest(RequestDTO request) {
        if (request == null
                || isBlank(request.sessionId())
                || request.sessionId().length() > 200
                || isBlank(request.question())
                || request.question().length() > 2000
                || (request.requestId() != null && request.requestId().length() > 200)) {
            throw invalid();
        }
        for (TurnDTO turn : request.history())
            if (turn == null || isBlank(turn.question()) || turn.answer() == null) {
                throw invalid();
            }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
