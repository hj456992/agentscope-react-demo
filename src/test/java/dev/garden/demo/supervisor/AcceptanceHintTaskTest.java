package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.config.SupervisorSettings;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.Intent;
import dev.garden.demo.supervisor.pojo.dto.RequestDTO;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;
import dev.garden.demo.supervisor.pojo.dto.Status;
import dev.garden.demo.supervisor.pojo.res.RunRes;
import dev.garden.demo.supervisor.service.TaskService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 受控验收提示的入口校验与向模型循环的传递。 */
class AcceptanceHintTaskTest {
    @TempDir Path directory;
    private final IdentityDTO identity = IdentityDTO.local();

    private SupervisorSettings settings(boolean hintsEnabled) {
        return new SupervisorSettings(
                "offline",
                Duration.ofSeconds(20),
                3,
                1024,
                2,
                1,
                10,
                120,
                90000,
                100,
                Duration.ofDays(7),
                Duration.ofDays(30),
                Duration.ofDays(90),
                "jdbc:h2:mem:" + UUID.randomUUID(),
                "sa",
                "",
                16,
                "test=t:u:query_weather",
                hintsEnabled,
                "",
                Duration.ofSeconds(5),
                0);
    }

    private static ResultDTO success(String text) {
        return new ResultDTO(
                Status.SUCCESS, text, text, Intent.WEATHER, text, List.of(), List.of(), List.of());
    }

    private static RequestDTO request(String requestId, String hint) {
        return new RequestDTO("session-" + requestId, "杭州明天天气", List.of(), requestId, hint);
    }

    @Test
    void enabledInstanceNormalizesHintAndRecordsControlledTrigger() throws Exception {
        try (var repo = new TestRepository("jdbc:h2:mem:" + UUID.randomUUID())) {
            var seen = new AtomicReference<RequestDTO>();
            var entered = new CountDownLatch(1);
            TaskService service =
                    TestServices.taskService(
                            repo,
                            settings(true),
                            (request, control) ->
                                    Mono.fromCallable(
                                            () -> {
                                                seen.set(request);
                                                entered.countDown();
                                                return success("已受理");
                                            }));
            RunRes started =
                    service.submit(identity, request("request", " spawn_child "), true).block();
            assertNotNull(started);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals("SPAWN_CHILD", seen.get().acceptanceHint());

            TaskDO stored = repo.get(started.runId());
            assertEquals("CONTROLLED", stored.triggerMode);
            assertEquals("SPAWN_CHILD", stored.acceptanceHint);
            assertEquals("SPAWN_CHILD", stored.request.acceptanceHint());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (repo.get(started.runId()).state.equals("RUNNING")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertNotEquals("RUNNING", repo.get(started.runId()).state);
        }
    }

    @Test
    void disabledInstanceRejectsAnyHintInsteadOfSilentlyDroppingIt() {
        try (var repo = new TestRepository("jdbc:h2:mem:" + UUID.randomUUID())) {
            TaskService service =
                    TestServices.taskService(
                            repo, settings(false), (request, control) -> Mono.just(success("已受理")));
            ResponseStatusException error =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> service.submit(identity, request("request", "SPAWN_CHILD"), true).block());
            assertEquals(400, error.getStatusCode().value());
            assertTrue(repo.all().isEmpty());
        }
    }

    @Test
    void enabledInstanceStillRejectsHintsOutsideTheAllowlist() {
        try (var repo = new TestRepository("jdbc:h2:mem:" + UUID.randomUUID())) {
            TaskService service =
                    TestServices.taskService(
                            repo, settings(true), (request, control) -> Mono.just(success("已受理")));
            ResponseStatusException error =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> service.submit(identity, request("request", "FREE_TEXT"), true).block());
            assertEquals(400, error.getStatusCode().value());
            assertTrue(repo.all().isEmpty());
        }
    }
}
