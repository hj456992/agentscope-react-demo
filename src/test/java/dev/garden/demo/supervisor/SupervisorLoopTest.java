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

class SupervisorLoopTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void originalInputReachesMainModelAndCapabilityAnswerNeedsNoChild() {
        List<String> roles = new ArrayList<>();
        String question = "你能帮我做什么？";
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            roles.add(role);
                            return model(
                                    (messages, tools) -> {
                                        assertTrue(
                                                messages.stream()
                                                        .anyMatch(
                                                                m ->
                                                                        m.getRole() == MsgRole.USER
                                                                                && question.equals(
                                                                                        m
                                                                                                .getTextContent())));
                                        return text("我可以查询天气、火车票和规划行程。");
                                    });
                        },
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(new Request("loop", question, List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(List.of("supervisor"), roles);
        assertTrue(result.agents().isEmpty());
        assertEquals(question, result.rewrittenQuery());
    }

    @Test
    void clarificationEndsWithoutCallingAChild() {
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) ->
                                                tool(
                                                        "ask_user",
                                                        Map.of("question", "请问要查询哪个城市的天气？"))),
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(new Request("loop", "明天天气怎么样", List.of())).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.answer().contains("城市"));
        assertTrue(result.agents().isEmpty());
    }

    @Test
    void clarificationCannotSmuggleUnverifiedFacts() {
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) ->
                                                tool(
                                                        "ask_user",
                                                        Map.of(
                                                                "question",
                                                                "杭州明天晴天35℃，票价500元，还需要车票吗？"))),
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result =
                supervisor.run(new Request("clarify-facts", "杭州明天天气怎么样", List.of())).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertFalse(result.answer().contains("35"), result.answer());
        assertFalse(result.answer().contains("500"), result.answer());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.agents().isEmpty());
    }

    @Test
    void mainModelChoosesChildAndReceivesPairedToolResult() {
        AtomicInteger mainCalls = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                role.equals("supervisor")
                                        ? model(
                                                (messages, tools) -> {
                                                    if (mainCalls.getAndIncrement() == 0)
                                                        return tool(
                                                                "weather_agent",
                                                                Map.of(
                                                                        "city",
                                                                        "杭州",
                                                                        "date",
                                                                        "2026-10-29"));
                                                    assertTrue(
                                                            messages.stream()
                                                                    .flatMap(
                                                                            m ->
                                                                                    m
                                                                                            .getContentBlocks(
                                                                                                    ToolResultBlock
                                                                                                            .class)
                                                                                            .stream())
                                                                    .anyMatch(
                                                                            r ->
                                                                                    "call-1"
                                                                                                    .equals(
                                                                                                            r
                                                                                                                    .getId())
                                                                                            && "weather_agent"
                                                                                                    .equals(
                                                                                                            r
                                                                                                                    .getName())));
                                                    return text("天气已查到。");
                                                })
                                        : new OfflineScenarioModel(role),
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(new Request("loop", "杭州明天的天气", List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(2, mainCalls.get());
        assertTrue(result.answer().contains("18℃"));
    }

    @Test
    void twoCallsToSameChildKeepDifferentCitiesAndBothFacts() {
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                role.equals("supervisor")
                                        ? model(
                                                (messages, tools) -> {
                                                    int turn = turns.getAndIncrement();
                                                    if (turn == 0)
                                                        return tool(
                                                                "weather_agent",
                                                                Map.of(
                                                                        "city",
                                                                        "杭州",
                                                                        "date",
                                                                        "2026-10-29"));
                                                    if (turn == 1)
                                                        return tool(
                                                                "weather_agent",
                                                                Map.of(
                                                                        "city",
                                                                        "上海",
                                                                        "date",
                                                                        "2026-10-29"));
                                                    return text("两地天气已查到。");
                                                })
                                        : new OfflineScenarioModel(role),
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(new Request("two", "杭州和上海明天的天气", List.of())).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(2, result.evidence().size());
        assertTrue(result.answer().contains("18℃"));
        assertTrue(result.answer().contains("22℃"));
    }

    @Test
    void unknownMainToolIsFeedbackAndCanChooseValidChildNext() {
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                role.equals("supervisor")
                                        ? model(
                                                (messages, tools) -> {
                                                    int turn = turns.getAndIncrement();
                                                    if (turn == 0)
                                                        return tool("unknown_agent", Map.of());
                                                    if (turn == 1) {
                                                        assertTrue(
                                                                messages.stream()
                                                                        .flatMap(
                                                                                m ->
                                                                                        m
                                                                                                .getContentBlocks(
                                                                                                        ToolResultBlock
                                                                                                                .class)
                                                                                                .stream())
                                                                        .anyMatch(
                                                                                r ->
                                                                                        ChildAgentDispatcher
                                                                                                .text(
                                                                                                        r)
                                                                                                .contains(
                                                                                                        "TOOL_NOT_FOUND")));
                                                        return tool(
                                                                "weather_agent",
                                                                Map.of(
                                                                        "city",
                                                                        "杭州",
                                                                        "date",
                                                                        "2026-10-29"));
                                                    }
                                                    return text("完成");
                                                })
                                        : new OfflineScenarioModel(role),
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(new Request("feedback", "杭州明天的天气", List.of())).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(List.of("天气查询"), result.agents());
    }

    @Test
    void changedInputIsAppendedToSameMainHistoryAfterChildFinishes() {
        RunControl base = RunControl.local(Limits.defaults());
        java.util.concurrent.atomic.AtomicReference<String> pending =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger revision = new AtomicInteger(1);
        RunControl control =
                new DelegatingControl(base) {
                    public String drainInput() {
                        String input = pending.getAndSet(null);
                        if (input != null) revision.incrementAndGet();
                        return input;
                    }

                    public int revision() {
                        return revision.get();
                    }
                };
        AtomicInteger mainTurns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            if (!role.equals("supervisor")) return new OfflineScenarioModel(role);
                            return model(
                                    (messages, tools) -> {
                                        int turn = mainTurns.getAndIncrement();
                                        if (turn == 0) {
                                            pending.set("上海后天的天气");
                                            return tool(
                                                    "weather_agent",
                                                    Map.of("city", "杭州", "date", "2026-10-29"));
                                        }
                                        if (turn == 1) {
                                            assertTrue(
                                                    messages.stream()
                                                            .anyMatch(
                                                                    m ->
                                                                            "杭州明天的天气"
                                                                                    .equals(
                                                                                            m
                                                                                                    .getTextContent())));
                                            assertTrue(
                                                    messages.stream()
                                                            .anyMatch(
                                                                    m ->
                                                                            "上海后天的天气"
                                                                                    .equals(
                                                                                            m
                                                                                                    .getTextContent())));
                                            assertTrue(
                                                    messages.stream()
                                                                    .flatMap(
                                                                            m ->
                                                                                    m
                                                                                            .getContentBlocks(
                                                                                                    ToolResultBlock
                                                                                                            .class)
                                                                                            .stream())
                                                                    .count()
                                                            == 1);
                                            return tool(
                                                    "weather_agent",
                                                    Map.of("city", "上海", "date", "2026-10-30"));
                                        }
                                        return text("按新条件完成");
                                    });
                        },
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(new Request("input", "杭州明天的天气", List.of()), control).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertFalse(result.answer().contains("杭州"));
        assertTrue(result.answer().contains("上海 2026-10-30"));
        assertEquals(3, mainTurns.get());
    }

    @Test
    void mixedClarificationAndBusinessCallMustNotExecuteQuery() {
        AtomicInteger reads = new AtomicInteger();
        var query =
                (ToolUseBlock)
                        tool("weather_agent", Map.of("city", "杭州", "date", "2026-10-29"))
                                .getContent()
                                .get(0);
        var ask =
                ToolUseBlock.builder()
                        .id("ask")
                        .name("ask_user")
                        .input(Map.of("question", "需要补充吗？"))
                        .build();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                role.equals("supervisor")
                                        ? model(
                                                (messages, tools) ->
                                                        ChatResponse.builder()
                                                                .content(List.of(ask, query))
                                                                .finishReason("tool_calls")
                                                                .build())
                                        : new OfflineScenarioModel(role),
                        CLOCK,
                        Limits.defaults(),
                        new SupervisorControlTest().counting(reads));
        Result result = supervisor.run(new Request("mixed", "杭州明天的天气", List.of())).block();
        assertNotEquals(Status.SUCCESS, result.status());
        assertEquals(0, reads.get(), "询问用户时不能同时执行尚未确认的查询");
    }

    @Test
    void duplicateCallIdsCannotLoseOneResultDuringRecovery() {
        AtomicInteger reads = new AtomicInteger();
        var first =
                tool("weather_agent", Map.of("city", "杭州", "date", "2026-10-29"))
                        .getContent()
                        .get(0);
        var second =
                tool("weather_agent", Map.of("city", "上海", "date", "2026-10-29"))
                        .getContent()
                        .get(0);
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                role.equals("supervisor")
                                        ? model(
                                                (messages, tools) ->
                                                        ChatResponse.builder()
                                                                .content(List.of(first, second))
                                                                .finishReason("tool_calls")
                                                                .build())
                                        : new OfflineScenarioModel(role),
                        CLOCK,
                        Limits.defaults(),
                        new SupervisorControlTest().counting(reads));
        Result result = supervisor.run(new Request("ids", "杭州和上海明天的天气", List.of())).block();
        assertEquals(0, reads.get(), "无法配对的请求不能先执行一半");
        assertEquals(Status.FAILED, result.status());
    }

    @Test
    void oldCompletedReadCanBeUsedWithoutOldRewriteAndIntentStages() {
        RunControl control = RunControl.local(Limits.defaults());
        control.reserveModel(100);
        control.reserveModel(100);
        control.checkpoint("rewrite:legacy", "旧改写检查点");
        control.checkpoint("intent:legacy", "旧意图检查点");
        String key =
                "read:query_weather:"
                        + RunContext.fingerprint(
                                Map.of(
                                        "parameters",
                                        Map.of("city", "杭州", "date", "2026-10-29"),
                                        "sourceVersion",
                                        "demo-v1"));
        control.checkpoint(
                key,
                json(
                        new Evidence(
                                "query_weather",
                                Status.SUCCESS,
                                "demo-v1",
                                Map.of(
                                        "city",
                                        "杭州",
                                        "date",
                                        "2026-10-29",
                                        "weather",
                                        Map.of("temperature", 18, "condition", "多云")))));
        AtomicInteger reads = new AtomicInteger();
        List<String> roles = new ArrayList<>();
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            roles.add(role);
                            return new OfflineScenarioModel(role);
                        },
                        CLOCK,
                        Limits.defaults(),
                        new SupervisorControlTest().counting(reads));
        Result result =
                supervisor.run(new Request("upgrade", "杭州明天的天气", List.of()), control).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(0, reads.get());
        assertEquals(7, control.modelCalls(), "两次旧预算加五次新模型尝试，不能清零旧预算");
        assertFalse(roles.contains("rewrite"));
        assertFalse(roles.contains("intent"));
        assertEquals("3.3", control.checkpoint("protocol"));
    }

    @Test
    void greetingCannotAuthorizeInventedWeatherWithoutEvidence() {
        for (String question : List.of("你好，杭州明天天气怎么样？", "你能查杭州明天天气吗？", "杭州明天多少度")) {
            Supervisor supervisor =
                    new Supervisor(
                            role -> model((messages, tools) -> text("杭州明天晴天35℃")),
                            CLOCK,
                            Limits.defaults(),
                            TravelTools.demoData());
            Result result = supervisor.run(new Request("no-evidence", question, List.of())).block();
            assertNotEquals(Status.SUCCESS, result.status(), question);
            assertFalse(result.answer().contains("35"), result.toString());
        }
    }

    @Test
    void capabilityAnswerCannotSmuggleUnverifiedFacts() {
        Supervisor supervisor =
                new Supervisor(
                        role -> model((messages, tools) -> text("我能查询天气，杭州明天35℃")),
                        CLOCK,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(new Request("capability", "你能做什么", List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertFalse(result.answer().contains("35"));
    }

    @Test
    void anotherCitySuccessCannotClearFailedCity() {
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor = cityFailureSupervisor(turns, true);
        Result result = supervisor.run(new Request("failure", "杭州和上海明天的天气", List.of())).block();
        assertEquals(Status.PARTIAL, result.status(), result.toString());
        assertTrue(result.answer().contains("18℃"));
    }

    @Test
    void failedCitySurvivesConversationRestore() {
        RunControl base = RunControl.local(Limits.defaults());
        java.util.concurrent.atomic.AtomicBoolean crash =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        RunControl control =
                new DelegatingControl(base) {
                    public void checkpoint(String key, String value) {
                        base.checkpoint(key, value);
                        if (key.equals("loop:v3.3")
                                && value != null
                                && (value.contains("助手执行失败") || value.contains("助手失败"))
                                && crash.compareAndSet(true, false))
                            throw new IllegalStateException("模拟已保存子调用错误后退出");
                    }
                };
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor = cityFailureSupervisor(turns, false);
        Request request = new Request("restore", "杭州和上海明天的天气", List.of());
        supervisor.run(request, control).block();
        assertFalse(crash.get(), "必须到达目标检查点");
        Result recovered = supervisor.run(request, control).block();
        assertEquals(Status.PARTIAL, recovered.status(), recovered.toString());
    }

    private Supervisor cityFailureSupervisor(AtomicInteger turns, boolean failFirst) {
        return new Supervisor(
                role -> {
                    if (role.equals("supervisor"))
                        return model(
                                (messages, tools) -> {
                                    int turn = turns.getAndIncrement();
                                    if (turn < 2)
                                        return tool(
                                                "weather_agent",
                                                Map.of(
                                                        "city",
                                                        (turn == 0) == failFirst ? "上海" : "杭州",
                                                        "date",
                                                        "2026-10-29"));
                                    return text("完成");
                                });
                    Model delegate = new OfflineScenarioModel(role);
                    return new Model() {
                        public String getModelName() {
                            return delegate.getModelName();
                        }

                        public Flux<ChatResponse> stream(
                                List<Msg> messages,
                                List<ToolSchema> tools,
                                GenerateOptions options) {
                            String input =
                                    messages.stream()
                                            .filter(m -> m.getRole() == MsgRole.USER)
                                            .findFirst()
                                            .orElseThrow()
                                            .getTextContent();
                            if (role.equals("weather_agent") && input.contains("\"city\":\"上海\""))
                                return Flux.error(new IllegalStateException("上海查询子模型失败"));
                            return delegate.stream(messages, tools, options);
                        }
                    };
                },
                CLOCK,
                Limits.defaults(),
                TravelTools.demoData());
    }

    @Test
    void savedFinalTextDoesNotNeedAnotherModelAttemptAfterRestart() {
        Limits limit = new Limits(Duration.ofSeconds(10), 1, 1024);
        RunControl base = RunControl.local(limit);
        java.util.concurrent.atomic.AtomicBoolean crash =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        RunControl control =
                new DelegatingControl(base) {
                    public void checkpoint(String key, String value) {
                        base.checkpoint(key, value);
                        if (key.equals("loop:v3.3")
                                && value != null
                                && value.contains("已完成能力说明")
                                && crash.compareAndSet(true, false))
                            throw new IllegalStateException("模拟最终文本保存后退出");
                    }
                };
        AtomicInteger sends = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) -> {
                                            sends.incrementAndGet();
                                            return text("已完成能力说明");
                                        }),
                        CLOCK,
                        limit,
                        TravelTools.demoData());
        Request request = new Request("final", "你能做什么", List.of());
        supervisor.run(request, control).block();
        assertFalse(crash.get());
        Result recovered = supervisor.run(request, control).block();
        assertEquals(Status.SUCCESS, recovered.status(), recovered.toString());
        assertEquals(1, sends.get());
        assertEquals(1, base.modelCalls());
    }

    @Test
    void optionalOldAnswersAreTrimmedBeforeCompleteInputBudgetIsExceeded() {
        for (int size = 150; size <= 450; size += 25) {
            String oldAnswer = "旧答复".repeat(size);
            List<Turn> history = List.of(new Turn("杭州明天的天气", oldAnswer));
            Supervisor supervisor =
                    new Supervisor(
                            role ->
                                    model(
                                            (messages, tools) -> {
                                                assertTrue(
                                                        ContextBuilder.estimate(
                                                                        Map.of(
                                                                                "messages",
                                                                                messages,
                                                                                "tools",
                                                                                tools))
                                                                <= ContextBuilder.INPUT_LIMIT);
                                                return text("可查询天气和车票");
                                            }),
                            CLOCK,
                            Limits.defaults(),
                            TravelTools.demoData());
            Result result = supervisor.run(new Request("history", "你能做什么", history)).block();
            assertEquals(Status.SUCCESS, result.status(), "旧答复长度：" + size + " / " + result);
        }
    }

    static class DelegatingControl implements RunControl {
        final RunControl base;

        DelegatingControl(RunControl base) {
            this.base = base;
        }

        public String runId() {
            return base.runId();
        }

        public int revision() {
            return base.revision();
        }

        public AccessPolicy.Identity identity() {
            return base.identity();
        }

        public void checkActive() {
            base.checkActive();
        }

        public Duration remaining() {
            return base.remaining();
        }

        public void reserveModel(int value) {
            base.reserveModel(value);
        }

        public void reserveModelStep(String key, int value, int max) {
            base.reserveModelStep(key, value, max);
        }

        public String checkpoint(String key) {
            return base.checkpoint(key);
        }

        public void checkpoint(String key, String value) {
            base.checkpoint(key, value);
        }

        public String drainInput() {
            return base.drainInput();
        }

        public void event(String text) {
            base.event(text);
        }

        public int modelCalls() {
            return base.modelCalls();
        }
    }

    interface Reply {
        ChatResponse next(List<Msg> messages, List<ToolSchema> tools);
    }

    static Model model(Reply reply) {
        return new Model() {
            public String getModelName() {
                return "loop-test";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> Flux.just(reply.next(messages, tools)));
            }
        };
    }

    static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }

    static ChatResponse tool(String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name(name)
                                        .input(input)
                                        .content(json(input))
                                        .build()))
                .finishReason("tool_calls")
                .build();
    }
}
