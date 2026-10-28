package dev.garden.demo.supervisor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.garden.demo.supervisor.pojo.dataobject.SideEffectDO;
import dev.garden.demo.supervisor.pojo.dataobject.SideEffectKeyDO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectOutcomeDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectOutcomeStatus;
import dev.garden.demo.supervisor.pojo.dto.SideEffectResultDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectStatus;
import dev.garden.demo.supervisor.repo.SideEffectRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务适配：持久化写入防重，不代表 Codex 对任意外部系统提供恰好一次保证。 一个服务持有一个账本实例；外部适配器必须提供持久幂等键、权威状态查询和有界 I/O。
 * 账本和外部写入使用独立事务，不能用本地成功记录代替下游事实。
 *
 * <p>账本的每条语句都在连接池自动提交下立即落盘，故意不使用 Spring 事务：claim 和 RUNNING
 * 状态必须先提交，再调用下游，进程崩溃后才有据可查。
 */
public final class SideEffectLedger {

    public interface Downstream {
        boolean supportsIdempotency();

        SideEffectOutcomeDTO submit(String operationId, Map<String, Object> parameters)
                throws Exception;

        SideEffectOutcomeDTO lookup(String operationId) throws Exception;
    }

    public static final class ParameterConflict extends RuntimeException {
        public ParameterConflict() {
            super("同一操作号的参数不一致");
        }
    }

    public static final class StorageFailure extends RuntimeException {
        StorageFailure(Throwable cause) {
            super("副作用账本不可用", cause);
        }
    }

    private static final int MAX_SUBMITS = 3,
            MAX_LOOKUPS = 8,
            MAX_BATCH = 100,
            MAX_PAYLOAD = 65_536;
    private static final long LOOKUP_INTERVAL_MS = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SideEffectRepository repository;
    private final Object lock = new Object();
    private final Clock clock;
    private final AtomicBoolean reconciling = new AtomicBoolean();
    private final AtomicBoolean lookupActive = new AtomicBoolean();
    private final Set<String> lookingUp = new HashSet<>();

    /**
     * 唯一构造器：使用共享 SideEffectRepository 和时钟。
     *
     * <p>账本不拥有连接池，也不提供 close；测试如需独立数据库，在测试代码中打开
     * Database 并用它构造 SideEffectRepository。
     */
    public SideEffectLedger(SideEffectRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public SideEffectResultDTO execute(
            IdentityDTO identity,
            String tool,
            String operationId,
            Map<String, ?> parameters,
            Downstream downstream) {
        Key key = authorizedKey(identity, tool, operationId);
        Objects.requireNonNull(downstream);
        String payload = canonicalParameters(parameters), hash = sha256(payload);
        boolean claimed;
        synchronized (lock) {
            Row current = find(key);
            if (current != null && !current.hash.equals(hash)) {
                throw new ParameterConflict();
            }
            if (current == null) {
                if (!downstream.supportsIdempotency()) {
                    throw new IllegalArgumentException("写入适配器必须支持持久幂等键");
                }
                claimed = insert(key, payload, hash);
            } else {
                claimed = claimRetry(current, downstream);
            }
        }
        var interruption = new Interruption();
        try {
            if (claimed) {
                submit(key, payload, downstream, interruption);
            } else {
                lookup(key, downstream, interruption);
            }
            // 当前调用的可信身份在每次结果回放前重新经过同一权限门。
            return read(identity, tool, operationId);
        } finally {
            interruption.restore();
        }
    }

    public SideEffectResultDTO read(IdentityDTO identity, String tool, String operationId) {
        Key key = authorizedKey(identity, tool, operationId);
        synchronized (lock) {
            Row row = find(key);
            return row == null ? null : row.result();
        }
    }

    /** 启动和定时器均调用此入口；不依赖任务是否取消、超时或已结束。只查状态，不自动写入。 */
    public int reconcile(Map<String, Downstream> downstreams, int batchLimit) {
        Objects.requireNonNull(downstreams);
        if (batchLimit < 1 || batchLimit > MAX_BATCH) {
            throw new IllegalArgumentException("核对批次须在 1 到 100 之间");
        }
        Map<String, Downstream> registered = Map.copyOf(downstreams);
        if (registered.isEmpty()) {
            return 0;
        }
        List<String> tools = List.copyOf(registered.keySet());
        if (!reconciling.compareAndSet(false, true)) {
            return 0;
        }
        var interruption = new Interruption();
        try {
            List<Key> pending = new ArrayList<>();
            synchronized (lock) {
                interruption.capture();
                if (interruption.interrupted) {
                    return 0;
                }
                // 先过滤可核对工具再限批量，移除适配器的旧记录不能占满后续批次。
                for (SideEffectKeyDO row :
                        repository.findPending(tools, MAX_LOOKUPS, clock.millis(), batchLimit))
                    pending.add(
                            new Key(row.tenantId, row.userId, row.toolName, row.operationId));
            }
            int checked = 0;
            for (Key key : pending) {
                Downstream adapter = registered.get(key.tool);
                if (adapter != null && lookup(key, adapter, interruption)) {
                    checked++;
                }
                interruption.capture();
                if (interruption.interrupted) {
                    break;
                }
            }
            return checked;
        } finally {
            reconciling.set(false);
            interruption.restore();
        }
    }

    private void submit(
            Key key, String payload, Downstream downstream, Interruption interruption) {
        SideEffectOutcomeDTO outcome;
        try {
            outcome =
                    Objects.requireNonNull(
                            downstream.submit(key.downstreamId(), immutableParameters(payload)));
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                interruption.interrupted = true;
            }
            outcome = new SideEffectOutcomeDTO(SideEffectOutcomeStatus.UNKNOWN, null);
        }
        interruption.capture();
        synchronized (lock) {
            saveOutcome(key, outcome, false);
        }
        if (!interruption.interrupted
                && outcome.status() != SideEffectOutcomeStatus.SUCCESS
                && outcome.status() != SideEffectOutcomeStatus.FAILED) {
            lookup(key, downstream, interruption);
        }
    }

    private boolean lookup(Key key, Downstream downstream, Interruption interruption) {
        if (!lookupActive.compareAndSet(false, true)) {
            return false;
        }
        try {
            return lookupExclusive(key, downstream, interruption);
        } finally {
            lookupActive.set(false);
        }
    }

    private boolean lookupExclusive(Key key, Downstream downstream, Interruption interruption) {
        synchronized (lock) {
            interruption.capture();
            if (interruption.interrupted) {
                return false;
            }
            Row row = find(key);
            if (row == null
                    || row.status != SideEffectStatus.UNKNOWN
                    || row.lookupCount >= MAX_LOOKUPS
                    || row.nextLookup > clock.millis()
                    || !lookingUp.add(key.downstreamId())) {
                return false;
            }
            repository.bumpLookup(key.downstreamId(), clock.millis() + LOOKUP_INTERVAL_MS);
        }
        try {
            SideEffectOutcomeDTO outcome;
            try {
                outcome = Objects.requireNonNull(downstream.lookup(key.downstreamId()));
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    interruption.interrupted = true;
                }
                outcome = new SideEffectOutcomeDTO(SideEffectOutcomeStatus.UNKNOWN, null);
            }
            synchronized (lock) {
                interruption.capture();
                saveOutcome(key, outcome, downstream.supportsIdempotency());
            }
            return true;
        } finally {
            synchronized (lock) {
                lookingUp.remove(key.downstreamId());
            }
        }
    }

    private boolean claimRetry(Row row, Downstream downstream) {
        // 仅当前有权限的新调用可重试；核对器永远不会提交写入。
        if (row.status != SideEffectStatus.UNKNOWN
                || !row.retryAllowed
                || row.submitCount >= MAX_SUBMITS
                || lookingUp.contains(row.key.downstreamId())
                || !downstream.supportsIdempotency()) {
            return false;
        }
        repository.claimRetry(row.key.downstreamId());
        return true;
    }

    private boolean insert(Key key, String payload, String hash) {
        SideEffectDO row = new SideEffectDO();
        row.tenantId = key.tenant;
        row.userId = key.user;
        row.toolName = key.tool;
        row.operationId = key.operation;
        row.downstreamId = key.downstreamId();
        row.paramsHash = hash;
        row.paramsJson = payload;
        return repository.insert(row);
    }

    private void saveOutcome(
            Key key, SideEffectOutcomeDTO outcome, boolean idempotentLookup) {
        SideEffectStatus status =
                switch (outcome.status()) {
                    case SUCCESS -> SideEffectStatus.SUCCESS;
                    case FAILED -> SideEffectStatus.FAILED;
                    default -> SideEffectStatus.UNKNOWN;
                };
        String response = outcome.response();
        // 无法安全保存响应时保持待核对，绝不把数据库未持久化的结果当成功。
        if (response != null && response.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD) {
            status = SideEffectStatus.UNKNOWN;
            response = null;
        }
        boolean retryAllowed =
                idempotentLookup
                        && outcome.status() == SideEffectOutcomeStatus.CONFIRMED_NOT_EXECUTED;
        repository.saveOutcome(key.downstreamId(), status.name(), response, retryAllowed);
    }

    private Row find(Key key) {
        SideEffectDO row = repository.find(key.tenant, key.user, key.tool, key.operation);
        return row == null
                ? null
                : new Row(
                        key,
                        row.paramsHash,
                        SideEffectStatus.valueOf(row.operationStatus),
                        row.responseText,
                        row.submitCount,
                        row.lookupCount,
                        row.nextLookup,
                        row.retryAllowed);
    }

    private static Key authorizedKey(IdentityDTO identity, String tool, String operation) {
        Objects.requireNonNull(identity);
        if (!identity.allows(tool)) {
            throw new SecurityException("当前身份无工具权限");
        }
        for (String value : List.of(identity.tenantId(), identity.userId(), tool, operation))
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("操作标识为空或过长");
            }
        return new Key(identity.tenantId(), identity.userId(), tool, operation);
    }

    private static String canonicalParameters(Map<String, ?> parameters) {
        Objects.requireNonNull(parameters);
        try {
            String json = JSON.writeValueAsString(canonicalNode(JSON.valueToTree(parameters)));
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD) {
                throw new IllegalArgumentException("参数过长");
            }
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
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(
                    list.stream().map(SideEffectLedger::freeze).toList());
        }
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
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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
            SideEffectStatus status,
            String response,
            int submitCount,
            int lookupCount,
            long nextLookup,
            boolean retryAllowed) {
        SideEffectResultDTO result() {
            return new SideEffectResultDTO(
                    key.operation, status, response, submitCount, lookupCount);
        }
    }

    // 调度器关闭时只在没有数据库 I/O 的临界区中发出中断。
    void interruptReconciliation(Thread worker) {
        synchronized (lock) {
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
