package dev.garden.demo.supervisor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务适配：持久化写入防重，不代表 Codex 对任意外部系统提供恰好一次保证。 一个服务持有一个账本实例；外部适配器必须提供持久幂等键、权威状态查询和有界 I/O。
 * 账本和外部写入使用独立事务，不能用本地成功记录代替下游事实。
 */
public final class SideEffectLedger implements AutoCloseable {
    public enum Status {
        RUNNING,
        SUCCESS,
        FAILED,
        UNKNOWN
    }

    public enum OutcomeStatus {
        SUCCESS,
        FAILED,
        PROCESSING,
        UNKNOWN,
        CONFIRMED_NOT_EXECUTED
    }

    public record Outcome(OutcomeStatus status, String response) {
        public Outcome {
            Objects.requireNonNull(status);
        }

        public static Outcome success(String response) {
            return new Outcome(OutcomeStatus.SUCCESS, response);
        }
    }

    /** 字符串结果不可变，避免回放时泄露可修改的嵌套对象。 */
    public record Result(
            String operationId,
            Status status,
            String response,
            int submitAttempts,
            int lookupAttempts) {}

    public interface Downstream {
        boolean supportsIdempotency();

        Outcome submit(String operationId, Map<String, Object> parameters) throws Exception;

        Outcome lookup(String operationId) throws Exception;
    }

    public static final class ParameterConflict extends RuntimeException {
        public ParameterConflict() {
            super("同一操作号的参数不一致");
        }
    }

    public static final class StorageFailure extends RuntimeException {
        StorageFailure(SQLException cause) {
            super("副作用账本不可用", cause);
        }
    }

    private static final int MAX_SUBMITS = 3,
            MAX_LOOKUPS = 8,
            MAX_BATCH = 100,
            MAX_PAYLOAD = 65_536;
    private static final long LOOKUP_INTERVAL_MS = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection database;
    private final Clock clock;
    private final AtomicBoolean reconciling = new AtomicBoolean();
    private final AtomicBoolean lookupActive = new AtomicBoolean();
    private final Set<String> lookingUp = new HashSet<>();

    public SideEffectLedger(String jdbcUrl) {
        this(jdbcUrl, Clock.systemUTC());
    }

    SideEffectLedger(String jdbcUrl, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        Connection opened = null;
        try {
            opened = DriverManager.getConnection(jdbcUrl, "sa", "");
            try (var statement = opened.createStatement()) {
                statement.execute("SET WRITE_DELAY 0");
                statement.execute(
                        """
CREATE TABLE IF NOT EXISTS side_effect_operations (
  tenant_id VARCHAR(256) NOT NULL, user_id VARCHAR(256) NOT NULL,
  tool_name VARCHAR(256) NOT NULL, operation_id VARCHAR(256) NOT NULL,
  downstream_id VARCHAR(64) NOT NULL UNIQUE, params_hash VARCHAR(64) NOT NULL,
  params_json CLOB NOT NULL, operation_status VARCHAR(16) NOT NULL,
  response_text CLOB, submit_count INT NOT NULL, lookup_count INT NOT NULL,
  next_lookup BIGINT NOT NULL, retry_allowed BOOLEAN NOT NULL,
  PRIMARY KEY (tenant_id, user_id, tool_name, operation_id))
""");
                // 上次进程遗留的 RUNNING 只能查询，不把它当首次操作重放。
                statement.executeUpdate(
                        "UPDATE side_effect_operations SET operation_status='UNKNOWN' WHERE"
                            + " operation_status='RUNNING'");
            }
            database = opened;
        } catch (SQLException ex) {
            if (opened != null)
                try {
                    opened.close();
                } catch (SQLException closeError) {
                    ex.addSuppressed(closeError);
                }
            throw new StorageFailure(ex);
        }
    }

    public Result execute(
            AccessPolicy.Identity identity,
            String tool,
            String operationId,
            Map<String, ?> parameters,
            Downstream downstream) {
        Key key = authorizedKey(identity, tool, operationId);
        Objects.requireNonNull(downstream);
        String payload = canonicalParameters(parameters), hash = sha256(payload);
        boolean claimed;
        synchronized (database) {
            Row current = find(key);
            if (current != null && !current.hash.equals(hash)) throw new ParameterConflict();
            if (current == null) {
                if (!downstream.supportsIdempotency())
                    throw new IllegalArgumentException("写入适配器必须支持持久幂等键");
                claimed = insert(key, payload, hash);
            } else claimed = claimRetry(current, downstream);
        }
        var interruption = new Interruption();
        try {
            if (claimed) submit(key, payload, downstream, interruption);
            else lookup(key, downstream, interruption);
            // 当前调用的可信身份在每次结果回放前重新经过同一权限门。
            return read(identity, tool, operationId);
        } finally {
            interruption.restore();
        }
    }

    public Result read(AccessPolicy.Identity identity, String tool, String operationId) {
        Key key = authorizedKey(identity, tool, operationId);
        synchronized (database) {
            Row row = find(key);
            return row == null ? null : row.result();
        }
    }

    /** 启动和定时器均调用此入口；不依赖任务是否取消、超时或已结束。只查状态，不自动写入。 */
    public int reconcile(Map<String, Downstream> downstreams, int batchLimit) {
        Objects.requireNonNull(downstreams);
        if (batchLimit < 1 || batchLimit > MAX_BATCH)
            throw new IllegalArgumentException("核对批次须在 1 到 100 之间");
        Map<String, Downstream> registered = Map.copyOf(downstreams);
        if (registered.isEmpty()) return 0;
        List<String> tools = List.copyOf(registered.keySet());
        String placeholders = String.join(",", Collections.nCopies(tools.size(), "?"));
        if (!reconciling.compareAndSet(false, true)) return 0;
        var interruption = new Interruption();
        try {
            List<Key> pending = new ArrayList<>();
            synchronized (database) {
                interruption.capture();
                if (interruption.interrupted) return 0;
                // 先过滤可核对工具再限批量，移除适配器的旧记录不能占满后续批次。
                try (var query =
                        database.prepareStatement(
                                "SELECT tenant_id,user_id,tool_name,operation_id FROM"
                                    + " side_effect_operations WHERE operation_status='UNKNOWN' AND"
                                    + " lookup_count<? AND next_lookup<=? AND tool_name IN ("
                                        + placeholders
                                        + ") ORDER BY next_lookup,downstream_id LIMIT ?")) {
                    query.setInt(1, MAX_LOOKUPS);
                    query.setLong(2, clock.millis());
                    int parameter = 3;
                    for (String tool : tools) query.setString(parameter++, tool);
                    query.setInt(parameter, batchLimit);
                    try (var rows = query.executeQuery()) {
                        while (rows.next())
                            pending.add(
                                    new Key(
                                            rows.getString(1),
                                            rows.getString(2),
                                            rows.getString(3),
                                            rows.getString(4)));
                    }
                } catch (SQLException ex) {
                    throw new StorageFailure(ex);
                }
            }
            int checked = 0;
            for (Key key : pending) {
                Downstream adapter = registered.get(key.tool);
                if (adapter != null && lookup(key, adapter, interruption)) checked++;
                interruption.capture();
                if (interruption.interrupted) break;
            }
            return checked;
        } finally {
            reconciling.set(false);
            interruption.restore();
        }
    }

    private void submit(Key key, String payload, Downstream downstream, Interruption interruption) {
        Outcome outcome;
        try {
            outcome =
                    Objects.requireNonNull(
                            downstream.submit(key.downstreamId(), immutableParameters(payload)));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) interruption.interrupted = true;
            outcome = new Outcome(OutcomeStatus.UNKNOWN, null);
        }
        interruption.capture();
        synchronized (database) {
            saveOutcome(key, outcome, false);
        }
        if (!interruption.interrupted
                && outcome.status != OutcomeStatus.SUCCESS
                && outcome.status != OutcomeStatus.FAILED) lookup(key, downstream, interruption);
    }

    private boolean lookup(Key key, Downstream downstream, Interruption interruption) {
        if (!lookupActive.compareAndSet(false, true)) return false;
        try {
            return lookupExclusive(key, downstream, interruption);
        } finally {
            lookupActive.set(false);
        }
    }

    private boolean lookupExclusive(Key key, Downstream downstream, Interruption interruption) {
        synchronized (database) {
            interruption.capture();
            if (interruption.interrupted) return false;
            Row row = find(key);
            if (row == null
                    || row.status != Status.UNKNOWN
                    || row.lookupCount >= MAX_LOOKUPS
                    || row.nextLookup > clock.millis()
                    || !lookingUp.add(key.downstreamId())) return false;
            try (var update =
                    database.prepareStatement(
                            "UPDATE side_effect_operations SET"
                                + " lookup_count=lookup_count+1,next_lookup=? WHERE"
                                + " downstream_id=?")) {
                update.setLong(1, clock.millis() + LOOKUP_INTERVAL_MS);
                update.setString(2, key.downstreamId());
                update.executeUpdate();
            } catch (SQLException ex) {
                lookingUp.remove(key.downstreamId());
                throw new StorageFailure(ex);
            }
        }
        try {
            Outcome outcome;
            try {
                outcome = Objects.requireNonNull(downstream.lookup(key.downstreamId()));
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) interruption.interrupted = true;
                outcome = new Outcome(OutcomeStatus.UNKNOWN, null);
            }
            synchronized (database) {
                interruption.capture();
                saveOutcome(key, outcome, downstream.supportsIdempotency());
            }
            return true;
        } finally {
            synchronized (database) {
                lookingUp.remove(key.downstreamId());
            }
        }
    }

    private boolean claimRetry(Row row, Downstream downstream) {
        // 仅当前有权限的新调用可重试；核对器永远不会提交写入。
        if (row.status != Status.UNKNOWN
                || !row.retryAllowed
                || row.submitCount >= MAX_SUBMITS
                || lookingUp.contains(row.key.downstreamId())
                || !downstream.supportsIdempotency()) return false;
        try (var update =
                database.prepareStatement(
                        "UPDATE side_effect_operations SET"
                            + " operation_status='RUNNING',submit_count=submit_count+1,retry_allowed=FALSE"
                            + " WHERE downstream_id=?")) {
            update.setString(1, row.key.downstreamId());
            update.executeUpdate();
            return true;
        } catch (SQLException ex) {
            throw new StorageFailure(ex);
        }
    }

    private boolean insert(Key key, String payload, String hash) {
        try (var insert =
                database.prepareStatement(
                        "INSERT INTO side_effect_operations VALUES"
                            + " (?,?,?,?,?,?,?,'RUNNING',NULL,1,0,0,FALSE)")) {
            insert.setString(1, key.tenant);
            insert.setString(2, key.user);
            insert.setString(3, key.tool);
            insert.setString(4, key.operation);
            insert.setString(5, key.downstreamId());
            insert.setString(6, hash);
            insert.setString(7, payload);
            insert.executeUpdate();
            return true;
        } catch (SQLException ex) {
            throw new StorageFailure(ex);
        }
    }

    private void saveOutcome(Key key, Outcome outcome, boolean idempotentLookup) {
        Status status =
                switch (outcome.status) {
                    case SUCCESS -> Status.SUCCESS;
                    case FAILED -> Status.FAILED;
                    default -> Status.UNKNOWN;
                };
        String response = outcome.response;
        // 无法安全保存响应时保持待核对，绝不把数据库未持久化的结果当成功。
        if (response != null && response.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD) {
            status = Status.UNKNOWN;
            response = null;
        }
        boolean retryAllowed =
                idempotentLookup && outcome.status == OutcomeStatus.CONFIRMED_NOT_EXECUTED;
        try (var update =
                database.prepareStatement(
                        "UPDATE side_effect_operations SET"
                            + " operation_status=?,response_text=?,retry_allowed=? WHERE"
                            + " downstream_id=? AND operation_status IN ('RUNNING','UNKNOWN')")) {
            update.setString(1, status.name());
            update.setString(2, response);
            update.setBoolean(3, retryAllowed);
            update.setString(4, key.downstreamId());
            update.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageFailure(ex);
        }
    }

    private Row find(Key key) {
        try (var query =
                database.prepareStatement(
                        "SELECT"
                            + " params_hash,operation_status,response_text,submit_count,lookup_count,next_lookup,retry_allowed"
                            + " FROM side_effect_operations WHERE tenant_id=? AND user_id=? AND"
                            + " tool_name=? AND operation_id=?")) {
            query.setString(1, key.tenant);
            query.setString(2, key.user);
            query.setString(3, key.tool);
            query.setString(4, key.operation);
            try (var rows = query.executeQuery()) {
                return rows.next()
                        ? new Row(
                                key,
                                rows.getString(1),
                                Status.valueOf(rows.getString(2)),
                                rows.getString(3),
                                rows.getInt(4),
                                rows.getInt(5),
                                rows.getLong(6),
                                rows.getBoolean(7))
                        : null;
            }
        } catch (SQLException ex) {
            throw new StorageFailure(ex);
        }
    }

    private static Key authorizedKey(
            AccessPolicy.Identity identity, String tool, String operation) {
        Objects.requireNonNull(identity);
        if (!identity.allows(tool)) throw new SecurityException("当前身份无工具权限");
        for (String value : List.of(identity.tenantId(), identity.userId(), tool, operation))
            if (value.isBlank() || value.length() > 256)
                throw new IllegalArgumentException("操作标识为空或过长");
        return new Key(identity.tenantId(), identity.userId(), tool, operation);
    }

    private static String canonicalParameters(Map<String, ?> parameters) {
        Objects.requireNonNull(parameters);
        try {
            String json = JSON.writeValueAsString(canonicalNode(JSON.valueToTree(parameters)));
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD)
                throw new IllegalArgumentException("参数过长");
            return json;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("参数不是合法 JSON", ex);
        }
    }

    private static JsonNode canonicalNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            names.forEach(name -> result.set(name, canonicalNode(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.forEach(value -> result.add(canonicalNode(value)));
            return result;
        }
        return node;
    }

    private static Map<String, Object> immutableParameters(String payload) {
        try {
            return freezeMap(JSON.readValue(payload, new TypeReference<Map<String, Object>>() {}));
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("已保存参数无法解析", ex);
        }
    }

    private static Map<String, Object> freezeMap(Map<String, ?> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put(key, freeze(item)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put((String) key, freeze(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list)
            return Collections.unmodifiableList(
                    list.stream().map(SideEffectLedger::freeze).toList());
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // 先完成未知状态落盘，再恢复调用线程的中断标记，避免中断关闭 H2 文件通道。
    private static final class Interruption {
        boolean interrupted;

        void capture() {
            interrupted |= Thread.interrupted();
        }

        void restore() {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record Key(String tenant, String user, String tool, String operation) {
        String downstreamId() {
            return sha256(
                    tenant.length()
                            + ":"
                            + tenant
                            + user.length()
                            + ":"
                            + user
                            + tool.length()
                            + ":"
                            + tool
                            + operation.length()
                            + ":"
                            + operation);
        }
    }

    private record Row(
            Key key,
            String hash,
            Status status,
            String response,
            int submitCount,
            int lookupCount,
            long nextLookup,
            boolean retryAllowed) {
        Result result() {
            return new Result(key.operation, status, response, submitCount, lookupCount);
        }
    }

    // 调度器关闭时只在没有数据库 I/O 的临界区中发出中断。
    void interruptReconciliation(Thread worker) {
        synchronized (database) {
            if (worker != null) worker.interrupt();
        }
    }

    @Override
    public void close() {
        synchronized (database) {
            try {
                database.close();
            } catch (SQLException ex) {
                throw new StorageFailure(ex);
            }
        }
    }
}
