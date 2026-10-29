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

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

class SupervisorFailureTest {
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final String itinerary = "明天从杭州到上海玩一天，看看天气和火车票，规划行程";

    private Supervisor supervisor(
            Function<String, Model> models, LimitsDTO limits, TravelTools.DataSource data) {
        return new Supervisor(models, clock, limits, data);
    }

    private RequestDTO request(String question) {
        return new RequestDTO("甲", question, List.of());
    }

    @Test
    void failedWeatherStillAllowsTrainResultButOnlyAsPartialAnswer() {
        TravelTools.DataSource data =
                new TravelTools.DataSource() {
                    @Override
                    public Map<String, Object> weather(String city, String date) {
                        throw new IllegalStateException("模拟天气服务故障");
                    }

                    @Override
                    public List<Map<String, Object>> trains(String from, String to, String date) {
                        return TravelTools.demoData().trains(from, to, date);
                    }
                };
        ResultDTO result =
                supervisor(OfflineScenarioModel::new, LimitsDTO.defaults(), data)
                        .run(request(itinerary))
                        .block();
        assertEquals(Status.PARTIAL, result.status());
        assertEquals(
                Map.of(
                        "query_weather",
                        Status.FAILED,
                        "query_trains",
                        Status.SUCCESS,
                        "search_knowledge",
                        Status.SUCCESS),
                result.evidence().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        EvidenceDTO::tool, EvidenceDTO::status)));
        assertTrue(result.answer().contains("G-DEMO"));
        assertTrue(result.answer().contains("失败"));
    }

    @Test
    void childModelCrashIsNotTreatedAsNoData() {
        Function<String, Model> models =
                role ->
                        role.equals("weather_agent")
                                ? fixedModel(
                                        () -> Flux.error(new IllegalStateException("模拟子 Agent 故障")))
                                : new OfflineScenarioModel(role);
        ResultDTO result =
                supervisor(models, LimitsDTO.defaults(), TravelTools.demoData())
                        .run(request("杭州明天的天气"))
                        .block();
        assertEquals(Status.FAILED, result.status());
        assertEquals(List.of("天气查询"), result.agents());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void plannerContinuesAfterOneChildCrashes() {
        Function<String, Model> models =
                role ->
                        role.equals("weather_agent")
                                ? fixedModel(
                                        () -> Flux.error(new IllegalStateException("模拟子 Agent 故障")))
                                : new OfflineScenarioModel(role);
        ResultDTO result =
                supervisor(models, LimitsDTO.defaults(), TravelTools.demoData())
                        .run(request(itinerary))
                        .block();
        assertEquals(Status.PARTIAL, result.status());
        assertEquals(Set.of("行程规划", "天气查询", "火车票查询"), Set.copyOf(result.agents()));
        assertEquals(2, result.evidence().size());
        assertTrue(result.answer().contains("G-DEMO"));
        assertTrue(
                result.answer().contains("失败")
                        || result.answer().contains("未完成")
                        || result.answer().contains("无法确认")
                        || result.answer().contains("不完整"),
                result.answer());
        assertFalse(
                result.evidence().stream()
                        .anyMatch(
                                e ->
                                        e.tool().equals("query_weather")
                                                && e.status() == Status.SUCCESS),
                "子模型崩溃不能生成天气成功证据");
    }

    @Test
    void repeatedMalformedToolArgumentsStopBeforeAnyQuery() {
        Function<String, Model> models =
                role ->
                        role.equals("compact")
                                ? new OfflineScenarioModel(role)
                                : fixedModel(
                                        () ->
                                                Flux.just(
                                                        ChatResponse.builder()
                                                                .content(
                                                                        List.of(
                                                                                ToolUseBlock
                                                                                        .builder()
                                                                                        .id(
                                                                                                UUID.randomUUID()
                                                                                                        .toString())
                                                                                        .name(
                                                                                                "spawn_agent")
                                                                                        .input(
                                                                                                Map
                                                                                                        .of(
                                                                                                                "task_name",
                                                                                                                99,
                                                                                                                "message",
                                                                                                                "杭州明天的天气"))
                                                                                        .build()))
                                                                .finishReason("tool_calls")
                                                                .build()));
        ResultDTO result =
                supervisor(models, LimitsDTO.defaults(), TravelTools.demoData())
                        .run(request("杭州明天的天气"))
                        .block();
        assertEquals(Status.BUDGET_EXHAUSTED, result.status());
        assertTrue(result.agents().isEmpty());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void allAgentsShareOneModelCallBudget() {
        AtomicInteger remoteCalls = new AtomicInteger();
        Function<String, Model> models =
                role -> counting(new OfflineScenarioModel(role), remoteCalls);
        LimitsDTO limits = new LimitsDTO(Duration.ofSeconds(5), 3, 1024);
        ResultDTO result =
                supervisor(models, limits, TravelTools.demoData()).run(request(itinerary)).block();
        assertEquals(Status.BUDGET_EXHAUSTED, result.status());
        assertEquals(3, remoteCalls.get(), "超过额度后不能再向模型发请求");
    }

    @Test
    void timeoutCancelsPendingChildModel() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        Function<String, Model> models =
                role ->
                        role.equals("weather_agent")
                                ? fixedModel(
                                        () ->
                                                Flux.<ChatResponse>never()
                                                        .doOnCancel(cancelled::countDown))
                                : new OfflineScenarioModel(role);
        ResultDTO result =
                supervisor(
                                models,
                                new LimitsDTO(Duration.ofMillis(500), 16, 1024),
                                TravelTools.demoData())
                        .run(request("杭州明天的天气"))
                        .block(Duration.ofSeconds(5));
        assertEquals(Status.TIMEOUT, result.status());
        assertTrue(cancelled.await(2, TimeUnit.SECONDS), "本地订阅应取消，不代表远端服务器一定停止计算");
    }

    @Test
    void callerCancellationStopsParentOutputButAcceptedChildNeedsExplicitInterrupt()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicInteger responses = new AtomicInteger();
        Function<String, Model> models =
                role ->
                        role.equals("weather_agent")
                                ? fixedModel(
                                        () -> {
                                            started.countDown();
                                            return Flux.<ChatResponse>never()
                                                    .doOnCancel(cancelled::countDown);
                                        })
                                : new OfflineScenarioModel(role);
        RunControl control = RunControl.local(LimitsDTO.defaults());
        var subscription =
                supervisor(models, LimitsDTO.defaults(), TravelTools.demoData())
                        .run(request("杭州明天的天气"), control)
                        .subscribe(result -> responses.incrementAndGet());
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            subscription.dispose();
            assertFalse(cancelled.await(100, TimeUnit.MILLISECONDS), "V2 已受理子回合独立于父订阅");
            var child =
                    control.listAgents(null).stream()
                            .filter(agent -> agent.agentRole().equals("weather_agent"))
                            .findFirst()
                            .orElseThrow();
            control.interruptAgent(child.agentId()).block();
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertEquals(0, responses.get());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void invalidToolParametersNeverReachDataSource() {
        RunContext context =
                new RunContext(
                        request("杭州明天的天气"),
                        LimitsDTO.defaults(),
                        RunControl.local(LimitsDTO.defaults()));
        context.decision = new DecisionDTO(Intent.WEATHER, "杭州", "", "", "2026-10-29", 0);
        ToolResultBlock result =
                new TravelTools(context, TravelTools.demoData()).weather("上海", "invalid-date");
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(Status.FAILED, context.outcome());
        assertEquals(
                "INVALID_INPUT",
                context.result(Status.FAILED, "").evidence().get(0).status().name());
    }

    @Test
    void childTextAloneDoesNotCreateVerifiedEvidence() {
        Function<String, Model> models =
                role ->
                        role.equals("weather_agent")
                                ? fixedModel(
                                        () ->
                                                Flux.just(
                                                        ChatResponse.builder()
                                                                .content(
                                                                        List.of(
                                                                                TextBlock.builder()
                                                                                        .text(
                                                                                                "查询成功，天气晴朗")
                                                                                        .build()))
                                                                .finishReason("stop")
                                                                .build()))
                                : new OfflineScenarioModel(role);
        ResultDTO result =
                supervisor(models, LimitsDTO.defaults(), TravelTools.demoData())
                        .run(request("杭州明天的天气"))
                        .block();
        assertEquals(Status.SUCCESS, result.status(), "此状态表示会话完成，不代表文字事实经过验证");
        assertTrue(result.evidence().isEmpty());
        assertFalse(result.answer().contains("天气晴朗"));
    }

    @Test
    void queryAgentsOnlySeeTheirOwnTool() {
        Map<String, Set<String>> seen = new ConcurrentHashMap<>();
        Function<String, Model> models =
                role -> {
                    Model delegate = new OfflineScenarioModel(role);
                    return new Model() {
                        @Override
                        public String getModelName() {
                            return "工具隔离测试";
                        }

                        @Override
                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            seen.put(
                                    role,
                                    tools.stream()
                                            .map(ToolSchema::getName)
                                            .collect(java.util.stream.Collectors.toSet()));
                            return delegate.stream(messages, tools, options);
                        }
                    };
                };
        ResultDTO result =
                supervisor(models, LimitsDTO.defaults(), TravelTools.demoData())
                        .run(request(itinerary))
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertTrue(seen.get("weather_agent").contains("query_weather"));
        assertFalse(seen.get("weather_agent").contains("query_trains"));
        assertFalse(seen.get("weather_agent").contains("search_knowledge"));
        assertTrue(seen.get("train_agent").contains("query_trains"));
        assertFalse(seen.get("train_agent").contains("query_weather"));
        assertFalse(seen.get("train_agent").contains("search_knowledge"));
        assertTrue(
                seen.get("itinerary_agent")
                        .containsAll(Set.of("spawn_agent", "wait_agent", "search_knowledge")));
        assertFalse(
                seen.values().stream()
                        .anyMatch(
                                names ->
                                        names.contains("weather_agent")
                                                || names.contains("train_agent")));
    }

    private Model fixedModel(java.util.function.Supplier<Flux<ChatResponse>> response) {
        return new Model() {
            @Override
            public String getModelName() {
                return "故障测试模型";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return response.get();
            }
        };
    }

    private Model counting(Model delegate, AtomicInteger count) {
        return new Model() {
            @Override
            public String getModelName() {
                return "调用次数测试";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                count.incrementAndGet();
                return delegate.stream(messages, tools, options);
            }
        };
    }
}
