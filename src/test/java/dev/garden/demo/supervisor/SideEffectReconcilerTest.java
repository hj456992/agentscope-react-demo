package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.service.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.config.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class SideEffectReconcilerTest {
    @TempDir Path directory;

    String url(String name) {
        return "jdbc:h2:file:" + directory.resolve(name) + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    @Test
    void startupReconcilesUnknownWriteWithoutAnActiveTaskAndNeverSubmitsAgain() throws Exception {
        var clock = new SideEffectLedgerTest.MutableClock();
        try (var ledger = new TestLedger(url("ledger"), clock);
                var downstream = new SideEffectLedgerTest.DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            downstream.lookupUnknown = true;
            ledger.execute(
                    SideEffectLedgerTest.ALICE, "write_example", "detached", Map.of(), downstream);
            downstream.lookupUnknown = false;
            clock.advance();
            downstream.lookupStarted = new CountDownLatch(1);
            downstream.lookupRelease = new CountDownLatch(1);
            try (var reconciler =
                    new SideEffectReconciler(ledger.ledger(), Map.of("write_example", downstream))) {
                assertTrue(downstream.lookupStarted.await(5, TimeUnit.SECONDS));
                assertEquals(
                        SideEffectStatus.UNKNOWN,
                        ledger.read(SideEffectLedgerTest.ALICE, "write_example", "detached")
                                .status());
                downstream.lookupRelease.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (ledger.read(SideEffectLedgerTest.ALICE, "write_example", "detached").status()
                                != SideEffectStatus.SUCCESS
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(
                        SideEffectStatus.SUCCESS,
                        ledger.read(SideEffectLedgerTest.ALICE, "write_example", "detached")
                                .status());
                assertEquals(1, downstream.effectCount());
                assertEquals(1, downstream.submitCalls.get());
            } finally {
                downstream.lookupRelease.countDown();
            }
        }
    }

    @Test
    void closeDoesNotSpawnReplacementWorkersWhenLookupIgnoresInterruption() throws Exception {
        var clock = new SideEffectLedgerTest.MutableClock();
        try (var ledger = new TestLedger(url("ledger"), clock);
                var downstream = new SideEffectLedgerTest.DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            downstream.lookupUnknown = true;
            ledger.execute(
                    SideEffectLedgerTest.ALICE, "write_example", "detached", Map.of(), downstream);
            clock.advance();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var lookups = new AtomicInteger();
            SideEffectLedger.Downstream ignoresInterrupt =
                    new SideEffectLedger.Downstream() {
                        public boolean supportsIdempotency() {
                            return true;
                        }

                        public SideEffectOutcomeDTO submit(
                                String id, Map<String, Object> parameters) {
                            fail("reconciliation must never submit");
                            return null;
                        }

                        public SideEffectOutcomeDTO lookup(String id) throws Exception {
                            lookups.incrementAndGet();
                            entered.countDown();
                            while (true) {
                                try {
                                    release.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                }
                            }
                            try {
                                return downstream.lookup(id);
                            } finally {
                                exited.countDown();
                            }
                        }
                    };
            var reconciler =
                    new SideEffectReconciler(ledger.ledger(), Map.of("write_example", ignoresInterrupt));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(1), reconciler::close);
                Thread.sleep(1_100);
                assertEquals(1, lookups.get());
                release.countDown();
                assertTrue(exited.await(5, TimeUnit.SECONDS));
                assertEquals(1, downstream.effectCount());
            } finally {
                release.countDown();
                reconciler.close();
            }
        }
    }

    @Test
    void emptyAdapterRegistryDoesNotQueryOrReplayUnknownWrites() throws Exception {
        var clock = new SideEffectLedgerTest.MutableClock();
        try (var ledger = new TestLedger(url("ledger"), clock);
                var downstream = new SideEffectLedgerTest.DurableDownstream(url("external"))) {
            downstream.loseResponse = true;
            downstream.lookupUnknown = true;
            ledger.execute(
                    SideEffectLedgerTest.ALICE, "write_example", "detached", Map.of(), downstream);
            clock.advance();
            try (var reconciler = new SideEffectReconciler(ledger.ledger(), Map.of())) {
                Thread.sleep(50);
                assertEquals(1, downstream.lookupCalls.get());
                assertEquals(1, downstream.submitCalls.get());
                assertEquals(
                        SideEffectStatus.UNKNOWN,
                        ledger.read(SideEffectLedgerTest.ALICE, "write_example", "detached")
                                .status());
            }
        }
    }
}
