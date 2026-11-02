package dev.garden.demo.supervisor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.pojo.dataobject.ApprovalDO;
import dev.garden.demo.supervisor.pojo.dto.ApprovalDTO;
import dev.garden.demo.supervisor.pojo.dto.BookingOutcomeDTO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectResultDTO;
import dev.garden.demo.supervisor.pojo.param.ApprovalParam;
import dev.garden.demo.supervisor.pojo.res.OperationRes;
import dev.garden.demo.supervisor.repo.GovernanceRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 人工审批与外部写入编排。
 *
 * <p>写工具先登记审批记录并返回 PENDING_APPROVAL；批准后才调用账本，账本先落盘
 * RUNNING claim，再调用独立下游。拒绝、超时和参数变更都不会调用下游。审批、账本和
 * 任务使用不同的持久化边界：审批决策是声明式事务，账本保持自动提交以保留恢复语义。
 */
@Service
public final class ApprovalService {
    public static final String TOOL = "book_ticket";
    public static final String APPROVE_PERMISSION = "approve_book_ticket";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_RESPONSE = 2_000;

    private final GovernanceRepository repository;
    private final SideEffectLedger ledger;
    private final SideEffectLedger.Downstream downstream;
    private final AccessPolicy access;
    private final Clock clock;

    /**
     * 唯一构造器：审批仓储、账本、写入下游和身份映射均由容器注入。
     *
     * <p>下游为空时不执行真实写入，只保留审批证据；这用于未配置本地下游的只读实例。
     */
    public ApprovalService(
            GovernanceRepository repository,
            SideEffectLedger ledger,
            SideEffectLedger.Downstream downstream,
            AccessPolicy access,
            Clock clock) {
        this.repository = repository;
        this.ledger = ledger;
        this.downstream = downstream;
        this.access = access;
        this.clock = clock;
    }

    /** 写工具入口：没有审批时登记；已批准时经账本执行；拒绝或过期不调用下游。 */
    public BookingOutcomeDTO booking(
            String runId, IdentityDTO identity, String operationId, Map<String, Object> parameters) {
        if (downstream == null || !downstream.available()) {
            return new BookingOutcomeDTO(
                    "FAILED", null, operationId, null, "本实例未配置本地下单下游");
        }
        if (!identity.allows(TOOL)) {
            return new BookingOutcomeDTO(
                    "FAILED", null, operationId, null, "PERMISSION_DENIED");
        }
        String hash = hash(parameters);
        ApprovalDO current = repository.approval(runId, TOOL, operationId);
        if (current == null) {
            ApprovalDO row = new ApprovalDO();
            row.approvalId = UUID.randomUUID().toString();
            row.tenantId = identity.tenantId();
            row.userId = identity.userId();
            row.runId = runId;
            row.toolName = TOOL;
            row.operationId = operationId;
            row.requestHash = hash;
            row.parameters = encode(parameters);
            row.status = "PENDING";
            row.requiredPermission = APPROVE_PERMISSION;
            row.requestedAt = clock.millis();
            row.expiresAt = row.requestedAt + TTL_MS;
            row.decidedAt = 0;
            repository.saveApproval(row);
            return new BookingOutcomeDTO(
                    "PENDING_APPROVAL",
                    row.approvalId,
                    operationId,
                    null,
                    "需要人工审批后才能下单");
        }
        if (!current.requestHash.equals(hash)) {
            return new BookingOutcomeDTO(
                    "FAILED",
                    current.approvalId,
                    operationId,
                    null,
                    "APPROVAL_PARAMETER_CONFLICT");
        }
        if ("REJECTED".equals(current.status)) {
            return new BookingOutcomeDTO(
                    "FAILED", current.approvalId, operationId, null, "审批已拒绝");
        }
        if ("EXPIRED".equals(current.status) || current.expiresAt < clock.millis()) {
            return new BookingOutcomeDTO(
                    "FAILED", current.approvalId, operationId, null, "审批已过期");
        }
        if (!"APPROVED".equals(current.status)) {
            return new BookingOutcomeDTO(
                    "PENDING_APPROVAL",
                    current.approvalId,
                    operationId,
                    null,
                    "等待人工审批");
        }
        return execute(current, parameters);
    }

    /**
     * 批准或拒绝；批准后立即执行一次账本写入，重复批准返回已执行结果。
     *
     * <p>本方法故意不加事务：后续账本调用必须先以独立语句提交 RUNNING claim，
     * 包进同一个 Spring 事务会在崩溃时回滚 claim，破坏恢复语义。
     */
    public ApprovalDTO decide(IdentityDTO actor, ApprovalParam param) {
        if (param == null || blank(param.approvalId()) || blank(param.decision())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        ApprovalDO row = repository.approval(param.approvalId());
        if (row == null || !row.tenantId.equals(actor.tenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // 审批只接受专门的审批权限；申请人即使拥有写工具权限也不能自己批准。
        if (!actor.allows(APPROVE_PERMISSION)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String decision = param.decision().strip().toUpperCase(java.util.Locale.ROOT);
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!"PENDING".equals(row.status)) {
            if ("APPROVED".equals(row.status) && decision.equals("APPROVE")) {
                return view(row);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (row.expiresAt < clock.millis()) {
            repository.decide(row.approvalId, "EXPIRED", actor.userId(), clock.millis(), "审批超时");
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        repository.decide(
                row.approvalId,
                decision.equals("APPROVE") ? "APPROVED" : "REJECTED",
                actor.userId(),
                clock.millis(),
                param.reason());
        ApprovalDO decided = repository.approval(row.approvalId);
        if (decision.equals("REJECT")) {
            return view(decided);
        }
        BookingOutcomeDTO outcome = execute(decided, decode(decided.parameters));
        return view(repository.approval(row.approvalId), outcome);
    }

    /** 读取审批；只允许同租户身份。 */
    public ApprovalDTO get(IdentityDTO identity, String approvalId) {
        ApprovalDO row = repository.approval(approvalId);
        if (row == null || !row.tenantId.equals(identity.tenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return view(row);
    }

    /** 读取一次外部操作的账本证据。 */
    public OperationRes operation(IdentityDTO identity, String tool, String operationId) {
        SideEffectEvidenceDTO evidence = ledger.evidence(identity, tool, operationId);
        if (evidence == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return new OperationRes(
                evidence.tool(),
                evidence.operationId(),
                evidence.status(),
                evidence.parameterHash(),
                evidence.updatedAt(),
                evidence.submitAttempts(),
                evidence.lookupAttempts(),
                evidence.lastError());
    }

    /** 运行详情使用的审批列表；调用方已校验任务可见性，这里再校验租户。 */
    public List<ApprovalDTO> forRun(String runId, IdentityDTO identity) {
        List<ApprovalDTO> result = new ArrayList<>();
        for (ApprovalDO row : repository.approvals(runId)) {
            if (row.tenantId.equals(identity.tenantId())) {
                result.add(view(row));
            }
        }
        return result;
    }

    /** 运行详情使用的副作用证据：只读取当前身份仍有权限的工具。 */
    public List<SideEffectEvidenceDTO> sideEffects(String runId, IdentityDTO identity) {
        List<SideEffectEvidenceDTO> result = new ArrayList<>();
        for (ApprovalDO row : repository.approvals(runId)) {
            if (!row.tenantId.equals(identity.tenantId()) || !identity.allows(row.toolName)) {
                continue;
            }
            SideEffectEvidenceDTO evidence = ledger.evidence(identity, row.toolName, row.operationId);
            if (evidence != null) {
                result.add(evidence);
            }
        }
        return result;
    }

    public int pending() {
        return repository.pendingApprovals();
    }

    public int decided() {
        return repository.decidedApprovals();
    }

    /** 实际执行：重新读取当前身份权限，再交给账本做幂等和 UNKNOWN 处理。 */
    private BookingOutcomeDTO execute(ApprovalDO row, Map<String, Object> parameters) {
        IdentityDTO requester;
        try {
            requester = access == null
                    ? new IdentityDTO(row.tenantId, row.userId, Set.of(row.toolName))
                    : access.resolve(row.tenantId, row.userId);
        } catch (RuntimeException error) {
            return new BookingOutcomeDTO(
                    "FAILED", row.approvalId, row.operationId, null, "审批人身份已撤销");
        }
        SideEffectResultDTO result;
        try {
            result =
                    ledger.execute(
                            requester, row.toolName, row.operationId, parameters, downstream);
        } catch (SideEffectLedger.ParameterConflict error) {
            repository.operation(row.approvalId, "FAILED", "同一操作号的参数不一致");
            return new BookingOutcomeDTO(
                    "FAILED", row.approvalId, row.operationId, null, "同一操作号的参数不一致");
        } catch (SecurityException error) {
            repository.operation(row.approvalId, "FAILED", "PERMISSION_DENIED");
            return new BookingOutcomeDTO(
                    "FAILED", row.approvalId, row.operationId, null, "PERMISSION_DENIED");
        } catch (RuntimeException error) {
            repository.operation(row.approvalId, "FAILED", "账本不可用");
            return new BookingOutcomeDTO(
                    "FAILED", row.approvalId, row.operationId, null, "账本不可用");
        }
        String status = result.status().name();
        String response = truncate(result.response());
        repository.operation(row.approvalId, status, response);
        return new BookingOutcomeDTO(
                status, row.approvalId, row.operationId, response, message(status));
    }

    private static String message(String status) {
        return switch (status) {
            case "SUCCESS" -> "下单成功";
            case "UNKNOWN" -> "下游结果未知，系统只查询不重放";
            case "FAILED" -> "下游拒绝下单";
            default -> "操作已登记，等待核对";
        };
    }

    private static ApprovalDTO view(ApprovalDO row) {
        return view(row, null);
    }

    private static ApprovalDTO view(ApprovalDO row, BookingOutcomeDTO outcome) {
        return new ApprovalDTO(
                row.approvalId,
                row.runId,
                row.toolName,
                row.operationId,
                row.requestHash,
                row.status,
                row.userId,
                row.requiredPermission,
                row.requestedAt,
                row.expiresAt,
                row.decidedBy,
                row.decidedAt == 0 ? null : row.decidedAt,
                row.reason,
                outcome == null ? row.operationStatus : outcome.status(),
                outcome == null ? row.operationResponse : outcome.response());
    }

    private static String encode(Map<String, Object> parameters) {
        try {
            return JSON.writeValueAsString(new TreeMap<>(parameters));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static Map<String, Object> decode(String payload) {
        try {
            return JSON.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static String hash(Map<String, Object> parameters) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(encode(parameters).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("无法计算参数指纹", error);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_RESPONSE ? value : value.substring(0, MAX_RESPONSE) + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
