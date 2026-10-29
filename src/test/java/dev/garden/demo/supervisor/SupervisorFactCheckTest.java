package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 最终回答的确定性事实校验默认不增加模型调用，也不把普通文本误判为冲突。 */
class SupervisorFactCheckTest {
    private static final SupervisorPolicy POLICY =
            SupervisorPolicy.defaults()
                    .withFactCheck(
                            new SupervisorPolicy.FactCheckPolicy(
                                    true, false, Set.of("query_weather")));

    @Test
    void unsupportedTemperatureKeepsTheAnswerButDowngradesAndNotesIt() {
        AtomicInteger calls = new AtomicInteger();
        ResultDTO result = run(calls, "杭州今天99℃。");
        assertEquals(2, calls.get());
        assertEquals(Status.PARTIAL, result.status());
        assertTrue(result.answer().contains("99℃"));
        assertTrue(result.answer().contains("部分数值未逐项核实：99℃"));
        assertTrue(result.events().stream().anyMatch(event -> event.contains("FACT_UNSUPPORTED")));
    }

    @Test
    void supportedTemperatureKeepsTheModelAnswerAndSuccessStatus() {
        AtomicInteger calls = new AtomicInteger();
        ResultDTO result = run(calls, "杭州今天18℃，多云。");
        assertEquals(2, calls.get());
        assertEquals(Status.SUCCESS, result.status());
        assertEquals("杭州今天18℃，多云。", result.answer());
        assertFalse(result.events().stream().anyMatch(event -> event.contains("FACT_UNSUPPORTED")));
    }

    private static ResultDTO run(AtomicInteger calls, String answer) {
        Model model =
                new Model() {
                    public String getModelName() {
                        return "fact-check-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    int call = calls.getAndIncrement();
                                    if (call == 0) {
                                        return Flux.just(
                                                tool(
                                                        "query_weather",
                                                        Map.of(
                                                                "city",
                                                                "杭州",
                                                                "date",
                                                                "2026-10-29")));
                                    }
                                    return Flux.just(text(answer));
                                });
                    }
                };
        LimitsDTO limits = LimitsDTO.defaults();
        Supervisor supervisor =
                new Supervisor(
                        role -> model,
                        Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC),
                        limits,
                        TravelTools.demoData());
        return supervisor
                .run(
                        new RequestDTO("fact", "杭州天气", List.of()),
                        RunControl.local(limits, POLICY))
                .block();
    }

    private static ChatResponse tool(String name, Map<String, Object> input) {
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

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }
}
