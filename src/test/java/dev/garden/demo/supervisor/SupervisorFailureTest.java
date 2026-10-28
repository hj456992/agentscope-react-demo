package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.service.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.config.*;

import static org.junit.jupiter.api.Assertions.*;

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
                List.of(Status.FAILED, Status.SUCCESS, Status.SUCCESS),
                result.evidence().stream().map(EvidenceDTO::status).toList());
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
        assertEquals(List.of("行程规划", "天气查询", "火车票查询"), result.agents());
        assertEquals(2, result.evidence().size());
        assertTrue(result.answer().contains("G-DEMO"));
        assertTrue(result.answer().contains("失败"));
    }

    @Test
    void repeatedMalformedToolArgumentsStopBeforeAnyQuery() {
        Function<String, Model> models =
                role ->
                        fixedModel(
                                () ->
                                        Flux.just(
                                                ChatResponse.builder()
                                                        .content(
                                                                List.of(
                                                                        ToolUseBlock.builder()
                                                                                .id("invalid")
                                                                                .name(
                                                                                        "weather_agent")
                                                                                .input(
                                                                                        Map.of(
                                                                                                "city",
                                                                                                99))
                                                                                .content(
                                                                                        "{\"city\":99}")
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
    void callerCancellationStopsSubscriptionWithoutEmittingSuccess() throws Exception {
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
        var subscription =
                supervisor(models, LimitsDTO.defaults(), TravelTools.demoData())
                        .run(request("杭州明天的天气"))
                        .subscribe(result -> responses.incrementAndGet());
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertEquals(0, responses.get());
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void changedToolParametersNeverReachDataSource() {
        RunContext context =
                new RunContext(
                        request("杭州明天的天气"),
                        LimitsDTO.defaults(),
                        RunControl.local(LimitsDTO.defaults()));
        context.decision = new DecisionDTO(Intent.WEATHER, "杭州", "", "", "2026-10-29", 0);
        ToolResultBlock result =
                new TravelTools(context, TravelTools.demoData()).weather("上海", "2026-10-29");
        assertEquals(ToolResultState.ERROR, result.getState());
        assertEquals(Status.FAILED, context.outcome());
        assertEquals(
                "INVALID_INPUT",
                context.result(Status.FAILED, "").evidence().get(0).status().name());
    }

    @Test
    void answeringWithoutQueryingDoesNotCountAsSuccess() {
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
        assertEquals(Status.FAILED, result.status());
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
        assertEquals(Set.of("query_weather"), seen.get("weather_agent"));
        assertEquals(Set.of("query_trains"), seen.get("train_agent"));
        assertEquals(
                Set.of("weather_agent", "train_agent", "search_knowledge"),
                seen.get("itinerary_agent"));
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
