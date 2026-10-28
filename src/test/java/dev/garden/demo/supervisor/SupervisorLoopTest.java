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

    @Test void originalInputReachesMainModelAndCapabilityAnswerIsDeclared() {
        List<String> roles = new ArrayList<>();
        String question = "你能帮我做什么？";
        Supervisor supervisor = new Supervisor(role -> {
            roles.add(role);
            return model((messages, tools) -> {
                assertTrue(messages.stream().anyMatch(m -> m.getRole() == MsgRole.USER && question.equals(m.getTextContent())));
                // 非事实答复必须由 answer_directly 声明类别，纯文本会被判为不可信。
                return tool("answer_directly", Map.of("kind", "CAPABILITY", "text", "我可以查询天气、火车票和规划行程。"));
            });
        }, CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("loop", question, List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(List.of("supervisor"), roles);
        assertTrue(result.agents().isEmpty());
        assertTrue(result.answer().contains("规划行程"));
        assertEquals(question, result.rewrittenQuery());
    }

    @Test void plainTextWithoutEvidenceIsNotAcceptedAsFactualAnswer() {
        String question = "你好，杭州明天的天气怎么样";
        Supervisor supervisor = new Supervisor(role -> textOnly("杭州明天晴，25℃。"), CLOCK,
                Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("probe", question, List.of())).block();
        assertEquals(Status.FAILED, result.status(), result.toString());
        assertTrue(result.evidence().isEmpty());
        assertFalse(result.answer().contains("25"));
    }

    @Test void unrelatedRequestIsDeclaredWithoutCallingAChild() {
        Supervisor supervisor = new Supervisor(role -> model((messages, tools) ->
                tool("answer_directly", Map.of("kind", "UNRELATED", "text", "请提出天气、火车票或行程规划问题。"))),
                CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("unrelated", "帮我写一首诗", List.of())).block();
        assertEquals(Status.UNKNOWN_INTENT, result.status(), result.toString());
        assertTrue(result.agents().isEmpty());
    }

    @Test void clarificationStillConsumesInputQueuedAfterTheReply() {
        RunControl base = RunControl.local(Limits.defaults());
        AtomicInteger drains = new AtomicInteger();
        AtomicInteger revision = new AtomicInteger(1);
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        RunControl control = new DelegatingControl(base) {
            // 第 1、2 次读取没有新输入，输入在澄清答复之后才到；旧实现会直接返回 NEEDS_INPUT。
            public String drainInput() {
                if (drains.incrementAndGet() < 3 || delivered.getAndSet(true)) return null;
                revision.incrementAndGet();
                return "杭州明天的天气";
            }
            public int revision() { return revision.get(); }
        };
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor = new Supervisor(role -> role.equals("supervisor")
                ? model((messages, tools) -> {
                    int turn = turns.getAndIncrement();
                    if (turn == 0) return tool("ask_user", Map.of("question", "请问要查询哪个城市的天气？"));
                    if (turn == 1) {
                        assertTrue(messages.stream().anyMatch(m -> "杭州明天的天气".equals(m.getTextContent())));
                        return tool("weather_agent", Map.of("city", "杭州", "date", "2026-10-29"));
                    }
                    return text("已查到。");
                }) : new OfflineScenarioModel(role), CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("clarify", "明天天气怎么样", List.of()), control).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals("杭州", result.evidence().get(0).data().get("city"));
    }

    @Test void longHistoryTrimsOldAnswersButKeepsToolPairing() {
        RunContext context = new RunContext(new ChatInput("trim", "杭州明天的天气", List.of()), Limits.defaults());
        LoopConversation conversation = new LoopConversation(context);
        conversation.messages.add(Msg.builder().role(MsgRole.ASSISTANT)
                .content(List.of(ToolUseBlock.builder().id("call-1").name("weather_agent")
                        .input(Map.of("city", "杭州", "date", "2026-10-29")).content("{}").build())).build());
        conversation.messages.add(Msg.builder().role(MsgRole.TOOL)
                .content(List.of(ToolResultBlock.text("{}").withIdAndName("call-1", "weather_agent"))).build());
        for (int i = 0; i < 5; i++)
            conversation.messages.add(Msg.builder().role(MsgRole.ASSISTANT)
                    .textContent("第" + i + "次回答" + "很长的回答".repeat(120)).build());
        List<Msg> view = conversation.historyFor(2000);
        assertTrue(ContextBuilder.estimate(view) < ContextBuilder.estimate(conversation.messages));
        assertTrue(view.stream().anyMatch(m -> !m.getContentBlocks(ToolUseBlock.class).isEmpty()),
                "工具请求不能因为裁剪被删除");
        assertTrue(view.stream().anyMatch(m -> LoopConversation.TRIMMED.equals(m.getTextContent())),
                "旧回答应被替换为裁剪标记");
    }

    @Test void legacyProgressEvidenceIsReusedByTheNewLoop() {
        Result legacy = new Result(Status.SUCCESS, "杭州明天的天气", "", Intent.WEATHER, "旧回答",
                List.of("天气查询"),
                List.of(new Evidence("query_weather", Status.SUCCESS, "demo", Map.of(
                        "city", "杭州", "date", "2026-10-29",
                        "weather", Map.of("condition", "晴", "temperature", 18)))),
                List.of());
        Map<String, String> store = new java.util.concurrent.ConcurrentHashMap<>();
        store.put("progress", json(legacy));
        RunControl base = RunControl.local(Limits.defaults());
        RunControl control = new DelegatingControl(base) {
            public String checkpoint(String key) { return store.get(key); }
            public void checkpoint(String key, String value) {
                if (value == null) store.remove(key); else store.put(key, value);
            }
        };
        Supervisor supervisor = new Supervisor(role -> role.equals("supervisor")
                ? textOnly("杭州明天晴。") : new OfflineScenarioModel(role),
                CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("legacy", "杭州明天的天气", List.of()), control).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertFalse(result.evidence().isEmpty());
        assertTrue(result.answer().contains("18"));
    }

    @Test void verifiedModelWordingIsIncludedInTheAnswer() {
        Result result = Supervisor.offline(CLOCK)
                .run(new ChatInput("verified", "杭州明天的天气", List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertTrue(result.answer().contains("模型答复："), result.answer());
        assertTrue(result.answer().contains("18"));
    }

    static Model textOnly(String reply) {
        return model((messages, tools) -> text(reply));
    }


    @Test void clarificationEndsWithoutCallingAChild() {
        Supervisor supervisor = new Supervisor(role -> model((messages, tools) ->
                tool("ask_user", Map.of("question", "请问要查询哪个城市的天气？"))),
                CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("loop", "明天天气怎么样", List.of())).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.answer().contains("城市"));
        assertTrue(result.agents().isEmpty());
    }

    @Test void mainModelChoosesChildAndReceivesPairedToolResult() {
        AtomicInteger mainCalls = new AtomicInteger();
        Supervisor supervisor = new Supervisor(role -> role.equals("supervisor")
                ? model((messages, tools) -> {
                    if (mainCalls.getAndIncrement() == 0)
                        return tool("weather_agent", Map.of("city", "杭州", "date", "2026-10-29"));
                    assertTrue(messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .anyMatch(r -> "call-1".equals(r.getId()) && "weather_agent".equals(r.getName())));
                    return text("天气已查到。");
                }) : new OfflineScenarioModel(role), CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("loop", "杭州明天的天气", List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(2, mainCalls.get());
        assertTrue(result.answer().contains("18℃"));
    }

    @Test void twoCallsToSameChildKeepDifferentCitiesAndBothFacts() {
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor = new Supervisor(role -> role.equals("supervisor")
                ? model((messages, tools) -> {
                    int turn = turns.getAndIncrement();
                    if (turn == 0) return tool("weather_agent", Map.of("city", "杭州", "date", "2026-10-29"));
                    if (turn == 1) return tool("weather_agent", Map.of("city", "上海", "date", "2026-10-29"));
                    return text("两地天气已查到。");
                }) : new OfflineScenarioModel(role), CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("two", "杭州和上海明天的天气", List.of())).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(2, result.evidence().size());
        assertTrue(result.answer().contains("18℃"));
        assertTrue(result.answer().contains("22℃"));
    }

    @Test void unknownMainToolIsFeedbackAndCanChooseValidChildNext() {
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor = new Supervisor(role -> role.equals("supervisor")
                ? model((messages, tools) -> {
                    int turn = turns.getAndIncrement();
                    if (turn == 0) return tool("unknown_agent", Map.of());
                    if (turn == 1) {
                        assertTrue(messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .anyMatch(r -> ChildAgentDispatcher.text(r).contains("TOOL_NOT_FOUND")));
                        return tool("weather_agent", Map.of("city", "杭州", "date", "2026-10-29"));
                    }
                    return text("完成");
                }) : new OfflineScenarioModel(role), CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("feedback", "杭州明天的天气", List.of())).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertEquals(List.of("天气查询"), result.agents());
    }

    @Test void changedInputIsAppendedToSameMainHistoryAfterChildFinishes() {
        RunControl base = RunControl.local(Limits.defaults());
        java.util.concurrent.atomic.AtomicReference<String> pending = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger revision = new AtomicInteger(1);
        RunControl control = new DelegatingControl(base) {
            public String drainInput() { String input = pending.getAndSet(null); if (input != null) revision.incrementAndGet(); return input; }
            public int revision() { return revision.get(); }
        };
        AtomicInteger mainTurns = new AtomicInteger();
        Supervisor supervisor = new Supervisor(role -> {
            if (!role.equals("supervisor")) return new OfflineScenarioModel(role);
            return model((messages, tools) -> {
                int turn = mainTurns.getAndIncrement();
                if (turn == 0) { pending.set("上海后天的天气"); return tool("weather_agent", Map.of("city", "杭州", "date", "2026-10-29")); }
                if (turn == 1) {
                    assertTrue(messages.stream().anyMatch(m -> "杭州明天的天气".equals(m.getTextContent())));
                    assertTrue(messages.stream().anyMatch(m -> "上海后天的天气".equals(m.getTextContent())));
                    assertTrue(messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).count() == 1);
                    return tool("weather_agent", Map.of("city", "上海", "date", "2026-10-30"));
                }
                return text("按新条件完成");
            });
        }, CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new ChatInput("input", "杭州明天的天气", List.of()), control).block();
        assertEquals(Status.SUCCESS, result.status(), result.toString());
        assertFalse(result.answer().contains("杭州"));
        assertTrue(result.answer().contains("上海 2026-10-30"));
        assertEquals(3, mainTurns.get());
    }

    static class DelegatingControl implements RunControl {
        final RunControl base;
        DelegatingControl(RunControl base) { this.base = base; }
        public String runId() { return base.runId(); }
        public int revision() { return base.revision(); }
        public AccessPolicy.Identity identity() { return base.identity(); }
        public void checkActive() { base.checkActive(); }
        public Duration remaining() { return base.remaining(); }
        public void reserveModel(int value) { base.reserveModel(value); }
        public void reserveModelStep(String key, int value, int max) { base.reserveModelStep(key, value, max); }
        public String checkpoint(String key) { return base.checkpoint(key); }
        public void checkpoint(String key, String value) { base.checkpoint(key,value); }
        public String drainInput() { return base.drainInput(); }
        public void event(String text) { base.event(text); }
        public int modelCalls() { return base.modelCalls(); }
    }

    interface Reply { ChatResponse next(List<Msg> messages, List<ToolSchema> tools); }
    static Model model(Reply reply) { return new Model() {
        public String getModelName() { return "loop-test"; }
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> Flux.just(reply.next(messages, tools)));
        }
    }; }
    static ChatResponse text(String value) { return ChatResponse.builder().content(List.of(TextBlock.builder().text(value).build())).finishReason("stop").build(); }
    static ChatResponse tool(String name, Map<String,Object> input) { return ChatResponse.builder().content(List.of(ToolUseBlock.builder().id("call-1").name(name).input(input).content(json(input)).build())).finishReason("tool_calls").build(); }
}
