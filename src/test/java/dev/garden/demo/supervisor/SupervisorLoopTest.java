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

    @Test void originalInputReachesMainModelAndCapabilityAnswerNeedsNoChild() {
        List<String> roles = new ArrayList<>();
        String question = "你能帮我做什么？";
        Supervisor supervisor = new Supervisor(role -> {
            roles.add(role);
            return model((messages, tools) -> {
                assertTrue(messages.stream().anyMatch(m -> m.getRole() == MsgRole.USER && question.equals(m.getTextContent())));
                return text("我可以查询天气、火车票和规划行程。");
            });
        }, CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new Request("loop", question, List.of())).block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(List.of("supervisor"), roles);
        assertTrue(result.agents().isEmpty());
        assertEquals(question, result.rewrittenQuery());
    }

    @Test void clarificationEndsWithoutCallingAChild() {
        Supervisor supervisor = new Supervisor(role -> model((messages, tools) ->
                tool("ask_user", Map.of("question", "请问要查询哪个城市的天气？"))),
                CLOCK, Limits.defaults(), TravelTools.demoData());
        Result result = supervisor.run(new Request("loop", "明天天气怎么样", List.of())).block();
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
        Result result = supervisor.run(new Request("loop", "杭州明天的天气", List.of())).block();
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
        Result result = supervisor.run(new Request("two", "杭州和上海明天的天气", List.of())).block();
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
        Result result = supervisor.run(new Request("feedback", "杭州明天的天气", List.of())).block();
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
        Result result = supervisor.run(new Request("input", "杭州明天的天气", List.of()), control).block();
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
