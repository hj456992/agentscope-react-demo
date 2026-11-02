package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.garden.demo.supervisor.pojo.dto.BookingOutcomeDTO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectOutcomeDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectOutcomeStatus;
import dev.garden.demo.supervisor.pojo.param.ApprovalParam;
import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.repo.GovernanceRepository;
import dev.garden.demo.supervisor.service.AccessPolicy;
import dev.garden.demo.supervisor.service.ApprovalService;
import dev.garden.demo.supervisor.service.SideEffectLedger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审批编排测试：审批、拒绝、职责分离、同号异参和 UNKNOWN 查询。
 *
 * <p>下游是测试替身，只用于证明调用次数和账本证据，不代替真实页面验收。
 */
class ApprovalServiceTest {
    private static final String RUN = "run-approval";
    private static final String OPERATION = "book:run-approval:operation";
    private static final IdentityDTO APPLICANT =
            new IdentityDTO("tenant", "demo-user", Set.of("book_ticket"));
    private static final IdentityDTO APPROVER =
            new IdentityDTO("tenant", "approver", Set.of("approve_book_ticket"));
    private static final Map<String, Object> PARAMETERS =
            Map.of("from", "杭州", "to", "上海", "date", "2026-11-10", "passenger", "张三");

    @TempDir Path directory;
    private Database database;
    private ApprovalService approvals;
    private CountingDownstream downstream;

    @BeforeEach
    void setUp() {
        database = TestServices.database("jdbc:h2:file:" + directory.resolve("approval"));
        GovernanceRepository repository = new GovernanceRepository(database);
        SideEffectLedger ledger = TestServices.sideEffectLedger(database, Clock.systemUTC());
        AccessPolicy access =
                new AccessPolicy(
                        "demo-token=tenant:demo-user:book_ticket;"
                                + "approver-token=tenant:approver:approve_book_ticket");
        downstream = new CountingDownstream();
        approvals = new ApprovalService(repository, ledger, downstream, access, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void pendingApprovalExecutesOnceAndRepeatedApprovalDoesNotSubmitAgain() {
        BookingOutcomeDTO pending = approvals.booking(RUN, APPLICANT, OPERATION, PARAMETERS);
        assertEquals("PENDING_APPROVAL", pending.status());
        assertNotNull(pending.approvalId());
        assertEquals(0, downstream.submits.get());

        var decided =
                approvals.decide(
                        APPROVER,
                        new ApprovalParam(pending.approvalId(), "APPROVE", "核对通过"));
        assertEquals("APPROVED", decided.status());
        assertEquals("SUCCESS", decided.operationStatus());
        assertEquals(1, downstream.submits.get());

        var repeated =
                approvals.decide(
                        APPROVER,
                        new ApprovalParam(pending.approvalId(), "APPROVE", "重复批准"));
        assertEquals("APPROVED", repeated.status());
        assertEquals(1, downstream.submits.get());
        assertEquals(1, downstream.orders.get());
    }

    @Test
    void rejectionNeverCallsDownstreamAndLeavesNoOperationEvidence() {
        BookingOutcomeDTO pending = approvals.booking(RUN, APPLICANT, OPERATION, PARAMETERS);
        var rejected =
                approvals.decide(
                        APPROVER,
                        new ApprovalParam(pending.approvalId(), "REJECT", "乘客信息不完整"));
        assertEquals("REJECTED", rejected.status());
        assertEquals(0, downstream.submits.get());
        assertThrows(
                ResponseStatusException.class,
                () -> approvals.operation(APPLICANT, ApprovalService.TOOL, OPERATION));
    }

    @Test
    void applicantCannotApproveOwnRequest() {
        BookingOutcomeDTO pending = approvals.booking(RUN, APPLICANT, OPERATION, PARAMETERS);
        ResponseStatusException denied =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                approvals.decide(
                                        APPLICANT,
                                        new ApprovalParam(
                                                pending.approvalId(), "APPROVE", "自行批准")));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals(
                "PENDING",
                approvals.get(APPLICANT, pending.approvalId()).status());
        assertEquals(0, downstream.submits.get());
    }

    @Test
    void sameOperationWithDifferentParametersIsRejectedBeforeExecution() {
        BookingOutcomeDTO pending = approvals.booking(RUN, APPLICANT, OPERATION, PARAMETERS);
        BookingOutcomeDTO conflict =
                approvals.booking(
                        RUN,
                        APPLICANT,
                        OPERATION,
                        Map.of("from", "杭州", "to", "北京", "date", "2026-11-10", "passenger", "张三"));
        assertEquals("FAILED", conflict.status());
        assertEquals("APPROVAL_PARAMETER_CONFLICT", conflict.message());
        assertEquals(0, downstream.submits.get());

        approvals.decide(
                APPROVER, new ApprovalParam(pending.approvalId(), "APPROVE", "核对通过"));
        assertEquals(1, downstream.submits.get());

        BookingOutcomeDTO afterApproval =
                approvals.booking(
                        RUN,
                        APPLICANT,
                        OPERATION,
                        Map.of("from", "杭州", "to", "北京", "date", "2026-11-10", "passenger", "张三"));
        assertEquals("FAILED", afterApproval.status());
        assertEquals("APPROVAL_PARAMETER_CONFLICT", afterApproval.message());
        assertEquals(1, downstream.submits.get());
    }

    @Test
    void unknownResultRecordsOneSubmitAndOnlyUsesLookupForRecovery() {
        downstream.unknown = true;
        BookingOutcomeDTO pending = approvals.booking(RUN, APPLICANT, OPERATION, PARAMETERS);
        var decided =
                approvals.decide(
                        APPROVER,
                        new ApprovalParam(pending.approvalId(), "APPROVE", "结果未知"));
        assertEquals("UNKNOWN", decided.operationStatus());
        assertEquals(1, downstream.submits.get());
        assertTrue(downstream.lookups.get() >= 1);

        var evidence = approvals.operation(APPLICANT, ApprovalService.TOOL, OPERATION);
        assertEquals("UNKNOWN", evidence.status());
        assertEquals(1, evidence.submitAttempts());
        assertTrue(evidence.lookupAttempts() >= 1);

        approvals.booking(RUN, APPLICANT, OPERATION, PARAMETERS);
        assertEquals(1, downstream.submits.get());
    }

    /** 记录调用次数的测试替身；订单数单独计数，避免把重放查询当成新写入。 */
    private static final class CountingDownstream implements SideEffectLedger.Downstream {
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicInteger orders = new AtomicInteger();
        volatile boolean unknown;

        @Override
        public boolean supportsIdempotency() {
            return true;
        }

        @Override
        public SideEffectOutcomeDTO submit(String operationId, Map<String, Object> parameters) {
            submits.incrementAndGet();
            if (unknown) {
                return new SideEffectOutcomeDTO(SideEffectOutcomeStatus.UNKNOWN, null);
            }
            orders.incrementAndGet();
            return new SideEffectOutcomeDTO(
                    SideEffectOutcomeStatus.SUCCESS, "{\"orderId\":\"local-order\"}");
        }

        @Override
        public SideEffectOutcomeDTO lookup(String operationId) {
            lookups.incrementAndGet();
            return unknown
                    ? new SideEffectOutcomeDTO(SideEffectOutcomeStatus.UNKNOWN, null)
                    : new SideEffectOutcomeDTO(
                            SideEffectOutcomeStatus.SUCCESS, "{\"orderId\":\"local-order\"}");
        }
    }
}
