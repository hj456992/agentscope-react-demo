package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class SideEffectLedgerTest {
    @TempDir Path directory;
    static final AccessPolicy.Identity ALICE = identity("tenant", "alice", true);

    static AccessPolicy.Identity identity(String tenant, String user, boolean allowed) {
        return new AccessPolicy.Identity(
                tenant, user, allowed ? Set.of("write_example") : Set.of());
    }

    String url(String name) {
        return "jdbc:h2:file:" + directory.resolve(name) + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    @Test
    void concurrentDuplicateCallsCommitOneActualEffectAndCanonicalOrderMatches() throws Exception {
        try (var ledger = new SideEffectLedger(url("ledger"));
                var downstream = new DurableDownstream(url("external"))) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            downstream.started = started;
            downstream.release = release;
            var pool = Executors.newFixedThreadPool(8);
            try {
                var first =
                        pool.submit(
                                () ->
                                        ledger.execute(
                                                ALICE,
                                                "write_example",
                                                "operation",
                                                Map.of(
                                                        "amount",
                                                        8,
                                                        "nested",
                                                        Map.of("b", 2, "a", 1)),
                                                downstream));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var duplicates = new ArrayList<Future<SideEffectLedger.Result>>();
                for (int i = 0; i < 20; i++)
                    duplicates.add(
                            pool.submit(
                                    () ->
                                            ledger.execute(
                                                    ALICE,
                                                    "write_example",
                                                    "operation",
                                                    Map.of(
                                                            "nested",
                                                            Map.of("a", 1, "b", 2),
                                                            "amount",
                                                            8),
                                                    downstream)));
                for (var duplicate : duplicates)
                    assertEquals(
                            SideEffectLedger.Status.RUNNING,
                            duplicate.get(5, TimeUnit.SECONDS).status());
                release.countDown();
                assertEquals(
                        SideEffectLedger.Status.SUCCESS, first.get(5, TimeUnit.SECONDS).status());
                assertEquals(
                        "receipt",
                        ledger.execute(
                                        ALICE,
                                        "write_example",
                                        "operation",
                                        Map.of("amount", 8, "nested", Map.of("b", 2, "a", 1)),
                                        downstream)
                                .response());
                assertEquals(1, downstream.effectCount());
                assertEquals(1, downstream.submitCalls.get());
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void responseLostAfterExternalCommitIsResolvedWithoutResubmitting() throws Exception {
        try (var ledger = new SideEffectLedger(url("ledger"));
                var downstream = new DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            assertEquals(
                    SideEffectLedger.Status.SUCCESS,
                    ledger.execute(ALICE, "write_example", "lost", Map.of("amount", 8), downstream)
                            .status());
            assertEquals(1, downstream.effectCount());
            assertEquals(1, downstream.submitCalls.get());
        }
    }

    @Test
    void uncertainWritesNeverResubmitAndReconciliationHasPersistentAttemptAndRateBounds()
            throws Exception {
        var clock = new MutableClock();
        try (var ledger = new SideEffectLedger(url("ledger"), clock);
                var downstream = new DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            downstream.lookupUnknown = true;
            assertEquals(
                    SideEffectLedger.Status.UNKNOWN,
                    ledger.execute(
                                    ALICE,
                                    "write_example",
                                    "unknown",
                                    Map.of("amount", 8),
                                    downstream)
                            .status());
            for (int i = 0; i < 20; i++)
                ledger.execute(ALICE, "write_example", "unknown", Map.of("amount", 8), downstream);
            assertEquals(1, downstream.lookupCalls.get());
            for (int i = 0; i < 20; i++) {
                clock.advance();
                ledger.reconcile(Map.of("write_example", downstream), 10);
            }
            assertEquals(8, downstream.lookupCalls.get());
            assertEquals(1, downstream.submitCalls.get());
            assertEquals(1, downstream.effectCount());
        }
    }

    @Test
    void conflictingParametersAndRevokedPermissionCannotReplayOrWrite() throws Exception {
        try (var ledger = new SideEffectLedger(url("ledger"));
                var downstream = new DurableDownstream(url("external"))) {
            ledger.execute(ALICE, "write_example", "id", Map.of("amount", 8), downstream);
            assertThrows(
                    SideEffectLedger.ParameterConflict.class,
                    () ->
                            ledger.execute(
                                    ALICE, "write_example", "id", Map.of("amount", 9), downstream));
            var revoked = identity("tenant", "alice", false);
            assertThrows(
                    SecurityException.class,
                    () ->
                            ledger.execute(
                                    revoked,
                                    "write_example",
                                    "id",
                                    Map.of("amount", 8),
                                    downstream));
            assertThrows(
                    SecurityException.class, () -> ledger.read(revoked, "write_example", "id"));
            assertEquals(1, downstream.effectCount());
        }
    }

    @Test
    void sameOperationIdIsIsolatedByTenantAndUserAndSurvivesReopen() throws Exception {
        try (var downstream = new DurableDownstream(url("external"))) {
            try (var ledger = new SideEffectLedger(url("ledger"))) {
                for (var who :
                        List.of(
                                ALICE,
                                identity("tenant", "bob", true),
                                identity("other", "alice", true))) {
                    assertEquals(
                            SideEffectLedger.Status.SUCCESS,
                            ledger.execute(
                                            who,
                                            "write_example",
                                            "same-id",
                                            Map.of("amount", 8),
                                            downstream)
                                    .status());
                }
            }
            try (var ledger = new SideEffectLedger(url("ledger"))) {
                assertEquals(
                        SideEffectLedger.Status.SUCCESS,
                        ledger.execute(
                                        ALICE,
                                        "write_example",
                                        "same-id",
                                        Map.of("amount", 8),
                                        downstream)
                                .status());
            }
            assertEquals(3, downstream.effectCount());
            assertEquals(3, downstream.submitCalls.get());
        }
    }

    @Test
    void processCrashAfterExternalCommitRecoversByLookupInAnotherJvm() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        Path crashLog = directory.resolve("crash.log");
        var child =
                new ProcessBuilder(
                                javaExecutable,
                                "-cp",
                                cp,
                                SideEffectCrashProcess.class.getName(),
                                url("ledger"),
                                url("external"))
                        .redirectErrorStream(true)
                        .redirectOutput(crashLog.toFile())
                        .start();
        assertTrue(child.waitFor(20, TimeUnit.SECONDS));
        assertEquals(23, child.exitValue(), java.nio.file.Files.readString(crashLog));
        try (var ledger = new SideEffectLedger(url("ledger"));
                var downstream = new DurableDownstream(url("external"))) {
            assertEquals(
                    1, downstream.effectCount(), "external commit must survive the process halt");
            assertEquals(
                    SideEffectLedger.Status.UNKNOWN,
                    ledger.read(ALICE, "write_example", "crash").status());
            assertEquals(1, ledger.reconcile(Map.of("write_example", downstream), 1));
            assertEquals(
                    SideEffectLedger.Status.SUCCESS,
                    ledger.read(ALICE, "write_example", "crash").status());
            assertEquals(1, downstream.effectCount());
            assertEquals(0, downstream.submitCalls.get());
        }
    }

    @Test
    void onlyAuthoritativeNotExecutedAllowsBoundedRetryAndScheduledChecksNeverSubmit()
            throws Exception {
        var clock = new MutableClock();
        try (var ledger = new SideEffectLedger(url("ledger"), clock);
                var downstream = new DurableDownstream(url("external"))) {
            downstream.rejectBeforeExecution = true;
            assertEquals(
                    SideEffectLedger.Status.UNKNOWN,
                    ledger.execute(ALICE, "write_example", "retry", Map.of("amount", 8), downstream)
                            .status());
            clock.advance();
            ledger.reconcile(Map.of("write_example", downstream), 1);
            assertEquals(1, downstream.submitCalls.get());
            for (int i = 0; i < 10; i++) {
                clock.advance();
                ledger.execute(ALICE, "write_example", "retry", Map.of("amount", 8), downstream);
            }
            assertEquals(3, downstream.submitCalls.get());
            assertEquals(0, downstream.effectCount());
        }
    }

    @Test
    void failedBusinessOutcomeIsTerminalAndUnsafeAdapterCannotSubmit() throws Exception {
        try (var ledger = new SideEffectLedger(url("ledger"));
                var downstream = new DurableDownstream(url("external"))) {
            downstream.idempotent = false;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ledger.execute(ALICE, "write_example", "unsafe", Map.of(), downstream));
            assertEquals(0, downstream.effectCount());
            downstream.idempotent = true;
            downstream.businessRejected = true;
            assertEquals(
                    SideEffectLedger.Status.FAILED,
                    ledger.execute(ALICE, "write_example", "rejected", Map.of(), downstream)
                            .status());
            ledger.execute(ALICE, "write_example", "rejected", Map.of(), downstream);
            assertEquals(1, downstream.submitCalls.get());
            assertEquals(0, downstream.lookupCalls.get());
        }
    }

    @Test
    void reconciliationAcrossConcurrentCallersNeverExceedsOneExternalLookup() throws Exception {
        var clock = new MutableClock();
        try (var ledger = new SideEffectLedger(url("ledger"), clock);
                var downstream = new DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            downstream.lookupUnknown = true;
            ledger.execute(ALICE, "write_example", "a", Map.of(), downstream);
            ledger.execute(ALICE, "write_example", "b", Map.of(), downstream);
            clock.advance();
            downstream.lookupStarted = new CountDownLatch(1);
            downstream.lookupRelease = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first =
                        pool.submit(
                                () ->
                                        ledger.execute(
                                                ALICE, "write_example", "a", Map.of(), downstream));
                assertTrue(downstream.lookupStarted.await(5, TimeUnit.SECONDS));
                var second =
                        pool.submit(
                                () ->
                                        ledger.execute(
                                                ALICE, "write_example", "b", Map.of(), downstream));
                assertEquals(
                        SideEffectLedger.Status.UNKNOWN, second.get(1, TimeUnit.SECONDS).status());
                downstream.lookupRelease.countDown();
                first.get(5, TimeUnit.SECONDS);
                assertEquals(1, downstream.maximumActiveLookups.get());
            } finally {
                downstream.lookupRelease.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void interruptedSubmitPersistsUnknownBeforeRestoringInterruptionAndCanReconcileLater()
            throws Exception {
        try (var ledger = new SideEffectLedger(url("ledger"));
                var downstream = new DurableDownstream(url("external"))) {
            downstream.interruptAfterCommit = true;
            try {
                assertEquals(
                        SideEffectLedger.Status.UNKNOWN,
                        ledger.execute(ALICE, "write_example", "interrupted", Map.of(), downstream)
                                .status());
                assertTrue(
                        Thread.interrupted(),
                        "interruption must be restored after the durable save");
                assertEquals(0, downstream.lookupCalls.get());
                assertEquals(1, ledger.reconcile(Map.of("write_example", downstream), 10));
                assertEquals(
                        SideEffectLedger.Status.SUCCESS,
                        ledger.read(ALICE, "write_example", "interrupted").status());
                assertEquals(1, downstream.effectCount());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void lookupBudgetSurvivesReopenAndBatchLimitBoundsActualQueries() throws Exception {
        var clock = new MutableClock();
        try (var downstream = new DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            downstream.lookupUnknown = true;
            try (var ledger = new SideEffectLedger(url("ledger"), clock)) {
                ledger.execute(ALICE, "write_example", "one", Map.of(), downstream);
                ledger.execute(ALICE, "write_example", "two", Map.of(), downstream);
                clock.advance();
                int before = downstream.lookupCalls.get();
                assertEquals(1, ledger.reconcile(Map.of("write_example", downstream), 1));
                assertEquals(before + 1, downstream.lookupCalls.get());
            }
            try (var ledger = new SideEffectLedger(url("ledger"), clock)) {
                for (int i = 0; i < 20; i++) {
                    clock.advance();
                    ledger.reconcile(Map.of("write_example", downstream), 2);
                }
                assertEquals(16, downstream.lookupCalls.get());
                assertEquals(2, downstream.submitCalls.get());
                assertThrows(IllegalArgumentException.class, () -> ledger.reconcile(Map.of(), 101));
            }
        }
    }

    @Test
    void unavailableAdaptersCannotStarveLaterRegisteredOperations() throws Exception {
        var clock = new MutableClock();
        var identity =
                new AccessPolicy.Identity(
                        "tenant", "alice", Set.of("removed_tool", "write_example"));
        try (var ledger = new SideEffectLedger(url("ledger"), clock);
                var downstream = new DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            downstream.lookupUnknown = true;
            for (int i = 0; i < 21; i++)
                ledger.execute(identity, "removed_tool", "unavailable-" + i, Map.of(), downstream);
            clock.advance();
            ledger.execute(identity, "write_example", "registered-later", Map.of(), downstream);
            clock.advance();
            downstream.lookupUnknown = false;
            int before = downstream.lookupCalls.get();
            assertEquals(1, ledger.reconcile(Map.of("write_example", downstream), 20));
            assertEquals(before + 1, downstream.lookupCalls.get());
            assertEquals(
                    SideEffectLedger.Status.SUCCESS,
                    ledger.read(identity, "write_example", "registered-later").status());
            for (int i = 0; i < 21; i++)
                assertEquals(
                        SideEffectLedger.Status.UNKNOWN,
                        ledger.read(identity, "removed_tool", "unavailable-" + i).status());
            assertEquals(22, downstream.effectCount());
            assertEquals(22, downstream.submitCalls.get());
        }
    }

    static final class MutableClock extends Clock {
        long millis = 1_000;

        void advance() {
            millis += 2_000;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    /** 独立数据库、独立事务：即使账本提交失败，下游效果仍然存在。 */
    static final class DurableDownstream implements SideEffectLedger.Downstream, AutoCloseable {
        final Connection database;
        final AtomicInteger submitCalls = new AtomicInteger(), lookupCalls = new AtomicInteger();
        volatile boolean loseResponse,
                lookupUnknown,
                crash,
                rejectBeforeExecution,
                businessRejected,
                interruptAfterCommit;
        volatile boolean idempotent = true;
        final AtomicInteger activeLookups = new AtomicInteger(),
                maximumActiveLookups = new AtomicInteger();
        volatile CountDownLatch lookupStarted, lookupRelease;
        volatile CountDownLatch started, release;

        DurableDownstream(String url) throws SQLException {
            database = DriverManager.getConnection(url);
            database.createStatement().execute("SET WRITE_DELAY 0");
            database.createStatement()
                    .execute(
                            "CREATE TABLE IF NOT EXISTS actual_effects (operation_id VARCHAR"
                                    + " PRIMARY KEY, receipt VARCHAR NOT NULL)");
        }

        public boolean supportsIdempotency() {
            return idempotent;
        }

        public SideEffectLedger.Outcome submit(String operationId, Map<String, Object> parameters)
                throws Exception {
            submitCalls.incrementAndGet();
            if (rejectBeforeExecution) throw new java.io.IOException("no write accepted");
            if (businessRejected)
                return new SideEffectLedger.Outcome(
                        SideEffectLedger.OutcomeStatus.FAILED, "rejected");
            if (started != null) {
                started.countDown();
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("test latch timed out");
            }
            synchronized (database) {
                try (var statement =
                        database.prepareStatement("INSERT INTO actual_effects VALUES (?,?)")) {
                    statement.setString(1, operationId);
                    statement.setString(2, "receipt");
                    try {
                        statement.executeUpdate();
                    } catch (SQLException ex) {
                        if (!"23505".equals(ex.getSQLState())) throw ex;
                    }
                }
            }
            if (crash) Runtime.getRuntime().halt(23);
            if (interruptAfterCommit) throw new InterruptedException("interrupted after commit");
            if (loseResponse) throw new java.io.IOException("response lost after durable commit");
            return SideEffectLedger.Outcome.success("receipt");
        }

        public SideEffectLedger.Outcome lookup(String operationId) throws Exception {
            lookupCalls.incrementAndGet();
            if (lookupStarted != null) {
                int active = activeLookups.incrementAndGet();
                maximumActiveLookups.accumulateAndGet(active, Math::max);
                lookupStarted.countDown();
                try {
                    if (!lookupRelease.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("lookup test latch timed out");
                } finally {
                    activeLookups.decrementAndGet();
                }
            }
            if (lookupUnknown)
                return new SideEffectLedger.Outcome(SideEffectLedger.OutcomeStatus.UNKNOWN, null);
            synchronized (database) {
                try (var statement =
                        database.prepareStatement(
                                "SELECT receipt FROM actual_effects WHERE operation_id=?")) {
                    statement.setString(1, operationId);
                    try (var rows = statement.executeQuery()) {
                        return rows.next()
                                ? SideEffectLedger.Outcome.success(rows.getString(1))
                                : new SideEffectLedger.Outcome(
                                        SideEffectLedger.OutcomeStatus.CONFIRMED_NOT_EXECUTED,
                                        null);
                    }
                }
            }
        }

        int effectCount() throws SQLException {
            synchronized (database) {
                try (var rows =
                        database.createStatement()
                                .executeQuery("SELECT COUNT(*) FROM actual_effects")) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        }

        public void close() throws SQLException {
            database.close();
        }
    }
}
