package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class TaskRuntimeTest {
    @TempDir Path directory;
    private final AccessPolicy.Identity identity = AccessPolicy.Identity.local();

    private SupervisorSettings settings(String url, int calls) {
        return settings(url, calls, 1000);
    }

    private SupervisorSettings settings(String url, int calls, int maxStoredTasks) {
        return new SupervisorSettings(
                "offline",
                Duration.ofSeconds(20),
                calls,
                1024,
                2,
                1,
                10,
                120,
                90000,
                maxStoredTasks,
                Duration.ofDays(7),
                Duration.ofDays(30),
                Duration.ofDays(90),
                url,
                "test=t:u:query_weather");
    }

    private TaskRepository repo(String url) {
        return new TaskRepository(url, new ObjectMapper());
    }

    private Request request(String session, String id) {
        return new Request(session, "杭州明天天气", List.of(), id);
    }

    private Result success(String text) {
        return new Result(
                Status.SUCCESS, text, text, Intent.WEATHER, text, List.of(), List.of(), List.of());
    }

    private TaskService.View waitDone(TaskService service, String id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        TaskService.View view;
        do {
            view = service.get(identity, id).block();
            if (!view.state().equals("RUNNING")) return view;
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        throw new AssertionError("任务未结束");
    }

    @Test
    void queuedSteeringKeepsSubscriptionAndBudgetAndFencesOldCompletion() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        var first = Sinks.<Result>one();
        var entered = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var captured = new AtomicReference<RunControl>();
        var invocations = new AtomicInteger();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) ->
                                        Mono.defer(
                                                () -> {
                                                    captured.set(control);
                                                    control.reserveModel(10);
                                                    String input = control.drainInput();
                                                    if (invocations.incrementAndGet() == 1) {
                                                        entered.countDown();
                                                        return first.asMono()
                                                                .doOnCancel(
                                                                        () -> cancelled.set(true));
                                                    }
                                                    return Mono.just(success(input));
                                                }))) {
            var started = service.submit(identity, request("session", "request"), true).block();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var change =
                    new TaskService.Instruction(
                            "instruction", 1, TaskService.Action.REVISE, "上海后天天气");
            var reply = service.instruct(identity, started.runId(), change).block();
            assertEquals(started.runId(), reply.runId());
            assertEquals(2, reply.revision());
            assertEquals(1, captured.get().revision());
            assertFalse(cancelled.get());
            assertEquals(reply, service.instruct(identity, started.runId(), change).block());
            first.tryEmitValue(success("杭州旧答案"));
            var done = waitDone(service, started.runId());
            assertEquals("上海后天天气", done.result().answer());
            assertEquals(2, done.modelCalls());
            assertEquals(started.deadline(), done.deadline());
            assertEquals(2, captured.get().revision());
        }
    }

    @Test
    void explicitCancelStopsOwnedExecutionWhileDisconnectDoesNot() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) ->
                                        Mono.<Result>never()
                                                .doOnSubscribe(x -> entered.countDown())
                                                .doOnCancel(cancelled::countDown))) {
            var waiting =
                    service.submit(identity, request("session", "request"), false).subscribe();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            waiting.dispose();
            assertFalse(cancelled.await(50, TimeUnit.MILLISECONDS));
            var task = repo.all().get(0);
            assertEquals("RUNNING", task.state);
            var conflict =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    service.submit(identity, request("session", "another"), true)
                                            .block());
            assertEquals(409, conflict.getStatusCode().value());
            service.instruct(
                            identity,
                            task.runId,
                            new TaskService.Instruction(
                                    "cancel", 1, TaskService.Action.CANCEL, null))
                    .block();
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
            assertEquals("CANCELLED", service.get(identity, task.runId).block().state());
        }
    }

    @Test
    void restartPreservesCheckpointsConsumedCallsAndOriginalDeadline() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("recovery");
        String id;
        long deadline;
        var entered = new CountDownLatch(1);
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 2),
                                (request, control) ->
                                        Mono.defer(
                                                () -> {
                                                    control.reserveModel(100);
                                                    control.checkpoint("step:weather", "saved");
                                                    entered.countDown();
                                                    return Mono.never();
                                                }))) {
            var started = service.submit(identity, request("session", "request"), true).block();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            id = started.runId();
            deadline = started.deadline();
        }
        var resumed = new CountDownLatch(1);
        var proof = new AtomicReference<String>();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 2),
                                (request, control) ->
                                        Mono.defer(
                                                () -> {
                                                    proof.set(
                                                            control.checkpoint("step:weather")
                                                                    + ":"
                                                                    + control.modelCalls());
                                                    control.reserveModel(100);
                                                    resumed.countDown();
                                                    return Mono.just(success("恢复完成"));
                                                }))) {
            assertTrue(resumed.await(4, TimeUnit.SECONDS));
            var done = waitDone(service, id);
            assertEquals("saved:1", proof.get());
            assertEquals(2, done.modelCalls());
            assertEquals(deadline, done.deadline());
        }
    }

    @Test
    void globalReservationFailureDoesNotDebitTaskOrResetAfterReopen() {
        String url = "jdbc:h2:file:" + directory.resolve("windows");
        try (var repo = repo(url)) {
            repo.reserve("global", 1, 80, 2, 100);
            assertThrows(
                    RunControl.BudgetExceeded.class,
                    () ->
                            repo.transaction(
                                    c -> {
                                        repo.reserve("global", 1, 10, 2, 100);
                                        throw new RunControl.BudgetExceeded("later check");
                                    }));
        }
        try (var repo = repo(url)) {
            repo.reserve("global", 1, 20, 2, 100);
            assertThrows(
                    RunControl.BudgetExceeded.class, () -> repo.reserve("global", 1, 1, 2, 100));
        }
    }

    @Test
    void cancelledTaskKeepsCompletedEvidence() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var captured = new AtomicReference<RunControl>();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) ->
                                        Mono.defer(
                                                () -> {
                                                    captured.set(control);
                                                    Result progress =
                                                            new Result(
                                                                    Status.PARTIAL,
                                                                    request.question(),
                                                                    request.question(),
                                                                    Intent.WEATHER,
                                                                    "天气已完成",
                                                                    List.of("weather"),
                                                                    List.of(
                                                                            new Evidence(
                                                                                    "query_weather",
                                                                                    Status.SUCCESS,
                                                                                    "fixture",
                                                                                    Map.of(
                                                                                            "city",
                                                                                            "杭州"))),
                                                                    List.of());
                                                    control.checkpoint(
                                                            "progress", repo.encode(progress));
                                                    entered.countDown();
                                                    return Mono.never();
                                                }))) {
            var started = service.submit(identity, request("session", "request"), true).block();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(
                    1, service.get(identity, started.runId()).block().result().evidence().size());
            service.instruct(
                            identity,
                            started.runId(),
                            new TaskService.Instruction(
                                    "cancel", 1, TaskService.Action.CANCEL, null))
                    .block();
            assertEquals(
                    1, service.get(identity, started.runId()).block().result().evidence().size());
        }
    }

    @Test
    void modelStepReservationCannotPartiallyDebitWhenStepIsExhausted() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var captured = new AtomicReference<RunControl>();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 2),
                                (request, control) -> {
                                    captured.set(control);
                                    entered.countDown();
                                    return Mono.never();
                                })) {
            service.submit(identity, request("session", "request"), true).block();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            RunControl control = captured.get();
            control.reserveModelStep("model:rewrite", 100, 1);
            assertThrows(
                    RunControl.BudgetExceeded.class,
                    () -> control.reserveModelStep("model:rewrite", 100, 1));
            assertEquals(1, control.modelCalls());
            assertEquals("1", control.checkpoint("model:rewrite"));
            control.reserveModelStep("model:intent", 100, 1);
            assertEquals(2, control.modelCalls());
            assertThrows(
                    RunControl.BudgetExceeded.class,
                    () -> control.reserveModelStep("model:answer", 100, 1));
            assertNull(control.checkpoint("model:answer"));
        }
    }

    @Test
    void resumedTaskUsesCurrentPolicyInsteadOfStoredPermissionSnapshot() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("policy");
        var entered = new CountDownLatch(1);
        String runId;
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> {
                                    entered.countDown();
                                    return Mono.never();
                                })) {
            runId = service.submit(identity, request("session", "request"), true).block().runId();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
        }
        AccessPolicy changed = new AccessPolicy("new-token=local:cli:query_trains");
        var actual = new AtomicReference<AccessPolicy.Identity>();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> {
                                    actual.set(control.identity());
                                    return Mono.just(success("恢复"));
                                },
                                changed)) {
            assertEquals("SUCCESS", waitDone(service, runId).state());
            assertFalse(actual.get().allows("query_weather"));
            assertTrue(actual.get().allows("query_trains"));
        }
    }

    @Test
    void revokedToolPermissionBlocksCompletedResultAndRequestReplay() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        Result evidence =
                new Result(
                        Status.SUCCESS,
                        "query",
                        "query",
                        Intent.WEATHER,
                        "weather-secret",
                        List.of(),
                        List.of(
                                new Evidence(
                                        "query_weather",
                                        Status.SUCCESS,
                                        "source",
                                        Map.of("city", "杭州"))),
                        List.of());
        var restricted =
                new AccessPolicy.Identity(
                        identity.tenantId(), identity.userId(), Set.of("query_trains"));
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> Mono.just(evidence))) {
            Request original = request("session", "request");
            String id = service.submit(identity, original, false).block().runId();
            assertEquals(
                    403,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> service.get(restricted, id).block())
                            .getStatusCode()
                            .value());
            assertEquals(
                    403,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> service.submit(restricted, original, false).block())
                            .getStatusCode()
                            .value());
            assertEquals(
                    403,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> service.submit(restricted, original, true).block())
                            .getStatusCode()
                            .value());
        }
    }

    @Test
    void emptyExecutionResultBecomesFailureInsteadOfUnboundedRecovery() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> Mono.empty())) {
            String id =
                    service.submit(identity, request("session", "request"), true).block().runId();
            assertEquals("FAILED", waitDone(service, id).state());
        }
    }

    @Test
    void currentSessionFenceDoesNotDependOnWallClockOrdering() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        Result needsInput =
                new Result(
                        Status.NEEDS_INPUT,
                        "query",
                        "query",
                        Intent.WEATHER,
                        "请补充",
                        List.of(),
                        List.of(),
                        List.of());
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> Mono.just(needsInput))) {
            String first =
                    service.submit(identity, request("session", "first"), false).block().runId();
            String second =
                    service.submit(identity, request("session", "second"), false).block().runId();
            // 同毫秒或墙上时钟调整都不能让旧任务覆盖会话当前任务。
            var oldTask = repo.get(first);
            var nextTask = repo.get(second);
            nextTask.createdAt = oldTask.createdAt;
            repo.save(nextTask);
            assertEquals(
                    409,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            service.instruct(
                                                            identity,
                                                            first,
                                                            new TaskService.Instruction(
                                                                    "late",
                                                                    1,
                                                                    TaskService.Action.SUPPLEMENT,
                                                                    "上海后天天气"))
                                                    .block())
                            .getStatusCode()
                            .value());
        }
    }

    @Test
    void switchedTaskRequestIdReplaysThroughTheChatContract() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        Result needsInput =
                new Result(
                        Status.NEEDS_INPUT,
                        "query",
                        "query",
                        Intent.WEATHER,
                        "请补充",
                        List.of(),
                        List.of(),
                        List.of());
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> Mono.just(needsInput))) {
            String first =
                    service.submit(identity, request("session", "first"), false).block().runId();
            String next =
                    service.instruct(
                                    identity,
                                    first,
                                    new TaskService.Instruction(
                                            "switch", 1, TaskService.Action.SWITCH, "上海后天天气"))
                            .block()
                            .runId();
            TaskService.View replacement = service.get(identity, next).block();
            TaskService.View replay =
                    service.submit(
                                    identity,
                                    new Request(
                                            "session",
                                            "上海后天天气",
                                            List.of(),
                                            replacement.requestId()),
                                    false)
                            .block();
            assertEquals(next, replay.runId());
            assertEquals("REPLACED", service.get(identity, first).block().state());
        }
    }

    @Test
    void queuedInputAggregateLimitRejectsWithoutChangingAcceptedRevision() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> Mono.never())) {
            String id = service.submit(identity, request("session", "first"), true).block().runId();
            service.instruct(
                            identity,
                            id,
                            new TaskService.Instruction(
                                    "one", 1, TaskService.Action.SUPPLEMENT, "甲".repeat(1200)))
                    .block();
            ResponseStatusException rejected =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    service.instruct(
                                                    identity,
                                                    id,
                                                    new TaskService.Instruction(
                                                            "two",
                                                            2,
                                                            TaskService.Action.SUPPLEMENT,
                                                            "乙".repeat(1200)))
                                            .block());
            assertEquals(413, rejected.getStatusCode().value());
            assertEquals(2, service.get(identity, id).block().revision());
            assertEquals(List.of("甲".repeat(1200)), repo.get(id).pending);
        }
    }

    @Test
    void switchCannotBypassPersistentTaskCapacity() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        Result needsInput =
                new Result(
                        Status.NEEDS_INPUT,
                        "query",
                        "query",
                        Intent.WEATHER,
                        "请补充",
                        List.of(),
                        List.of(),
                        List.of());
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3, 1),
                                (request, control) -> Mono.just(needsInput))) {
            String first =
                    service.submit(identity, request("session", "first"), false).block().runId();
            assertEquals(
                    429,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            service.instruct(
                                                            identity,
                                                            first,
                                                            new TaskService.Instruction(
                                                                    "switch",
                                                                    1,
                                                                    TaskService.Action.SWITCH,
                                                                    "上海后天天气"))
                                                    .block())
                            .getStatusCode()
                            .value());
            assertEquals("NEEDS_INPUT", service.get(identity, first).block().state());
            assertEquals(1, repo.all().size());
        }
    }

    @Test
    void staleInstructionAndAmbiguousAutoCannotMutateTask() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repo = repo(url);
                var service =
                        new TaskService(
                                repo,
                                new ConversationService(repo),
                                settings(url, 3),
                                (request, control) -> Mono.never())) {
            var started = service.submit(identity, request("session", "request"), true).block();
            var reply =
                    service.instruct(
                                    identity,
                                    started.runId(),
                                    new TaskService.Instruction(
                                            "auto", 1, TaskService.Action.AUTO, "换一下"))
                            .block();
            assertEquals(1, reply.revision());
            assertEquals(0, service.get(identity, started.runId()).block().modelCalls());
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            service.instruct(
                                            identity,
                                            started.runId(),
                                            new TaskService.Instruction(
                                                    "stale", 2, TaskService.Action.CANCEL, null))
                                    .block());
            assertEquals("RUNNING", service.get(identity, started.runId()).block().state());
        }
    }
}
