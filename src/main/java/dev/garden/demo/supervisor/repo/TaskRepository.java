package dev.garden.demo.supervisor.repo;

import dev.garden.demo.supervisor.RunControl;
import dev.garden.demo.supervisor.pojo.dataobject.InstructionDO;
import dev.garden.demo.supervisor.pojo.dataobject.QuotaWindowDO;
import dev.garden.demo.supervisor.pojo.dataobject.SessionDO;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.dataobject.TaskRowDO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.repo.mapper.TaskMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务持久化仓储。所有语句由 MyBatis 执行；跨语句原子性由 service 层的声明式事务提供。
 */
public final class TaskRepository {
    private final Database database;
    private final TaskMapper mapper;
    private final JsonCodec codec;

    /**
     * 唯一构造器：使用调用方提供的共享连接池和 JSON 编解码器。
     *
     * <p>仓储不拥有连接池，close 由 Database 的持有方负责；测试如需独立连接池，
     * 先在测试代码中打开 Database，再用它构造本仓储。
     */
    public TaskRepository(Database database, JsonCodec codec) {
        this.database = database;
        this.codec = codec;
        this.mapper = database.mapper(TaskMapper.class);
        mapper.createTasksTable();
        mapper.createInstructionsTable();
        mapper.createWindowsTable();
        mapper.createSessionsTable();
    }

    public Database database() {
        return database;
    }

    public JsonCodec codec() {
        return codec;
    }

    public TaskDO get(String id) {
        String payload = mapper.selectPayload(id);
        return payload == null ? null : decode(payload, TaskDO.class);
    }

    public List<TaskDO> all() {
        List<TaskDO> result = new ArrayList<>();
        for (String payload : mapper.selectAllPayloads()) result.add(decode(payload, TaskDO.class));
        return result;
    }

    public void save(TaskDO task) {
        TaskRowDO row = new TaskRowDO();
        row.runId = task.runId;
        row.tenantId = task.tenantId;
        row.userId = task.userId;
        row.sessionId = task.sessionId;
        row.requestId = task.requestId;
        row.createdAt = task.createdAt;
        row.payload = encode(task);
        mapper.upsertTask(row);
    }

    public String[] instruction(String runId, String id) {
        InstructionDO row = mapper.selectInstruction(runId, id);
        return row == null ? null : new String[] {row.fingerprint, row.response};
    }

    public void instruction(String runId, String id, String hash, Object response) {
        InstructionDO row = new InstructionDO();
        row.runId = runId;
        row.instructionId = id;
        row.fingerprint = hash;
        row.response = encode(response);
        mapper.insertInstruction(row);
    }

    /**
     * 读取并更新分钟窗口额度。读改写必须由调用方用事务包裹，当前由 TaskStoreService 负责。
     */
    public void reserve(String key, int calls, long tokens, int maxCalls, long maxTokens) {
        long now = System.currentTimeMillis(), started = now, usedTokens = 0;
        int used = 0;
        QuotaWindowDO window = mapper.selectWindow(key);
        if (window != null && now - window.startedAt < 60_000) {
            started = window.startedAt;
            used = window.calls;
            usedTokens = window.tokens;
        }
        if (used + calls > maxCalls || usedTokens + tokens > maxTokens) {
            throw new RunControl.BudgetExceeded("分钟窗口额度已耗尽");
        }
        QuotaWindowDO next = new QuotaWindowDO();
        next.windowKey = key;
        next.startedAt = started;
        next.calls = used + calls;
        next.tokens = usedTokens + tokens;
        mapper.upsertWindow(next);
    }

    public String currentSession(IdentityDTO identity, String sessionId) {
        return mapper.selectCurrentSession(identity.tenantId(), identity.userId(), sessionId);
    }

    public void currentSession(TaskDO task) {
        SessionDO row = new SessionDO();
        row.tenantId = task.tenantId;
        row.userId = task.userId;
        row.sessionId = task.sessionId;
        row.currentRunId = task.runId;
        mapper.upsertSession(row);
    }

    public void cleanup(long oldest) {
        for (TaskDO task : all()) {
            if (task.createdAt >= oldest
                    || task.state.equals("RUNNING")
                    || task.state.equals("NEEDS_INPUT")) {
                continue;
            }
            mapper.deleteInstructions(task.runId);
            mapper.deleteTask(task.runId);
        }
        mapper.deleteOrphanSessions();
    }

    public String encode(Object value) {
        return codec.encode(value);
    }

    public <T> T decode(String json, Class<T> type) {
        return codec.decode(json, type);
    }
}
