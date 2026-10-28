package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.config.SupervisorSettings;
import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.InstructionParam;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.service.TaskStoreService;
import dev.garden.demo.supervisor.service.TaskService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 固定源码 V2 契约：取消父轮次不关闭子会话，完成后仍可追加任务。 */
class AgentLifecycleTest {
    private static ResultDTO answer(String text) {
        return new ResultDTO(Status.SUCCESS, text, "", Intent.UNKNOWN, text,
                List.of(), List.of(), List.of());
    }

    private static SupervisorSettings settings(String url) {
        return new SupervisorSettings("offline", Duration.ofSeconds(30), 8, 1024,
                8, 8, 100, 100, 90000, 1000, Duration.ofDays(7),
                Duration.ofDays(30), Duration.ofDays(90), url, "");
    }

    @Test
    void acceptedChildSurvivesParentCancellationAndRetainsSharedBudget() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        var parentControl = new AtomicReference<RunControl>();
        var childControl = new AtomicReference<RunControl>();
        var parentStarted = new CountDownLatch(1);
        var childStarted = new CountDownLatch(1);
        var childEnd = Sinks.<ResultDTO>one();
        try (var repository = new TestRepository(url);
                var service = TestServices.taskService(repository, settings(url), (request, control) -> {
                    if (control.agentPath().equals("/root")) {
                        parentControl.set(control);
                        parentStarted.countDown();
                        return Mono.never();
                    }
                    childControl.set(control);
                    childStarted.countDown();
                    return childEnd.asMono();
                })) {
            var started = service.submit(IdentityDTO.local(),
                    new RequestDTO("session", "delegate", List.of(), "root"), true).block();
            assertTrue(parentStarted.await(2, TimeUnit.SECONDS));
            var child = parentControl.get().spawnAgent("worker", "research", "weather_agent",
                    "none", Map.of()).block();
            assertTrue(childStarted.await(2, TimeUnit.SECONDS));
            service.instruct(IdentityDTO.local(), new InstructionParam(started.runId(), "stop", 1,
                    Action.CANCEL, null)).block();
            assertDoesNotThrow(() -> childControl.get().reserveModel(1));
            childEnd.tryEmitValue(answer("child completed"));
            awaitStatus(parentControl.get(), child.agentName(), "completed");
            assertEquals("CANCELLED", service.get(IdentityDTO.local(), started.runId()).block().state());
            assertEquals("child completed", parentControl.get().drainMailbox().get(0).message());
        }
    }

    @Test
    void queueOnlyDoesNotWakeCompletedChildButFollowupReusesSession() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 10, 1024));
        var calls = new AtomicInteger();
        var observed = new AtomicReference<List<AgentMessageDTO>>();
        root.bindRunner((request, control) -> {
            calls.incrementAndGet();
            observed.set(control.drainMailbox());
            control.checkpoint("remember", "persistent fact");
            return Mono.just(answer(control.checkpoint("remember")));
        });
        var spawned = root.spawnAgent("worker", "first", "weather_agent", "none", Map.of()).block();
        awaitStatus(root, spawned.agentName(), "completed");
        root.drainMailbox();
        root.sendMessage("worker", "ordinary message", false).block();
        assertEquals(1, calls.get());
        root.sendMessage("worker", "second task", true).block();
        awaitStatus(root, spawned.agentName(), "completed");
        assertEquals(2, calls.get());
        assertTrue(observed.get().stream().anyMatch(m -> m.message().equals("ordinary message")));
        assertEquals("persistent fact", root.drainMailbox().get(0).message());
    }

    @Test
    void waitObservesAlreadyPendingMessageWithoutConsumingIt() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 10, 1024));
        root.bindRunner((request, control) -> Mono.just(answer("done")));
        var child = root.spawnAgent("worker", "first", "weather_agent", "none", Map.of()).block();
        awaitStatus(root, child.agentName(), "completed");
        assertFalse(root.waitAgent(100).block().timedOut());
        assertEquals(1, root.drainMailbox().size());
        assertTrue(root.waitAgent(20).block().timedOut());
    }

    @Test
    void interruptPreservesSessionAndDoesNotSendCompletion() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 10, 1024));
        var cancelled = new CountDownLatch(1);
        root.bindRunner((request, control) -> Mono.<ResultDTO>never().doOnCancel(cancelled::countDown));
        var child = root.spawnAgent("worker", "first", "weather_agent", "none", Map.of()).block();
        var previous = root.interruptAgent("worker").block();
        assertEquals("running", previous.agentStatus());
        awaitStatus(root, child.agentName(), "interrupted");
        assertTrue(root.drainMailbox().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> root.interruptAgent("/root").block());
        assertThrows(IllegalArgumentException.class, () -> root.sendMessage("/root", "new", true).block());
        root.bindRunner((request, control) -> Mono.just(answer("restarted")));
        root.sendMessage("worker", "again", true).block();
        awaitStatus(root, child.agentName(), "completed");
        assertEquals("restarted", root.drainMailbox().get(0).message());
    }

    @Test
    void restartRecoversAcceptedChildWhileCancelledParentStaysStopped() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var root = new AtomicReference<RunControl>();
        var parentEntered = new CountDownLatch(1);
        var childEntered = new CountDownLatch(1);
        var childId = new AtomicReference<String>();
        try (var repository = new TestRepository(url)) {
            try (var first = TestServices.taskService(repository, settings(url), (request, control) -> {
                if (control.agentPath().equals("/root")) {
                    root.set(control);
                    parentEntered.countDown();
                } else {
                    control.checkpoint("retained", "saved child context");
                    childEntered.countDown();
                }
                return Mono.never();
            })) {
                var task = first.submit(IdentityDTO.local(),
                        new RequestDTO("restart", "delegate", List.of(), "once"), true).block();
                assertTrue(parentEntered.await(2, TimeUnit.SECONDS));
                childId.set(root.get().spawnAgent("worker", "child task", "weather_agent", "none", Map.of())
                        .block().agentId());
                assertTrue(childEntered.await(2, TimeUnit.SECONDS));
                first.instruct(IdentityDTO.local(), new InstructionParam(task.runId(), "stop", 1,
                        Action.CANCEL, null)).block();
            }
            var resumed = new CountDownLatch(1);
            var recoveredRole = new AtomicReference<String>();
            try (var second = TestServices.taskService(repository, settings(url), (request, control) -> {
                recoveredRole.set(control.agentRole());
                assertEquals("saved child context", control.checkpoint("retained"));
                resumed.countDown();
                return Mono.just(answer("recovered"));
            })) {
                assertTrue(resumed.await(3, TimeUnit.SECONDS));
                assertEquals("weather_agent", recoveredRole.get());
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (repository.get(childId.get()).state.equals("RUNNING") && System.nanoTime() < until) {
                    Thread.sleep(5);
                }
                assertEquals("SUCCESS", repository.get(childId.get()).state);
                assertEquals(1, repository.all().stream().filter(t -> t.parentRunId == null)
                        .findFirst().orElseThrow().mailbox.size());
            }
        }
    }

    @Test
    void cancellationBeforeAcceptanceReceiptDiscardsDurableProvisionalChild() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        var root = new AtomicReference<RunControl>();
        var entered = new CountDownLatch(1);
        var acceptedWrite = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var childCalls = new AtomicInteger();
        try (var repository = new TestRepository(url)) {
            TaskStoreService store = new TaskStoreService(repository.repository(),
                    TestServices.conversationService(repository.repository()), settings(url)) {
                @Override
                public TaskDO acceptChild(String id) {
                    TaskDO task = super.acceptChild(id);
                    acceptedWrite.countDown();
                    try {
                        if (!release.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release acceptance boundary");
                        }
                    } catch (InterruptedException error) {
                        throw new AssertionError(error);
                    }
                    return task;
                }
            };
            try (var service = new TaskService(store, settings(url), (request, control) -> {
                if (control.agentPath().equals("/root")) {
                    root.set(control);
                    entered.countDown();
                } else {
                    childCalls.incrementAndGet();
                }
                return Mono.never();
            }, null)) {
                service.submit(IdentityDTO.local(), new RequestDTO("cancel-spawn", "delegate", List.of(), "once"), true)
                        .block();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var subscription = root.get().spawnAgent("worker", "child task", "weather_agent", "none", Map.of())
                        .subscribe();
                assertTrue(acceptedWrite.await(2, TimeUnit.SECONDS));
                subscription.dispose();
                release.countDown();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                TaskDO child;
                do {
                    child = repository.all().stream().filter(t -> t.parentRunId != null).findFirst().orElseThrow();
                    if (child.state.equals("CLOSED")) {
                        break;
                    }
                    Thread.sleep(5);
                } while (System.nanoTime() < until);
                assertEquals("CLOSED", child.state);
                assertFalse(child.accepted);
                assertEquals(0, childCalls.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void inputsRemainReplayableUntilConversationCheckpointAcknowledgesTheirBatch() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store = new TaskStoreService(repository.repository(),
                    TestServices.conversationService(repository.repository()), settings(url));
            var task = store.create(IdentityDTO.local(),
                    new RequestDTO("ack-input", "first", List.of(), "once"), () -> {});
            store.applyInstruction(IdentityDTO.local(), new InstructionParam(task.runId, "one", 1,
                    Action.SUPPLEMENT, "answer one"), () -> {});
            store.applyInstruction(IdentityDTO.local(), new InstructionParam(task.runId, "two", 2,
                    Action.SUPPLEMENT, "answer two"), () -> {});
            var delivered = store.drainInputs(task.runId, task.generation);
            assertEquals(List.of("answer one", "answer two"), delivered.inputs());
            // 构造阶段保存旧模型上下文不能确认尚未重放的输入。
            var resumed = store.claimExecution(task.runId);
            store.checkpointWithDeliveryAck(task.runId, resumed.generation, 3,
                    "loop:v3.3", "old context", 0, 0);
            var replay = store.drainInputs(task.runId, resumed.generation);
            assertEquals(List.of("answer one", "answer two"), replay.inputs());
            store.checkpointWithDeliveryAck(task.runId, resumed.generation, 3,
                    "loop:v3.3", "context containing both answers", replay.deliveryId(), 0);
            assertTrue(store.drainInputs(task.runId, resumed.generation).inputs().isEmpty());
        }
    }

    @Test
    void mailboxReplayIsAcknowledgedAtomicallyWithoutChangingPublicInputRevision() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store = new TaskStoreService(repository.repository(),
                    TestServices.conversationService(repository.repository()), settings(url));
            var root = store.create(IdentityDTO.local(),
                    new RequestDTO("ack-mail", "first", List.of(), "once"), () -> {});
            var child = store.prepareChild(root.runId, root.generation, "worker", "task", "weather_agent", "none", Map.of());
            child = store.acceptChild(child.runId);
            store.sendAgent(root.runId, "worker", "message one", false);
            var batch = store.deliverMailbox(child.runId, child.generation);
            assertEquals("message one", batch.messages().get(0).message());
            assertEquals(1, store.get(child.runId).revision);
            var resumed = store.claimExecution(child.runId);
            store.checkpointWithDeliveryAck(child.runId, resumed.generation, 1,
                    "loop:v3.3", "old context", 0, 0);
            var replay = store.deliverMailbox(child.runId, resumed.generation);
            assertEquals("message one", replay.messages().get(0).message());
            store.checkpointWithDeliveryAck(child.runId, resumed.generation, 1,
                    "loop:v3.3", "context containing message", 0, replay.deliveryId());
            assertTrue(store.deliverMailbox(child.runId, resumed.generation).messages().isEmpty());
        }
    }

    private static void awaitStatus(RunControl root, String name, String status) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            if (root.listAgents(null).stream().anyMatch(a -> a.agentName().equals(name)
                    && a.agentStatus().equals(status))) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }
        fail("Agent did not reach " + status);
    }
}
