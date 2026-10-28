package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

class SupervisorControlTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC);
    final Request request = new Request("control", "杭州明天的天气", List.of());

    Model model(Supplier<ChatResponse> next) {
        return new Model() {
            public String getModelName() {
                return "adversarial";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> Flux.just(next.get()));
            }
        };
    }

    ChatResponse tool(String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(UUID.randomUUID().toString())
                                        .name(name)
                                        .input(input)
                                        .content(json(input))
                                        .build()))
                .finishReason("tool_calls")
                .build();
    }

    ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }

    Supervisor with(Function<String, Model> models, TravelTools.DataSource data) {
        return new Supervisor(models, clock, Limits.defaults(), data);
    }

    @Test
    void modelInventedCityMustNotReachWeather() {
        AtomicInteger queries = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        Result result = with(role -> role.equals("supervisor")
                ? model(() -> turns.getAndIncrement() == 0
                    ? tool("weather_agent", Map.of("city", "上海", "date", "2026-10-29"))
                    : tool("ask_user", Map.of("question", "请确认查询城市。")))
                : new OfflineScenarioModel(role), counting(queries)).run(request).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertEquals(0, queries.get());
    }

    @Test
    void nonexistentToolIsReturnedToModelAndBoundedAcrossNewCallIds() {
        AtomicInteger calls = new AtomicInteger();
        Result result =
                with(
                                role ->
                                        role.equals("weather_agent")
                                                ? model(
                                                        () -> {
                                                            calls.incrementAndGet();
                                                            return tool("not_registered", Map.of());
                                                        })
                                                : new OfflineScenarioModel(role),
                                TravelTools.demoData())
                        .run(request)
                        .block();
        assertTrue(calls.get() <= 4, "未知工具不能形成无限循环");
        assertNotEquals(Status.SUCCESS, result.status());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.events().stream().anyMatch(e -> e.contains("TOOL_NOT_FOUND")));
    }

    @Test
    void repeatedToolCallDoesNotRepeatRead() {
        AtomicInteger steps = new AtomicInteger(), reads = new AtomicInteger();
        Result result =
                with(
                                role ->
                                        role.equals("weather_agent")
                                                ? model(
                                                        () ->
                                                                steps.incrementAndGet() <= 2
                                                                        ? tool(
                                                                                "query_weather",
                                                                                Map.of(
                                                                                        "city",
                                                                                        "杭州",
                                                                                        "date",
                                                                                        "2026-10-29"))
                                                                        : text("完成"))
                                                : new OfflineScenarioModel(role),
                                counting(reads))
                        .run(request)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(1, reads.get());
    }

    @Test
    void wrongSchemaCanBeCorrectedThroughRealSdkLoop() {
        AtomicInteger steps = new AtomicInteger(), reads = new AtomicInteger();
        Result result =
                with(
                                role ->
                                        role.equals("weather_agent")
                                                ? model(
                                                        () ->
                                                                switch (steps.incrementAndGet()) {
                                                                    case 1 ->
                                                                            tool(
                                                                                    "query_weather",
                                                                                    Map.of(
                                                                                            "city",
                                                                                            123,
                                                                                            "date",
                                                                                            "2026-10-29"));
                                                                    case 2 ->
                                                                            tool(
                                                                                    "query_weather",
                                                                                    Map.of(
                                                                                            "city",
                                                                                            "杭州",
                                                                                            "date",
                                                                                            "2026-10-29"));
                                                                    default -> text("完成");
                                                                })
                                                : new OfflineScenarioModel(role),
                                counting(reads))
                        .run(request)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(1, reads.get());
        assertTrue(result.events().stream().anyMatch(e -> e.contains("INVALID_SCHEMA")));
    }

    @Test
    void invalidFinalClaimRepairsOnceThenUsesEvidence() {
        AtomicInteger answers = new AtomicInteger();
        Result result =
                with(
                                role ->
                                        role.equals("answer")
                                                ? model(
                                                        () -> {
                                                            answers.incrementAndGet();
                                                            return tool(
                                                                    "generate_response",
                                                                    Map.of(
                                                                            "response",
                                                                            new AnswerVerifier
                                                                                    .Draft(
                                                                                    List.of(
                                                                                            new AnswerVerifier
                                                                                                    .Claim(
                                                                                                    "query_weather",
                                                                                                    "{\"temperature\":99}")))));
                                                        })
                                                : new OfflineScenarioModel(role),
                                TravelTools.demoData())
                        .run(request)
                        .block();
        assertEquals(2, answers.get());
        assertEquals(Status.SUCCESS, result.status());
        assertTrue(result.answer().contains("18"));
        assertFalse(result.answer().contains("99"));
        assertTrue(result.events().stream().anyMatch(e -> e.contains("证据兜底")));
    }

    @Test
    void longHistoryDoesNotSilentlyRemoveEarlierUserConstraint() {
        List<Turn> turns = new ArrayList<>();
        turns.add(new Turn("只坐二等座", ""));
        for (int i = 0; i < 8; i++) turns.add(new Turn("第" + i + "轮补充", "无关回答".repeat(1000)));
        Map<String, Object> input =
                ContextBuilder.conversationInput(
                        new Request("x", "杭州明天的天气", turns), LocalDate.of(2026, 10, 28));
        assertTrue(json(input).contains("只坐二等座"));
        assertTrue(ContextBuilder.estimate(input) <= 4000);
    }

    @Test
    void oversizedCriticalHistoryAsksInsteadOfTruncating() {
        Result result =
                Supervisor.offline(clock)
                        .run(
                                new Request(
                                        "large",
                                        "杭州明天的天气",
                                        List.of(new Turn("不能丢的条件".repeat(1000), ""))))
                        .block();
        assertEquals(Status.CONTEXT_LIMIT, result.status());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void knowledgeExpiryExcludesOldEvidence() {
        Evidence evidence = new KnowledgeService().search("行程规划", LocalDate.of(2100, 1, 1));
        assertEquals(Status.NO_DATA, evidence.status());
    }

    @Test
    void checkpointedChildIsReusedWithSameConditions() {
        AtomicInteger reads = new AtomicInteger();
        RunControl control = RunControl.local(Limits.defaults());
        Supervisor supervisor = with(OfflineScenarioModel::new, counting(reads));
        assertEquals(Status.SUCCESS, supervisor.run(request, control).block().status());
        int used = control.modelCalls();
        assertEquals(Status.SUCCESS, supervisor.run(request, control).block().status());
        assertEquals(1, reads.get());
        assertTrue(control.modelCalls() > used);
    }

    @Test
    void sdkRateLimitRetriesWithoutRepeatingWholeTask() {
        AtomicInteger attempts = new AtomicInteger();
        Result result =
                with(
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
                                                    && attempts.incrementAndGet() == 1)
                                                return Flux.error(
                                                        new io.agentscope.extensions.model.openai
                                                                .exception.RateLimitException(
                                                                "limited", "rate_limit", ""));
                                            return delegate.stream(messages, tools, options);
                                        }
                                    };
                                },
                                TravelTools.demoData())
                        .run(request)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(3, attempts.get());
    }

    @Test
    void sameLogicalModelStepCannotResetAttemptsWithNewMessageIds() {
        RunContext context = new RunContext(request, Limits.defaults());
        AtomicInteger sends = new AtomicInteger();
        Model delegate =
                model(
                        () -> {
                            sends.incrementAndGet();
                            return text("ok");
                        });
        Model wrapped = context.budgeted(delegate);
        for (int i = 0; i < 3; i++)
            wrapped.stream(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("same logical prompt")
                                            .build()),
                            List.of(),
                            GenerateOptions.builder().build())
                    .blockLast();
        assertThrows(
                RunControl.BudgetExceeded.class,
                () ->
                        wrapped.stream(
                                        List.of(
                                                Msg.builder()
                                                        .role(MsgRole.USER)
                                                        .textContent("same logical prompt")
                                                        .build()),
                                        List.of(),
                                        GenerateOptions.builder().build())
                                .blockLast());
        assertEquals(3, sends.get());
    }

    @Test
    void unsupportedSeatConstraintMustNotBeSilentlyIgnored() {
        Result result =
                Supervisor.offline(clock)
                        .run(new Request("seat", "明天从杭州到上海的火车票，只要一等座", List.of()))
                        .block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void modelCannotChangeExplicitTripLength() {
        AtomicInteger turns = new AtomicInteger();
        Result result = with(role -> role.equals("supervisor")
                ? model(() -> turns.getAndIncrement() == 0
                    ? tool("itinerary_agent", Map.of("from", "杭州", "to", "上海", "date", "2026-10-29", "days", 1))
                    : tool("ask_user", Map.of("question", "请确认行程天数。")))
                : new OfflineScenarioModel(role), TravelTools.demoData())
                .run(new Request("days", "明天从杭州到上海玩3天", List.of())).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.evidence().isEmpty());
    }

    TravelTools.DataSource counting(AtomicInteger count) {
        return new TravelTools.DataSource() {
            public Map<String, Object> weather(String city, String date) {
                count.incrementAndGet();
                return TravelTools.demoData().weather(city, date);
            }

            public List<Map<String, Object>> trains(String from, String to, String date) {
                return TravelTools.demoData().trains(from, to, date);
            }
        };
    }
}
