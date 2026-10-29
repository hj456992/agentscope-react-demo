package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.config.SupervisorSettings;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.InstructionParam;
import dev.garden.demo.supervisor.service.TaskService;
import dev.garden.demo.supervisor.service.TaskStoreService;

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

/** 固定源码 V2 契约：取消父轮次不关闭子会话，完成后仍可追加任务。 */
class AgentLifecycleTest {
    private static ResultDTO answer(String text) {
        return new ResultDTO(
                Status.SUCCESS, text, "", Intent.UNKNOWN, text, List.of(), List.of(), List.of());
    }

    private static SupervisorSettings settings(String url) {
        return new SupervisorSettings(
                "offline",
                Duration.ofSeconds(30),
                8,
                1024,
                8,
                8,
                100,
                100,
                90000,
                1000,
                Duration.ofDays(7),
                Duration.ofDays(30),
                Duration.ofDays(90),
                url,
                "");
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
                var service =
                        TestServices.taskService(
                                repository,
                                settings(url),
                                (request, control) -> {
                                    if (control.agentPath().equals("/root")) {
                                        parentControl.set(control);
                                        parentStarted.countDown();
                                        return Mono.never();
                                    }
                                    childControl.set(control);
                                    childStarted.countDown();
                                    return childEnd.asMono();
                                })) {
            var started =
                    service.submit(
                                    IdentityDTO.local(),
                                    new RequestDTO("session", "delegate", List.of(), "root"),
                                    true)
                            .block();
            assertTrue(parentStarted.await(2, TimeUnit.SECONDS));
            var child =
                    parentControl
                            .get()
                            .spawnAgent("worker", "research", "weather_agent", "none", Map.of())
                            .block();
            assertTrue(childStarted.await(2, TimeUnit.SECONDS));
            service.instruct(
                            IdentityDTO.local(),
                            new InstructionParam(started.runId(), "stop", 1, Action.CANCEL, null))
                    .block();
            assertDoesNotThrow(() -> childControl.get().reserveModel(1));
            childEnd.tryEmitValue(answer("child completed"));
            awaitStatus(parentControl.get(), child.agentName(), "completed");
            assertEquals(
                    "CANCELLED", service.get(IdentityDTO.local(), started.runId()).block().state());
            assertEquals("child completed", parentControl.get().drainMailbox().get(0).message());
        }
    }

    @Test
    void queueOnlyDoesNotWakeCompletedChildButFollowupReusesSession() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 10, 1024));
        var calls = new AtomicInteger();
        var observed = new AtomicReference<List<AgentMessageDTO>>();
        root.bindRunner(
                (request, control) -> {
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
        root.bindRunner(
                (request, control) -> Mono.<ResultDTO>never().doOnCancel(cancelled::countDown));
        var child = root.spawnAgent("worker", "first", "weather_agent", "none", Map.of()).block();
        var previous = root.interruptAgent("worker").block();
        assertEquals("running", previous.agentStatus());
        awaitStatus(root, child.agentName(), "interrupted");
        assertTrue(root.drainMailbox().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> root.interruptAgent("/root").block());
        assertThrows(
                IllegalArgumentException.class,
                () -> root.sendMessage("/root", "new", true).block());
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
            try (var first =
                    TestServices.taskService(
                            repository,
                            settings(url),
                            (request, control) -> {
                                if (control.agentPath().equals("/root")) {
                                    root.set(control);
                                    parentEntered.countDown();
                                } else {
                                    control.checkpoint("retained", "saved child context");
                                    childEntered.countDown();
                                }
                                return Mono.never();
                            })) {
                var task =
                        first.submit(
                                        IdentityDTO.local(),
                                        new RequestDTO("restart", "delegate", List.of(), "once"),
                                        true)
                                .block();
                assertTrue(parentEntered.await(2, TimeUnit.SECONDS));
                childId.set(
                        root.get()
                                .spawnAgent(
                                        "worker", "child task", "weather_agent", "none", Map.of())
                                .block()
                                .agentId());
                assertTrue(childEntered.await(2, TimeUnit.SECONDS));
                first.instruct(
                                IdentityDTO.local(),
                                new InstructionParam(task.runId(), "stop", 1, Action.CANCEL, null))
                        .block();
            }
            var resumed = new CountDownLatch(1);
            var recoveredRole = new AtomicReference<String>();
            try (var second =
                    TestServices.taskService(
                            repository,
                            settings(url),
                            (request, control) -> {
                                recoveredRole.set(control.agentRole());
                                assertEquals("saved child context", control.checkpoint("retained"));
                                resumed.countDown();
                                return Mono.just(answer("recovered"));
                            })) {
                assertTrue(resumed.await(3, TimeUnit.SECONDS));
                assertEquals("weather_agent", recoveredRole.get());
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (repository.get(childId.get()).state.equals("RUNNING")
                        && System.nanoTime() < until) {
                    Thread.sleep(5);
                }
                assertEquals("SUCCESS", repository.get(childId.get()).state);
                assertEquals(
                        1,
                        repository.all().stream()
                                .filter(t -> t.parentRunId == null)
                                .findFirst()
                                .orElseThrow()
                                .mailbox
                                .size());
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
            TaskStoreService store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url)) {
                        @Override
                        public TaskDO acceptChild(String id) {
                            TaskDO task = super.acceptChild(id);
                            acceptedWrite.countDown();
                            try {
                                if (!release.await(3, TimeUnit.SECONDS)) {
                                    throw new AssertionError(
                                            "test did not release acceptance boundary");
                                }
                            } catch (InterruptedException error) {
                                throw new AssertionError(error);
                            }
                            return task;
                        }
                    };
            try (var service =
                    new TaskService(
                            store,
                            settings(url),
                            (request, control) -> {
                                if (control.agentPath().equals("/root")) {
                                    root.set(control);
                                    entered.countDown();
                                } else {
                                    childCalls.incrementAndGet();
                                }
                                return Mono.never();
                            },
                            null)) {
                service.submit(
                                IdentityDTO.local(),
                                new RequestDTO("cancel-spawn", "delegate", List.of(), "once"),
                                true)
                        .block();
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var subscription =
                        root.get()
                                .spawnAgent(
                                        "worker", "child task", "weather_agent", "none", Map.of())
                                .subscribe();
                assertTrue(acceptedWrite.await(2, TimeUnit.SECONDS));
                subscription.dispose();
                release.countDown();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                TaskDO child;
                do {
                    child =
                            repository.all().stream()
                                    .filter(t -> t.parentRunId != null)
                                    .findFirst()
                                    .orElseThrow();
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
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var task =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("ack-input", "first", List.of(), "once"),
                            () -> {});
            store.applyInstruction(
                    IdentityDTO.local(),
                    new InstructionParam(task.runId, "one", 1, Action.SUPPLEMENT, "answer one"),
                    () -> {});
            store.applyInstruction(
                    IdentityDTO.local(),
                    new InstructionParam(task.runId, "two", 2, Action.SUPPLEMENT, "answer two"),
                    () -> {});
            var delivered = store.drainInputs(task.runId, task.generation);
            assertEquals(List.of("answer one", "answer two"), delivered.inputs());
            // 构造阶段保存旧模型上下文不能确认尚未重放的输入。
            var resumed = store.claimExecution(task.runId);
            store.checkpointWithDeliveryAck(
                    task.runId, resumed.generation, 3, "loop:v3.3", "old context", 0, 0);
            var replay = store.drainInputs(task.runId, resumed.generation);
            assertEquals(List.of("answer one", "answer two"), replay.inputs());
            store.checkpointWithDeliveryAck(
                    task.runId,
                    resumed.generation,
                    3,
                    "loop:v3.3",
                    "context containing both answers",
                    replay.deliveryId(),
                    0);
            assertTrue(store.drainInputs(task.runId, resumed.generation).inputs().isEmpty());
        }
    }

    @Test
    void mailboxReplayIsAcknowledgedAtomicallyWithoutChangingPublicInputRevision() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var root =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("ack-mail", "first", List.of(), "once"),
                            () -> {});
            var child =
                    store.prepareChild(
                            root.runId,
                            root.generation,
                            "worker",
                            "task",
                            "weather_agent",
                            "none",
                            Map.of());
            child = store.acceptChild(child.runId);
            store.sendAgent(root.runId, "worker", "message one", false);
            var batch = store.deliverMailbox(child.runId, child.generation);
            assertEquals("message one", batch.messages().get(0).message());
            assertEquals(1, store.get(child.runId).revision);
            var resumed = store.claimExecution(child.runId);
            store.checkpointWithDeliveryAck(
                    child.runId, resumed.generation, 1, "loop:v3.3", "old context", 0, 0);
            var replay = store.deliverMailbox(child.runId, resumed.generation);
            assertEquals("message one", replay.messages().get(0).message());
            store.checkpointWithDeliveryAck(
                    child.runId,
                    resumed.generation,
                    1,
                    "loop:v3.3",
                    "context containing message",
                    0,
                    replay.deliveryId());
            assertTrue(store.deliverMailbox(child.runId, resumed.generation).messages().isEmpty());
        }
    }

    @Test
    void cancelledRootContextIsCarriedIntoNextHttpRunWithoutCopyingExecutionState() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var first =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("resume-context", "first", List.of(), "one"),
                            () -> {});
            store.checkpoint(
                    first.runId,
                    first.generation,
                    1,
                    "loop:v3.3",
                    "completed tool messages and pending call");
            store.checkpoint(
                    first.runId, first.generation, 1, "unrelated-execution-cache", "must not copy");
            store.applyInstruction(
                    IdentityDTO.local(),
                    new InstructionParam(first.runId, "cancel", 1, Action.CANCEL, null),
                    () -> {});
            var second =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("resume-context", "continue", List.of(), "two"),
                            () -> {});
            assertNotEquals(first.runId, second.runId);
            assertEquals(
                    "completed tool messages and pending call",
                    second.checkpoints.get("loop:v3.3"));
            assertEquals("continue", second.checkpoints.get("agent:resume-user-input"));
            assertTrue(second.checkpoints.containsKey("agent:interrupted"));
            assertFalse(second.checkpoints.containsKey("unrelated-execution-cache"));
        }
    }

    @Test
    void fullParentMailboxRetainsCompletionForRetry() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var root =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("full-mail", "first", List.of(), "one"),
                            () -> {});
            var child =
                    store.acceptChild(
                            store.prepareChild(
                                            root.runId,
                                            root.generation,
                                            "worker",
                                            "task",
                                            "weather_agent",
                                            "none",
                                            Map.of())
                                    .runId);
            for (int i = 0; i < 512; i++) {
                root.mailbox.add(
                        new AgentMessageDTO(
                                "/root/other",
                                "/root",
                                "MESSAGE",
                                "queued " + i,
                                null,
                                java.util.Set.of()));
            }
            repository.save(root);
            assertTrue(
                    store.commitResult(
                            child.runId, child.generation, 1, 0, answer("retained result")));
            assertEquals(0, store.get(child.runId).notifiedGeneration);
            var batch = store.deliverMailbox(root.runId, root.generation);
            store.checkpointWithDeliveryAck(
                    root.runId,
                    root.generation,
                    1,
                    "loop:v3.3",
                    "all queued messages",
                    0,
                    batch.deliveryId());
            store.retryPendingNotifications();
            assertEquals("retained result", store.get(root.runId).mailbox.get(0).message());
            assertEquals(child.generation, store.get(child.runId).notifiedGeneration);
            store.retryPendingNotifications();
            assertEquals(1, store.get(root.runId).mailbox.size());
        }
    }

    @Test
    void pendingCompletionsSurviveFollowupAndPersistentReload() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var root =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("two-turns", "first", List.of(), "one"),
                            () -> {});
            var child =
                    store.acceptChild(
                            store.prepareChild(
                                            root.runId,
                                            root.generation,
                                            "worker",
                                            "task",
                                            "weather_agent",
                                            "none",
                                            Map.of())
                                    .runId);
            for (int i = 0; i < 512; i++) {
                root.mailbox.add(
                        new AgentMessageDTO(
                                "/root/other",
                                "/root",
                                "MESSAGE",
                                "queued " + i,
                                null,
                                java.util.Set.of()));
            }
            repository.save(root);
            assertTrue(
                    store.commitResult(
                            child.runId, child.generation, 1, 0, answer("first result")));
            store.sendAgent(root.runId, "worker", "next task", true);
            child = store.claimExecution(child.runId);
            assertTrue(
                    store.commitResult(
                            child.runId, child.generation, 1, 1, answer("second result")));
            var restored =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var batch = restored.deliverMailbox(root.runId, root.generation);
            restored.checkpointWithDeliveryAck(
                    root.runId,
                    root.generation,
                    1,
                    "loop:v3.3",
                    "queued messages saved",
                    0,
                    batch.deliveryId());
            restored.retryPendingNotifications();
            assertEquals(
                    List.of("first result", "second result"),
                    restored.get(root.runId).mailbox.stream()
                            .map(AgentMessageDTO::message)
                            .toList());
            restored.retryPendingNotifications();
            assertEquals(2, restored.get(root.runId).mailbox.size());
            assertEquals(child.generation, restored.get(child.runId).notifiedGeneration);
        }
    }

    @Test
    void localPendingCompletionsSurviveFollowup() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 10, 1024));
        var rounds = new AtomicInteger();
        root.bindRunner(
                (request, control) -> Mono.just(answer("result " + rounds.incrementAndGet())));
        for (int i = 0; i < 512; i++) {
            root.sendMessage("/root", "queued " + i, false).block();
        }
        var child =
                root.spawnAgent("worker", "first task", "weather_agent", "none", Map.of()).block();
        awaitStatus(root, child.agentName(), "completed");
        root.sendMessage("worker", "second task", true).block();
        awaitStatus(root, child.agentName(), "completed");
        assertEquals(512, root.drainMailbox().size());
        assertEquals(
                List.of("result 1", "result 2"),
                root.drainMailbox().stream().map(AgentMessageDTO::message).toList());
        assertTrue(root.drainMailbox().isEmpty());
    }

    @Test
    void localFollowupRejectsFullChildCapacityBeforeEnqueue() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 10, 1024));
        var idleControl = new AtomicReference<RunControl>();
        root.bindRunner(
                (request, control) -> {
                    idleControl.set(control);
                    return Mono.just(answer("idle"));
                });
        var idle = root.spawnAgent("idle", "first", "weather_agent", "none", Map.of()).block();
        awaitStatus(root, idle.agentName(), "completed");
        root.bindRunner((request, control) -> Mono.never());
        try {
            for (int i = 0; i < AgentLifecycle.MAX_ACTIVE_CHILDREN; i++) {
                root.spawnAgent("active" + i, "work", "weather_agent", "none", Map.of()).block();
            }
            assertThrows(
                    RunControl.BudgetExceeded.class,
                    () -> root.sendMessage("idle", "must not enqueue", true).block());
            assertTrue(idleControl.get().drainMailbox().isEmpty());
            assertEquals("completed", root.listAgents("idle").get(0).agentStatus());
        } finally {
            for (int i = 0; i < AgentLifecycle.MAX_ACTIVE_CHILDREN; i++) {
                root.interruptAgent("active" + i).block();
            }
            root.interruptAgent("idle").block();
        }
    }

    @Test
    void collaborationReceiptsPreventDuplicateSpawnAndFollowup() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 10, 1024));
        var calls = new AtomicInteger();
        root.bindRunner(
                (request, control) -> {
                    calls.incrementAndGet();
                    return Mono.just(answer("done"));
                });
        var first =
                root.spawnAgent("spawn-call", "worker", "task", "weather_agent", "none", Map.of())
                        .block();
        awaitStatus(root, first.agentName(), "completed");
        var repeated =
                root.spawnAgent("spawn-call", "worker", "task", "weather_agent", "none", Map.of())
                        .block();
        assertEquals(first.agentId(), repeated.agentId());
        assertEquals(1, calls.get());
        root.sendMessage("followup-call", "worker", "another", true).block();
        awaitStatus(root, first.agentName(), "completed");
        root.sendMessage("followup-call", "worker", "another", true).block();
        assertEquals(2, calls.get());
    }

    @Test
    void persistentOperationReceiptSurvivesExecutionGenerationChange() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var root =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("receipt", "first", List.of(), "one"),
                            () -> {});
            var child =
                    store.prepareChild(
                            root.runId,
                            root.generation,
                            "worker",
                            "task",
                            "weather_agent",
                            "none",
                            Map.of());
            String spawnFingerprint =
                    store.operationFingerprint("spawn", "worker", "task", "weather_agent", "none");
            child =
                    store.acceptChildWithReceipt(
                            child.runId, root.runId, "spawn-call", spawnFingerprint);
            var restored = store.claimExecution(root.runId);
            assertEquals(root.agentTurnId, restored.agentTurnId);
            assertEquals(
                    child.runId,
                    store.operationReceipt(root.runId, "spawn-call", spawnFingerprint)
                            .result()
                            .agentId());
            String sendFingerprint = store.operationFingerprint("send", "worker", "message", false);
            store.sendAgentWithReceipt(
                    root.runId, "message-call", sendFingerprint, "worker", "message", false);
            store.claimExecution(root.runId);
            store.sendAgentWithReceipt(
                    root.runId, "message-call", sendFingerprint, "worker", "message", false);
            assertEquals(1, store.get(child.runId).mailbox.size());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void exhaustedSharedBudgetEndsWaitAfterLastChildStops(boolean consumeCallLimit)
            throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        var parent = new AtomicReference<RunControl>();
        var ready = new CountDownLatch(1);
        try (var repository = new TestRepository(url);
                var service =
                        TestServices.taskService(
                                repository,
                                settings(url),
                                (request, control) -> {
                                    if (control.agentPath().equals("/root")) {
                                        parent.set(control);
                                        ready.countDown();
                                        return Mono.never();
                                    }
                                    return Mono.fromCallable(
                                            () -> {
                                                if (!consumeCallLimit) {
                                                    // 分钟额度可先耗尽，此时整树调用次数仍有余额。
                                                    control.reserveModel(
                                                            settings(url).modelTokensPerMinute()
                                                                    + 1);
                                                }
                                                for (int i = 0;
                                                        i <= settings(url).maxModelCalls();
                                                        i++) {
                                                    control.reserveModel(1);
                                                }
                                                return answer("unreachable");
                                            });
                                })) {
            service.submit(
                            IdentityDTO.local(),
                            new RequestDTO("budget-wait", "task", List.of(), "one"),
                            true)
                    .block();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            var child =
                    parent.get()
                            .spawnAgent("worker", "task", "weather_agent", "none", Map.of())
                            .block();
            awaitStatus(parent.get(), child.agentName(), "interrupted");
            if (!consumeCallLimit) {
                assertTrue(parent.get().modelCalls() < settings(url).maxModelCalls());
            }
            assertTrue(parent.get().drainMailbox().isEmpty());
            assertThrows(RunControl.BudgetExceeded.class, () -> parent.get().waitAgent(50).block());
        }
    }

    @Test
    void localExhaustedBudgetDoesNotBecomeCompletionOrTimeout() {
        RunControl root = RunControl.local(new LimitsDTO(Duration.ofSeconds(20), 1, 1024));
        root.bindRunner(
                (request, control) ->
                        Mono.fromCallable(
                                () -> {
                                    control.reserveModel(1);
                                    control.reserveModel(1);
                                    return answer("unreachable");
                                }));
        var child = root.spawnAgent("worker", "task", "weather_agent", "none", Map.of()).block();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (root.listAgents("worker").get(0).agentStatus().equals("running")
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals("interrupted", root.listAgents("worker").get(0).agentStatus());
        assertTrue(root.drainMailbox().isEmpty());
        assertThrows(RunControl.BudgetExceeded.class, () -> root.waitAgent(50).block());
    }

    @Test
    void completionCarriesHistoricalPermissionsWithoutCurrentEvidence() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var root =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("history-rights", "task", List.of(), "one"),
                            () -> {});
            var child =
                    store.acceptChild(
                            store.prepareChild(
                                            root.runId,
                                            root.generation,
                                            "worker",
                                            "task",
                                            "weather_agent",
                                            "none",
                                            Map.of())
                                    .runId);
            store.checkpoint(
                    child.runId,
                    child.generation,
                    1,
                    "loop:v3.3",
                    "{\"requiredPermissions\":[\"query_weather\"]}");
            store.commitResult(
                    child.runId, child.generation, 1, 0, answer("answer from old weather history"));
            String payload = repository.encode(store.get(root.runId).mailbox.get(0));
            assertTrue(
                    payload.contains("query_weather"),
                    "completion must retain permission provenance even without current evidence");
        }
    }

    @Test
    void completedHttpViewAndIdempotentReplayCheckHistoricalPermissions() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var request = new RequestDTO("history-view", "task", List.of(), "one");
            var root = store.create(IdentityDTO.local(), request, () -> {});
            store.checkpoint(
                    root.runId,
                    root.generation,
                    1,
                    "loop:v3.3",
                    "{\"requiredPermissions\":[\"query_weather\"]}");
            store.commitResult(
                    root.runId, root.generation, 1, 0, answer("answer from old weather history"));
            var revoked = new IdentityDTO("local", "cli", java.util.Set.of());
            var readError =
                    assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () -> store.view(revoked, store.visible(revoked, root.runId)));
            assertEquals(403, readError.getStatusCode().value());
            var repeated = store.create(revoked, request, () -> {});
            var replayError =
                    assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () -> store.view(revoked, repeated));
            assertEquals(403, replayError.getStatusCode().value());
        }
    }

    @Test
    void outboxScanPreservesMailboxWrittenByEarlierDescendant() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var store =
                    new TaskStoreService(
                            repository.repository(),
                            TestServices.conversationService(repository.repository()),
                            settings(url));
            var root =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("nested-outbox", "task", List.of(), "one"),
                            () -> {});
            var branch =
                    store.acceptChild(
                            store.prepareChild(
                                            root.runId,
                                            root.generation,
                                            "branch",
                                            "task",
                                            "weather_agent",
                                            "none",
                                            Map.of())
                                    .runId);
            var leaf =
                    store.acceptChild(
                            store.prepareChild(
                                            branch.runId,
                                            branch.generation,
                                            "leaf",
                                            "task",
                                            "weather_agent",
                                            "none",
                                            Map.of())
                                    .runId);
            for (int i = 0; i < 512; i++) {
                root.mailbox.add(
                        new AgentMessageDTO(
                                "/root/other",
                                "/root",
                                "MESSAGE",
                                "queued",
                                null,
                                java.util.Set.of()));
                branch.mailbox.add(
                        new AgentMessageDTO(
                                "/root/other",
                                branch.agentPath,
                                "MESSAGE",
                                "queued",
                                null,
                                java.util.Set.of()));
            }
            repository.save(root);
            repository.save(branch);
            store.commitResult(leaf.runId, leaf.generation, 1, 0, answer("leaf result"));
            store.commitResult(branch.runId, branch.generation, 1, 0, answer("branch result"));
            branch = store.get(branch.runId);
            branch.mailbox.clear();
            repository.save(branch);
            // Wall-clock timestamps can tie or move backwards; delivery must not rely on ancestor
            // scan order.
            leaf = store.get(leaf.runId);
            leaf.createdAt = branch.createdAt - 1;
            repository.save(leaf);
            store.retryPendingNotifications();
            assertEquals(
                    List.of("leaf result"),
                    store.get(branch.runId).mailbox.stream()
                            .map(AgentMessageDTO::message)
                            .toList());
        }
    }

    @Test
    void serverConversationPermissionsSurviveAnswerReuseAcrossHttpTasks() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url)) {
            var conversations = TestServices.conversationService(repository.repository());
            var store = new TaskStoreService(repository.repository(), conversations, settings(url));
            var first =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("permission-chain", "first", List.of(), "a"),
                            () -> {});
            ResultDTO observed =
                    new ResultDTO(
                            Status.SUCCESS,
                            "first",
                            "",
                            Intent.UNKNOWN,
                            "secret-original",
                            List.of(),
                            List.of(
                                    new EvidenceDTO(
                                            "query_weather", Status.SUCCESS, "weather", Map.of())),
                            List.of());
            store.commitResult(first.runId, first.generation, 1, 0, observed);
            var second =
                    store.create(
                            IdentityDTO.local(),
                            new RequestDTO("permission-chain", "second", List.of(), "b"),
                            () -> {});
            assertTrue(
                    AgentLifecycle.requiredPermissions(second.checkpoints, null)
                            .contains("query_weather"));
            assertTrue(
                    second.request.history().stream()
                            .anyMatch(t -> t.answer().contains("secret-original")));
            store.commitResult(
                    second.runId,
                    second.generation,
                    1,
                    0,
                    answer("secret-derived-without-new-evidence"));
            var revoked = new IdentityDTO("local", "cli", java.util.Set.of());
            var third =
                    store.create(
                            revoked,
                            new RequestDTO("permission-chain", "third", List.of(), "c"),
                            () -> {});
            String history = repository.encode(third.request.history());
            assertFalse(history.contains("secret-original"));
            assertFalse(history.contains("secret-derived-without-new-evidence"));
            assertTrue(history.contains("权限已撤销"));
            assertTrue(
                    AgentLifecycle.requiredPermissions(third.checkpoints, null).isEmpty(),
                    "redacted answers must not add permissions for content that was not included");
        }
    }

    @Test
    void legacyAgentPayloadsDeriveMissingPermissionsFromResultEvidence() {
        ResultDTO result =
                new ResultDTO(
                        Status.SUCCESS,
                        "question",
                        "",
                        Intent.UNKNOWN,
                        "secret",
                        List.of(),
                        List.of(
                                new EvidenceDTO(
                                        "query_weather", Status.SUCCESS, "weather", Map.of())),
                        List.of());
        var codec = io.agentscope.core.util.JsonUtils.getJsonCodec();
        String messageJson =
                dev.garden.demo.supervisor.support.Contracts.json(
                        Map.of(
                                "sender",
                                "/root/worker",
                                "recipient",
                                "/root",
                                "kind",
                                "RESULT",
                                "message",
                                "secret",
                                "result",
                                result));
        AgentMessageDTO message = codec.fromJson(messageJson, AgentMessageDTO.class);
        assertEquals(java.util.Set.of("query_weather"), message.requiredPermissions());
        String snapshotJson =
                dev.garden.demo.supervisor.support.Contracts.json(
                        Map.of(
                                "agentId",
                                "id",
                                "agentName",
                                "/root/worker",
                                "agentRole",
                                "weather_agent",
                                "agentStatus",
                                "completed",
                                "lastAnswer",
                                "secret",
                                "result",
                                result));
        AgentSnapshotDTO snapshot = codec.fromJson(snapshotJson, AgentSnapshotDTO.class);
        assertEquals(java.util.Set.of("query_weather"), snapshot.requiredPermissions());
    }

    private static void awaitStatus(RunControl root, String name, String status) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            if (root.listAgents(null).stream()
                    .anyMatch(a -> a.agentName().equals(name) && a.agentStatus().equals(status))) {
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
