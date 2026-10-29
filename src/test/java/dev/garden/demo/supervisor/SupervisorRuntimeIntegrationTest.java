package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.config.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.service.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class SupervisorRuntimeIntegrationTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC);

    static SupervisorSettings settings(String url) {
        return new SupervisorSettings(
                "offline",
                Duration.ofSeconds(15),
                16,
                1024,
                8,
                2,
                10,
                120,
                90000,
                1000,
                Duration.ofDays(7),
                Duration.ofDays(30),
                Duration.ofDays(90),
                url,
                "test=local:cli:query_weather,query_trains,search_knowledge,memory:write");
    }

    static RunRes done(TaskService service, String id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            var view = service.get(IdentityDTO.local(), id).block();
            if (!view.state().equals("RUNNING")) {
                return view;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("实际 SDK 任务未结束");
    }

    @Test
    void newInstructionDuringChildExecutionUsesSameRunAndNewFacts() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        Sinks.One<Boolean> release = Sinks.one();
        AtomicBoolean first = new AtomicBoolean(true);
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            Model delegate = new OfflineScenarioModel(role);
                            return new Model() {
                                public String getModelName() {
                                    return delegate.getModelName();
                                }

                                public Flux<ChatResponse> stream(
                                        List<Msg> messages,
                                        List<ToolSchema> tools,
                                        GenerateOptions options) {
                                    if (role.equals("weather_agent")
                                            && first.compareAndSet(true, false)) {
                                        entered.countDown();
                                        return release.asMono()
                                                .flatMapMany(
                                                        x ->
                                                                delegate.stream(
                                                                        messages, tools, options));
                                    }
                                    return delegate.stream(messages, tools, options);
                                }
                            };
                        },
                        CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        try (var repository = new TestRepository(url);
                var tasks = TestServices.taskService(repository, settings(url), supervisor::run)) {
            var started =
                    tasks.submit(
                                    IdentityDTO.local(),
                                    new RequestDTO("steer", "杭州明天的天气", List.of(), "start"),
                                    true)
                            .block();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            int calls = tasks.get(IdentityDTO.local(), started.runId()).block().modelCalls();
            var instruction =
                    tasks.instruct(
                                    IdentityDTO.local(),
                                    new InstructionParam(
                                            started.runId(), "change", 1, Action.REVISE, "上海后天的天气"))
                            .block();
            assertEquals(started.runId(), instruction.runId());
            release.tryEmitValue(true);
            var result = done(tasks, started.runId());
            assertEquals("SUCCESS", result.state(), result.result().toString());
            assertEquals(2, result.revision());
            assertTrue(
                    result.result().evidence().stream()
                            .anyMatch(
                                    e ->
                                            "上海".equals(e.data().get("city"))
                                                    && "2026-10-30".equals(e.data().get("date"))),
                    "保留旧结果并加入新查询结果");
            assertFalse(result.result().answer().contains("杭州"));
            assertTrue(result.modelCalls() > calls);
        }
    }

    @Test
    void parentCancellationKeepsAcceptedChildAndServiceCloseStopsIt() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            if (!role.equals("weather_agent")) {
                                return new OfflineScenarioModel(role);
                            }
                            return new Model() {
                                public String getModelName() {
                                    return "pending-child";
                                }

                                public Flux<ChatResponse> stream(
                                        List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                                    entered.countDown();
                                    return Flux.<ChatResponse>never()
                                            .doOnCancel(cancelled::countDown);
                                }
                            };
                        },
                        CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        try (var repository = new TestRepository(url);
                var tasks = TestServices.taskService(repository, settings(url), supervisor::run)) {
            var started =
                    tasks.submit(
                                    IdentityDTO.local(),
                                    new RequestDTO("cancel", "杭州明天的天气", List.of(), "start"),
                                    true)
                            .block();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            tasks.instruct(
                            IdentityDTO.local(),
                            new InstructionParam(started.runId(), "stop", 1, Action.CANCEL, null))
                    .block();
            assertFalse(cancelled.await(100, TimeUnit.MILLISECONDS), "父回合取消不撤回已受理子会话");
            assertEquals("CANCELLED", done(tasks, started.runId()).state());
            tasks.close();
            assertTrue(cancelled.await(3, TimeUnit.SECONDS), "关闭服务才关闭所有会话");
        }
    }

    @Test
    void cachedItineraryCannotLeakAfterPermissionsChange() {
        AtomicReference<IdentityDTO> identity = new AtomicReference<>(IdentityDTO.local());
        RunControl original = RunControl.local(LimitsDTO.defaults());
        RunControl control =
                new RunControl() {
                    public String runId() {
                        return original.runId();
                    }

                    public int revision() {
                        return original.revision();
                    }

                    public IdentityDTO identity() {
                        return identity.get();
                    }

                    public void checkActive() {
                        original.checkActive();
                    }

                    public Duration remaining() {
                        return original.remaining();
                    }

                    public void reserveModel(int n) {
                        original.reserveModel(n);
                    }

                    public String checkpoint(String key) {
                        return original.checkpoint(key);
                    }

                    public void checkpoint(String k, String v) {
                        original.checkpoint(k, v);
                    }

                    public String drainInput() {
                        return original.drainInput();
                    }

                    public void event(String e) {
                        original.event(e);
                    }

                    public int modelCalls() {
                        return original.modelCalls();
                    }
                };
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                CoreSourceParityTest.model(
                                        (messages, tools) ->
                                                turns.getAndIncrement() == 0
                                                        ? CoreSourceParityTest.tool(
                                                                "query_trains",
                                                                Map.of(
                                                                        "from",
                                                                        "杭州",
                                                                        "to",
                                                                        "上海",
                                                                        "date",
                                                                        "2026-10-29"))
                                                        : CoreSourceParityTest.text(
                                                                "已查询 G-DEMO 车次。")),
                        CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        RequestDTO request = new RequestDTO("cached", "明天从杭州到上海的火车票", List.of());
        ResultDTO first = supervisor.run(request, control).block();
        assertEquals(Status.SUCCESS, first.status());
        identity.set(new IdentityDTO("local", "cli", Set.of("query_weather")));
        ResultDTO next = supervisor.run(request, control).block();
        assertFalse(next.answer().contains("G-DEMO"));
        assertFalse(next.answer().contains("travel-guide"));
        assertTrue(next.evidence().stream().allMatch(e -> e.tool().equals("query_weather")));
    }

    @Test
    void ignoredInterruptDoesNotFreePhysicalToolWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicInteger executing = new AtomicInteger(), peak = new AtomicInteger();
        var jobs = new ArrayList<reactor.core.Disposable>();
        try {
            for (int i = 0; i < 8; i++)
                jobs.add(
                        ToolExecutor.blocking(
                                        () -> {
                                            int count = executing.incrementAndGet();
                                            peak.accumulateAndGet(count, Math::max);
                                            entered.countDown();
                                            boolean waiting = true;
                                            while (waiting) {
                                                try {
                                                    release.await();
                                                    waiting = false;
                                                } catch (InterruptedException ignored) {
                                                }
                                            }
                                            executing.decrementAndGet();
                                            return 1;
                                        })
                                .subscribe());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            jobs.forEach(reactor.core.Disposable::dispose);
            var queued =
                    ToolExecutor.blocking(
                                    () -> {
                                        int count = executing.incrementAndGet();
                                        peak.accumulateAndGet(count, Math::max);
                                        executing.decrementAndGet();
                                        return 2;
                                    })
                            .toFuture();
            assertFalse(queued.isDone());
            assertEquals(8, executing.get());
            release.countDown();
            assertEquals(2, queued.get(2, TimeUnit.SECONDS));
            assertEquals(8, peak.get());
        } finally {
            release.countDown();
            jobs.forEach(reactor.core.Disposable::dispose);
        }
    }
}
