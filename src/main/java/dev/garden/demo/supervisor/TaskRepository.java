package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** 单进程 H2 事务入口；一把短事务锁保护预算、版本和幂等，锁内不执行模型。 */
public final class TaskRepository implements AutoCloseable {
    public static final class Task {
        public String runId, tenantId, userId, sessionId, requestId, requestHash;
        public ChatInput request;
        public AccessPolicy.Identity identity;
        public String state = "RUNNING";
        public int revision = 1, consumedRevision = 1, generation = 1, calls;
        public int maxCalls;
        public long createdAt, deadline;
        public Result result;
        public List<String> pending = new ArrayList<>();
        public List<String> userInputs = new ArrayList<>();
        public int recordedInputs;
        public Map<String, String> checkpoints = new LinkedHashMap<>();
        public List<String> events = new ArrayList<>();
        public String replacedBy;
    }

    private final Connection connection;
    private final ObjectMapper mapper;
    private int depth;

    public TaskRepository(String url, ObjectMapper mapper) {
        this.mapper = mapper.copy().findAndRegisterModules();
        try {
            connection = DriverManager.getConnection(url, "sa", "");
            connection.setAutoCommit(false);
            // 预算与检查点在提交返回前刷盘，避免进程骤停时回退已使用额度。
            execute(connection, "SET WRITE_DELAY 0");
            transaction(
                    c -> {
                        execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS supervisor_tasks (run_id VARCHAR(64)"
                                    + " PRIMARY KEY, tenant_id VARCHAR(128), user_id VARCHAR(128),"
                                    + " session_id VARCHAR(200), request_id VARCHAR(200),"
                                    + " created_at BIGINT, payload CLOB,"
                                    + " UNIQUE(tenant_id,user_id,request_id))");
                        execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS supervisor_instructions (run_id"
                                        + " VARCHAR(64), instruction_id VARCHAR(200), fingerprint"
                                        + " VARCHAR(64), response CLOB, PRIMARY"
                                        + " KEY(run_id,instruction_id))");
                        execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS supervisor_windows (window_key"
                                        + " VARCHAR(400) PRIMARY KEY, started_at BIGINT, calls INT,"
                                        + " tokens BIGINT)");
                        execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS supervisor_sessions (tenant_id"
                                        + " VARCHAR(128), user_id VARCHAR(128), session_id"
                                        + " VARCHAR(200), current_run_id VARCHAR(64), PRIMARY"
                                        + " KEY(tenant_id,user_id,session_id))");
                        return null;
                    });
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public synchronized <T> T transaction(Function<Connection, T> work) {
        boolean outer = depth++ == 0;
        try {
            T result = work.apply(connection);
            if (outer) connection.commit();
            return result;
        } catch (Throwable error) {
            if (outer)
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
            if (error instanceof RuntimeException runtime) throw runtime;
            throw unavailable(error);
        } finally {
            depth--;
        }
    }

    Task get(String id) {
        return transaction(
                c -> {
                    try (var q =
                            c.prepareStatement(
                                    "SELECT payload FROM supervisor_tasks WHERE run_id=?")) {
                        q.setString(1, id);
                        try (var rs = q.executeQuery()) {
                            return rs.next() ? decode(rs.getString(1), Task.class) : null;
                        }
                    } catch (SQLException e) {
                        throw unavailable(e);
                    }
                });
    }

    List<Task> all() {
        return transaction(
                c -> {
                    List<Task> result = new ArrayList<>();
                    try (var q =
                                    c.prepareStatement(
                                            "SELECT payload FROM supervisor_tasks ORDER BY"
                                                    + " created_at");
                            var rs = q.executeQuery()) {
                        while (rs.next()) result.add(decode(rs.getString(1), Task.class));
                        return result;
                    } catch (SQLException e) {
                        throw unavailable(e);
                    }
                });
    }

    void save(Task task) {
        transaction(
                c -> {
                    try (var q =
                            c.prepareStatement(
                                    "MERGE INTO supervisor_tasks"
                                        + " (run_id,tenant_id,user_id,session_id,request_id,created_at,payload)"
                                        + " KEY(run_id) VALUES(?,?,?,?,?,?,?)")) {
                        q.setString(1, task.runId);
                        q.setString(2, task.tenantId);
                        q.setString(3, task.userId);
                        q.setString(4, task.sessionId);
                        q.setString(5, task.requestId);
                        q.setLong(6, task.createdAt);
                        q.setString(7, encode(task));
                        q.executeUpdate();
                        return null;
                    } catch (SQLException e) {
                        throw unavailable(e);
                    }
                });
    }

    String[] instruction(String runId, String id) {
        return transaction(
                c -> {
                    try (var q =
                            c.prepareStatement(
                                    "SELECT fingerprint,response FROM supervisor_instructions WHERE"
                                            + " run_id=? AND instruction_id=?")) {
                        q.setString(1, runId);
                        q.setString(2, id);
                        try (var rs = q.executeQuery()) {
                            return rs.next()
                                    ? new String[] {rs.getString(1), rs.getString(2)}
                                    : null;
                        }
                    } catch (SQLException e) {
                        throw unavailable(e);
                    }
                });
    }

    void instruction(String runId, String id, String hash, Object response) {
        transaction(
                c -> {
                    try (var q =
                            c.prepareStatement(
                                    "INSERT INTO supervisor_instructions VALUES(?,?,?,?)")) {
                        q.setString(1, runId);
                        q.setString(2, id);
                        q.setString(3, hash);
                        q.setString(4, encode(response));
                        q.executeUpdate();
                        return null;
                    } catch (SQLException e) {
                        throw unavailable(e);
                    }
                });
    }

    void reserve(String key, int calls, long tokens, int maxCalls, long maxTokens) {
        transaction(
                c -> {
                    long now = System.currentTimeMillis(), started = now, usedTokens = 0;
                    int used = 0;
                    try (var q =
                            c.prepareStatement(
                                    "SELECT started_at,calls,tokens FROM supervisor_windows WHERE"
                                            + " window_key=?")) {
                        q.setString(1, key);
                        try (var rs = q.executeQuery()) {
                            if (rs.next() && now - rs.getLong(1) < 60_000) {
                                started = rs.getLong(1);
                                used = rs.getInt(2);
                                usedTokens = rs.getLong(3);
                            }
                        }
                    } catch (SQLException e) {
                        throw unavailable(e);
                    }
                    if (used + calls > maxCalls || usedTokens + tokens > maxTokens)
                        throw new RunControl.BudgetExceeded("分钟窗口额度已耗尽");
                    try (var q =
                            c.prepareStatement(
                                    "MERGE INTO supervisor_windows KEY(window_key)"
                                            + " VALUES(?,?,?,?)")) {
                        q.setString(1, key);
                        q.setLong(2, started);
                        q.setInt(3, used + calls);
                        q.setLong(4, usedTokens + tokens);
                        q.executeUpdate();
                        return null;
                    } catch (SQLException e) {
                        throw unavailable(e);
                    }
                });
    }

    String currentSession(AccessPolicy.Identity identity, String sessionId) {
        return transaction(
                c -> {
                    try (var query =
                            c.prepareStatement(
                                    "SELECT current_run_id FROM supervisor_sessions WHERE"
                                            + " tenant_id=? AND user_id=? AND session_id=?")) {
                        query.setString(1, identity.tenantId());
                        query.setString(2, identity.userId());
                        query.setString(3, sessionId);
                        try (var rows = query.executeQuery()) {
                            return rows.next() ? rows.getString(1) : null;
                        }
                    } catch (SQLException error) {
                        throw unavailable(error);
                    }
                });
    }

    void currentSession(Task task) {
        transaction(
                c -> {
                    try (var save =
                            c.prepareStatement(
                                    "MERGE INTO supervisor_sessions"
                                        + " KEY(tenant_id,user_id,session_id) VALUES(?,?,?,?)")) {
                        save.setString(1, task.tenantId);
                        save.setString(2, task.userId);
                        save.setString(3, task.sessionId);
                        save.setString(4, task.runId);
                        save.executeUpdate();
                        return null;
                    } catch (SQLException error) {
                        throw unavailable(error);
                    }
                });
    }

    void cleanup(long oldest) {
        transaction(
                c -> {
                    for (Task task : all()) {
                        if (task.createdAt >= oldest
                                || task.state.equals("RUNNING")
                                || task.state.equals("NEEDS_INPUT")) continue;
                        try (var instructions =
                                        c.prepareStatement(
                                                "DELETE FROM supervisor_instructions WHERE"
                                                        + " run_id=?");
                                var tasks =
                                        c.prepareStatement(
                                                "DELETE FROM supervisor_tasks WHERE run_id=?")) {
                            instructions.setString(1, task.runId);
                            instructions.executeUpdate();
                            tasks.setString(1, task.runId);
                            tasks.executeUpdate();
                        } catch (SQLException e) {
                            throw unavailable(e);
                        }
                    }
                    execute(
                            c,
                            "DELETE FROM supervisor_sessions WHERE current_run_id NOT IN (SELECT"
                                    + " run_id FROM supervisor_tasks)");
                    return null;
                });
    }

    String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("任务无法序列化", e);
        }
    }

    <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("任务记录损坏", e);
        }
    }

    static void execute(Connection c, String sql) {
        try (var q = c.createStatement()) {
            q.execute(sql);
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    static RuntimeException unavailable(Throwable e) {
        return new DataAccessResourceFailureException("任务存储暂不可用", e);
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }
}
