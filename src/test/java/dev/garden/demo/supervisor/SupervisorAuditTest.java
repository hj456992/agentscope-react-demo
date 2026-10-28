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

class SupervisorAuditTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC);
    final Request request = new Request("audit", "杭州明天的天气", List.of());

    @Test
    void correctEvidenceCannotBeReplacedByInventedWeather() {
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            Model delegate = new OfflineScenarioModel(role);
                            return new Model() {
                                public String getModelName() {
                                    return "audit";
                                }

                                public Flux<ChatResponse> stream(
                                        List<Msg> messages,
                                        List<ToolSchema> tools,
                                        GenerateOptions options) {
                                    return delegate.stream(messages, tools, options)
                                            .map(
                                                    reply -> {
                                                        if (role.equals("weather_agent")
                                                                && reply.getContent().stream()
                                                                        .noneMatch(
                                                                                ToolUseBlock.class
                                                                                        ::isInstance)) {
                                                            return ChatResponse.builder()
                                                                    .content(
                                                                            List.of(
                                                                                    TextBlock
                                                                                            .builder()
                                                                                            .text(
                                                                                                    "已查询杭州明天温度99度，暴雪")
                                                                                            .build()))
                                                                    .finishReason("stop")
                                                                    .build();
                                                        }
                                                        return reply;
                                                    });
                                }
                            };
                        },
                        clock,
                        Limits.defaults(),
                        TravelTools.demoData());
        Result result = supervisor.run(request).block();
        assertEquals(Status.SUCCESS, result.status());
        assertFalse(result.answer().contains("99"), result.answer());
        assertTrue(result.answer().contains("18"));
    }

    @Test
    void transientReadFailureRetriesOnlyFailedTool() {
        AtomicInteger calls = new AtomicInteger();
        TravelTools.DataSource source =
                new TravelTools.DataSource() {
                    public Map<String, Object> weather(String city, String date) {
                        if (calls.incrementAndGet() == 1)
                            throw new java.io.UncheckedIOException(
                                    new java.net.ConnectException("temporary"));
                        return TravelTools.demoData().weather(city, date);
                    }

                    public List<Map<String, Object>> trains(String from, String to, String date) {
                        return List.of();
                    }
                };
        Result result =
                new Supervisor(OfflineScenarioModel::new, clock, Limits.defaults(), source)
                        .run(request)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(2, calls.get());
        assertEquals(1, result.evidence().size(), "尝试错误不是最终失败证据");
    }

    @Test
    void correctedToolAttemptReplacesOldFailureOutcome() {
        RunContext context = new RunContext(request, Limits.defaults());
        context.decision = new Decision(Intent.WEATHER, "杭州", "", "", "2026-10-29", 0);
        TravelTools tools = new TravelTools(context, TravelTools.demoData());
        tools.weather("上海", "2026-10-29");
        tools.weather("杭州", "2026-10-29");
        assertEquals(Status.SUCCESS, context.outcome());
    }
}
