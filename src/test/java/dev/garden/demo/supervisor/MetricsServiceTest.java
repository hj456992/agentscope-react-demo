package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.repo.GovernanceRepository;
import dev.garden.demo.supervisor.service.ApprovalService;
import dev.garden.demo.supervisor.service.MetricsService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

/** 指标服务：空库返回零值，告警来自真实采集而不是手工汇总。 */
class MetricsServiceTest {
    @TempDir Path directory;
    private Database database;
    private MetricsService metrics;

    @BeforeEach
    void setUp() {
        database = TestServices.database("jdbc:h2:file:" + directory.resolve("metrics"));
        var tasks = TestServices.taskRepository(database);
        var governance = new GovernanceRepository(database);
        var ledger = TestServices.sideEffectLedger(database, Clock.systemUTC());
        var approvals =
                new ApprovalService(governance, ledger, null, null, Clock.systemUTC());
        metrics = new MetricsService(tasks, governance, approvals, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void emptyDatabaseReportsZeroWithoutInventingSamples() {
        var snapshot = metrics.collect();
        assertEquals(0, snapshot.tasks());
        assertEquals(0, snapshot.running());
        assertEquals(0, snapshot.completed());
        assertEquals(0.0, snapshot.failureRate());
        assertEquals(0.0, snapshot.p95DurationMs());
        assertEquals(0, snapshot.modelCalls());
        assertEquals(0, snapshot.promptTokens());
        assertEquals(0, snapshot.completionTokens());
        assertEquals(0, snapshot.toolCalls());
        assertEquals(0, snapshot.approvalsPending());
        assertEquals(0, snapshot.approvalsDecided());
        assertTrue(snapshot.byState().isEmpty());
    }

    @Test
    void alertsAreDerivedFromCollectedValuesAndKeepOpenState() {
        var snapshot = metrics.collect();
        assertFalse(snapshot.alerts().isEmpty());
        assertTrue(snapshot.alerts().stream()
                .anyMatch(alert -> alert.kind().equals("task_failure_rate")
                        && alert.metricValue() == 0.0));
        assertEquals(
                snapshot.alerts().stream().filter(alert -> alert.status().equals("OPEN")).count(),
                snapshot.alertsOpen());
    }
}
