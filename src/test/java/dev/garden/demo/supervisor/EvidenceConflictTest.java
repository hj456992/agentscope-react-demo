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

/** 同一条件的不同结果必须保留为冲突证据，最终答复降级并展示证据。 */
class EvidenceConflictTest {
    @Test
    void conflictingWeatherResultsAreBothRetainedAndTheAnswerIsDowngraded() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "conflict-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    int call = calls.getAndIncrement();
                                    if (call < 2) {
                                        return Flux.just(
                                                tool(
                                                        "query_weather",
                                                        Map.of(
                                                                "city",
                                                                "杭州",
                                                                "date",
                                                                "2026-10-29")));
                                    }
                                    return Flux.just(text("杭州今天18℃。"));
                                });
                    }
                };
        SupervisorPolicy policy =
                SupervisorPolicy.defaults()
                        .withFactCheck(
                                new SupervisorPolicy.FactCheckPolicy(
                                        true, false, Set.of("query_weather")));
        LimitsDTO limits = LimitsDTO.defaults();
        Supervisor supervisor =
                new Supervisor(
                        role -> model,
                        Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC),
                        limits,
                        alternatingWeather(reads));
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("conflict", "杭州天气", List.of()),
                                RunControl.local(limits, policy))
                        .block();
        assertEquals(2, reads.get());
        assertEquals(2, result.evidence().size());
        assertEquals(Status.PARTIAL, result.status());
        assertTrue(result.answer().contains("18℃"));
        assertTrue(result.answer().contains("22℃"));
        assertTrue(result.events().stream().anyMatch(event -> event.contains("FACT_CONFLICT")));
    }

    private static TravelTools.DataSource alternatingWeather(AtomicInteger reads) {
        return new TravelTools.DataSource() {
            /** 每次读取都宣告新的数据快照，避免成功结果按相同参数回放。 */
            @Override
            public String version() {
                return "conflict-v" + reads.get();
            }

            public Map<String, Object> weather(String city, String date) {
                int value = reads.incrementAndGet() == 1 ? 18 : 22;
                return Map.of("temperature", value, "condition", value == 18 ? "多云" : "晴");
            }

            public List<Map<String, Object>> trains(String from, String to, String date) {
                return List.of();
            }
        };
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
