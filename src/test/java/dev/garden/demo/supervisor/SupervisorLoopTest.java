package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 主循环协议回归：原文、工具配对、反馈、输入边界、恢复与证据附件。 */
class SupervisorLoopTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC);

    private Supervisor supervisor(Reply reply) {
        return new Supervisor(
                role -> role.equals("compact") ? new OfflineScenarioModel(role) : model(reply),
                CLOCK,
                LimitsDTO.defaults(),
                TravelTools.demoData());
    }

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
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        ResultDTO result = supervisor.run(new RequestDTO("loop", question, List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals("我可以查询天气、火车票和规划行程。", result.answer());
        assertEquals(List.of("supervisor"), roles);
        assertTrue(result.agents().isEmpty());
        assertEquals(question, result.rewrittenQuery());
    }

    @Test
    void clarificationWaitsWithoutCallingAChildOrInventingToolCompletion() {
        RunControl control = RunControl.local(LimitsDTO.defaults());
        Supervisor supervisor = supervisor((messages, tools) -> question("请问要查询哪个城市的天气？"));
        ResultDTO result =
                supervisor.run(new RequestDTO("loop", "明天天气怎么样", List.of()), control).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertEquals("请问要查询哪个城市的天气？", result.answer());
        assertTrue(result.agents().isEmpty());
        assertFalse(control.checkpoint("loop:v3.3").contains("等待用户补充："));
    }

    @Test
    void questionPreservesModelWordingWithoutCreatingVerifiedFacts() {
        String candidate = "预算500元以内，还是优先省时？";
        ResultDTO result =
                supervisor((messages, tools) -> question(candidate))
                        .run(new RequestDTO("clarify", "帮我比较方案", List.of()))
                        .block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertEquals(candidate, result.answer());
        assertTrue(result.evidence().isEmpty(), "提问文字不是工具查询证据，也不是用户授权");
        assertTrue(result.agents().isEmpty());
    }

    @Test
    void mainModelChoosesV2ChildAndReceivesPairedAcceptanceReceipt() {
        RunControl base = RunControl.local(LimitsDTO.defaults());
        AtomicInteger spawns = new AtomicInteger();
        RunControl control =
                new DelegatingControl(base) {
                    public reactor.core.publisher.Mono<AgentSnapshotDTO> spawnAgent(
                            String operationId,
                            String taskName,
                            String message,
                            String role,
                            String fork,
                            Map<String, String> inherited) {
                        assertEquals("weather_agent", role);
                        assertEquals("none", fork);
                        spawns.incrementAndGet();
                        return reactor.core.publisher.Mono.just(
                                new AgentSnapshotDTO(
                                        "child-1",
                                        "/root/weather",
                                        role,
                                        "running",
                                        null,
                                        null,
                                        java.util.Set.of()));
                    }
                };
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                supervisor(
                        (messages, tools) -> {
                            if (turns.getAndIncrement() == 0) {
                                return tool(
                                        "spawn_agent",
                                        Map.of(
                                                "task_name",
                                                "weather",
                                                "message",
                                                "杭州明天天气",
                                                "agent_type",
                                                "weather_agent",
                                                "fork_turns",
                                                "none"));
                            }
                            ToolResultBlock output =
                                    results(messages).stream()
                                            .filter(r -> r.getName().equals("spawn_agent"))
                                            .findFirst()
                                            .orElseThrow();
                            assertEquals("call-1", output.getId());
                            assertTrue(ChildAgentDispatcher.text(output).contains("child-1"));
                            return text("已受理查询，尚未收到完成结果。");
                        });
        ResultDTO result =
                supervisor.run(new RequestDTO("spawn", "杭州明天天气", List.of()), control).block();
        assertEquals(1, spawns.get());
        assertEquals("已受理查询，尚未收到完成结果。", result.answer());
        assertTrue(result.evidence().isEmpty(), "受理回执不能伪装成已完成查询");
    }

    @Test
    void twoToolQueriesKeepDifferentCitiesAndBothFacts() {
        AtomicInteger turns = new AtomicInteger();
        ResultDTO result =
                supervisor(
                                (messages, tools) ->
                                        switch (turns.getAndIncrement()) {
                                            case 0 ->
                                                    tool(
                                                            "query_weather",
                                                            Map.of(
                                                                    "city",
                                                                    "杭州",
                                                                    "date",
                                                                    "2026-10-29"));
                                            case 1 ->
                                                    tool(
                                                            "query_weather",
                                                            Map.of(
                                                                    "city",
                                                                    "上海",
                                                                    "date",
                                                                    "2026-10-29"));
                                            default -> text("杭州18℃，上海22℃，均为示例数据。");
                                        })
                        .run(new RequestDTO("two", "杭州和上海明天天气", List.of()))
                        .block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(
                Set.of("杭州", "上海"),
                result.evidence().stream()
                        .map(e -> e.data().get("city"))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals("杭州18℃，上海22℃，均为示例数据。", result.answer());
    }

    @Test
    void unknownMainToolIsPairedFeedbackAndCanChooseValidToolNext() {
        AtomicInteger turns = new AtomicInteger();
        ResultDTO result =
                supervisor(
                                (messages, tools) -> {
                                    int turn = turns.getAndIncrement();
                                    if (turn == 0) {
                                        return tool("unknown_agent", Map.of());
                                    }
                                    if (turn == 1) {
                                        ToolResultBlock error = results(messages).get(0);
                                        assertEquals("call-1", error.getId());
                                        assertTrue(
                                                ChildAgentDispatcher.text(error)
                                                        .contains("TOOL_NOT_FOUND"));
                                        return tool(
                                                "query_weather",
                                                Map.of("city", "杭州", "date", "2026-10-29"));
                                    }
                                    return text("已用正确工具完成查询。");
                                })
                        .run(new RequestDTO("feedback", "杭州明天天气", List.of()))
                        .block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(1, result.evidence().size());
    }

    @Test
    void changedInputIsAppendedToSameMainHistoryAfterCurrentToolFinishes() {
        RunControl base = RunControl.local(LimitsDTO.defaults());
        AtomicReference<String> pending = new AtomicReference<>();
        AtomicInteger revision = new AtomicInteger(1);
        RunControl control =
                new DelegatingControl(base) {
                    public String drainInput() {
                        String value = pending.getAndSet(null);
                        if (value != null) {
                            revision.incrementAndGet();
                        }
                        return value;
                    }

                    public int revision() {
                        return revision.get();
                    }
                };
        AtomicInteger turns = new AtomicInteger();
        ResultDTO result =
                supervisor(
                                (messages, tools) -> {
                                    int turn = turns.getAndIncrement();
                                    if (turn == 0) {
                                        pending.set("上海后天的天气");
                                        return tool(
                                                "query_weather",
                                                Map.of("city", "杭州", "date", "2026-10-29"));
                                    }
                                    if (turn == 1) {
                                        assertTrue(
                                                messages.stream()
                                                        .anyMatch(
                                                                m ->
                                                                        m.getTextContent()
                                                                                .equals(
                                                                                        "杭州明天的天气")));
                                        assertTrue(
                                                messages.stream()
                                                        .anyMatch(
                                                                m ->
                                                                        m.getTextContent()
                                                                                .equals(
                                                                                        "上海后天的天气")));
                                        assertEquals(1, results(messages).size());
                                        return tool(
                                                "query_weather",
                                                Map.of("city", "上海", "date", "2026-10-30"));
                                    }
                                    return text("按新条件完成：上海 2026-10-30。");
                                })
                        .run(new RequestDTO("input", "杭州明天的天气", List.of()), control)
                        .block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals("按新条件完成：上海 2026-10-30。", result.answer());
        assertTrue(
                result.evidence().stream()
                        .anyMatch(
                                e ->
                                        e.data().get("city").equals("上海")
                                                && e.data().get("date").equals("2026-10-30")));
    }

    @Test
    void mixedBatchQuestionSuspendsBeforeLaterQueryInsteadOfFailingWholeTurn() {
        AtomicInteger reads = new AtomicInteger();
        ContentBlock query =
                tool("query_weather", Map.of("city", "杭州", "date", "2026-10-29"))
                        .getContent()
                        .get(0);
        ToolUseBlock ask =
                ToolUseBlock.builder()
                        .id("ask")
                        .name("request_user_input")
                        .input(questionInput("需要继续查询吗？"))
                        .build();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) ->
                                                ChatResponse.builder()
                                                        .content(List.of(ask, query))
                                                        .finishReason("tool_calls")
                                                        .build()),
                        CLOCK,
                        LimitsDTO.defaults(),
                        new SupervisorControlTest().counting(reads));
        ResultDTO result = supervisor.run(new RequestDTO("mixed", "杭州明天天气", List.of())).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertEquals(0, reads.get());
    }

    @Test
    void duplicateCallIdsCannotLoseOneResultDuringRecovery() {
        AtomicInteger reads = new AtomicInteger();
        ContentBlock first =
                tool("query_weather", Map.of("city", "杭州", "date", "2026-10-29"))
                        .getContent()
                        .get(0);
        ContentBlock second =
                tool("query_weather", Map.of("city", "上海", "date", "2026-10-29"))
                        .getContent()
                        .get(0);
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) ->
                                                ChatResponse.builder()
                                                        .content(List.of(first, second))
                                                        .finishReason("tool_calls")
                                                        .build()),
                        CLOCK,
                        LimitsDTO.defaults(),
                        new SupervisorControlTest().counting(reads));
        ResultDTO result = supervisor.run(new RequestDTO("ids", "杭州和上海明天天气", List.of())).block();
        assertEquals(0, reads.get());
        assertEquals(Status.FAILED, result.status());
    }

    @Test
    void oldCompletedReadCanBeUsedWithoutOldRewriteAndIntentStages() {
        RunControl control = RunControl.local(LimitsDTO.defaults());
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
                        new EvidenceDTO(
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
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger turns = new AtomicInteger();
        List<String> roles = new ArrayList<>();
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            roles.add(role);
                            Model delegate =
                                    role.equals("compact")
                                            ? new OfflineScenarioModel(role)
                                            : model(
                                                    (messages, tools) ->
                                                            turns.getAndIncrement() == 0
                                                                    ? tool(
                                                                            "query_weather",
                                                                            Map.of(
                                                                                    "city",
                                                                                    "杭州",
                                                                                    "date",
                                                                                    "2026-10-29"))
                                                                    : text("18℃，来自已完成示例查询。"));
                            return new Model() {
                                public String getModelName() {
                                    return delegate.getModelName();
                                }

                                public Flux<ChatResponse> stream(
                                        List<Msg> messages,
                                        List<ToolSchema> tools,
                                        GenerateOptions options) {
                                    sends.incrementAndGet();
                                    return delegate.stream(messages, tools, options);
                                }
                            };
                        },
                        CLOCK,
                        LimitsDTO.defaults(),
                        new SupervisorControlTest().counting(reads));
        ResultDTO result =
                supervisor.run(new RequestDTO("upgrade", "杭州明天天气", List.of()), control).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(0, reads.get());
        assertEquals(2 + sends.get(), control.modelCalls(), "旧预算保留且每次新模型调用计费");
        assertFalse(
                roles.stream()
                        .anyMatch(role -> Set.of("rewrite", "intent", "answer").contains(role)));
    }

    @Test
    void plainFinalTextDoesNotCreateToolEvidenceOrRunAnAnswerModel() {
        List<String> roles = new ArrayList<>();
        String candidate = "模型自由文本中出现35℃，这段文字没有查询证据。";
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            roles.add(role);
                            return model((messages, tools) -> text(candidate));
                        },
                        CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        ResultDTO result =
                supervisor.run(new RequestDTO("no-evidence", "你好，杭州明天天气怎么样", List.of())).block();
        assertEquals(candidate, result.answer());
        assertTrue(result.evidence().isEmpty(), "原样输出不等于事实已验证，不能生成伪证据");
        assertEquals(List.of("supervisor"), roles);
    }

    @Test
    void capabilityAnswerPreservesRawTextAndNeverBecomesExecutionAuthority() {
        String candidate = "我可以查询天气。请指定城市和日期。";
        ResultDTO result =
                supervisor((messages, tools) -> text(candidate))
                        .run(new RequestDTO("capability", "你能做什么", List.of()))
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(candidate, result.answer());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.agents().isEmpty());
    }

    @Test
    void anotherCitySuccessCannotClearFailedCity() {
        AtomicInteger turns = new AtomicInteger();
        ResultDTO result =
                cityFailureSupervisor(turns)
                        .run(new RequestDTO("failure", "杭州和上海明天天气", List.of()))
                        .block();
        assertEquals(Status.PARTIAL, result.status(), result.toString());
        assertTrue(
                result.evidence().stream()
                        .anyMatch(
                                e ->
                                        e.status() == Status.FAILED
                                                && e.data().get("city").equals("上海")));
        assertTrue(
                result.evidence().stream()
                        .anyMatch(
                                e ->
                                        e.status() == Status.SUCCESS
                                                && e.data().get("city").equals("杭州")));
    }

    @Test
    void failedCitySurvivesConversationRestore() {
        RunControl base = RunControl.local(LimitsDTO.defaults());
        AtomicBoolean crash = new AtomicBoolean(true);
        RunControl control =
                new DelegatingControl(base) {
                    public void checkpoint(String key, String value) {
                        base.checkpoint(key, value);
                        if (key.equals("loop:v3.3")
                                && value != null
                                && value.contains("查询失败")
                                && crash.compareAndSet(true, false)) {
                            throw new IllegalStateException("模拟工具失败结果保存后退出");
                        }
                    }
                };
        Supervisor supervisor = cityFailureSupervisor(new AtomicInteger());
        RequestDTO request = new RequestDTO("restore", "杭州和上海明天天气", List.of());
        supervisor.run(request, control).block();
        assertFalse(crash.get());
        ResultDTO recovered = supervisor.run(request, control).block();
        assertEquals(Status.PARTIAL, recovered.status(), recovered.toString());
    }

    private Supervisor cityFailureSupervisor(AtomicInteger turns) {
        return new Supervisor(
                role ->
                        role.equals("compact")
                                ? new OfflineScenarioModel(role)
                                : model(
                                        (messages, tools) ->
                                                switch (turns.getAndIncrement()) {
                                                    case 0 ->
                                                            tool(
                                                                    "query_weather",
                                                                    Map.of(
                                                                            "city",
                                                                            "上海",
                                                                            "date",
                                                                            "2026-10-29"));
                                                    case 1 ->
                                                            tool(
                                                                    "query_weather",
                                                                    Map.of(
                                                                            "city",
                                                                            "杭州",
                                                                            "date",
                                                                            "2026-10-29"));
                                                    default -> text("上海查询失败；杭州示例18℃。");
                                                }),
                CLOCK,
                LimitsDTO.defaults(),
                new TravelTools.DataSource() {
                    public Map<String, Object> weather(String city, String date) {
                        if (city.equals("上海")) {
                            throw new IllegalStateException("上海查询失败");
                        }
                        return TravelTools.demoData().weather(city, date);
                    }

                    public List<Map<String, Object>> trains(String from, String to, String date) {
                        return List.of();
                    }
                });
    }

    @Test
    void savedFinalTextDoesNotNeedAnotherModelAttemptAfterRestart() {
        LimitsDTO limit = new LimitsDTO(Duration.ofSeconds(10), 1, 1024);
        RunControl base = RunControl.local(limit);
        AtomicBoolean crash = new AtomicBoolean(true);
        RunControl control =
                new DelegatingControl(base) {
                    public void checkpoint(String key, String value) {
                        base.checkpoint(key, value);
                        if (key.equals("loop:v3.3")
                                && value != null
                                && value.contains("已完成能力说明")
                                && crash.compareAndSet(true, false)) {
                            throw new IllegalStateException("模拟最终文本保存后退出");
                        }
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
        RequestDTO request = new RequestDTO("final", "你能做什么", List.of());
        supervisor.run(request, control).block();
        assertFalse(crash.get());
        ResultDTO result = supervisor.run(request, control).block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals("已完成能力说明", result.answer());
        assertEquals(1, sends.get());
        assertEquals(1, base.modelCalls());
    }

    @Test
    void longHistoryIsModelCompactedBeforeOrdinarySampling() {
        AtomicInteger compactions = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) -> {
                                            if (role.equals("compact")) {
                                                compactions.incrementAndGet();
                                                return text("此前讨论杭州天气，当前要求了解能力。");
                                            }
                                            assertTrue(
                                                    ContextBuilder.estimate(
                                                                    Map.of(
                                                                            "messages",
                                                                            messages,
                                                                            "tools",
                                                                            tools))
                                                            <= ContextBuilder.INPUT_LIMIT);
                                            return text("可查询天气和车票。");
                                        }),
                        CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO(
                                        "history",
                                        "你能做什么",
                                        List.of(new TurnDTO("杭州明天天气", "旧答复".repeat(500)))))
                        .block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertTrue(compactions.get() > 0);
    }

    private static ChatResponse question(String text) {
        return tool("request_user_input", questionInput(text));
    }

    private static Map<String, Object> questionInput(String text) {
        return Map.of(
                "questions", List.of(Map.of("id", "conditions", "header", "条件", "question", text)));
    }

    private static List<ToolResultBlock> results(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
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

        public IdentityDTO identity() {
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
