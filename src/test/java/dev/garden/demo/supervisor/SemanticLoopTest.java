package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.support.SemanticLoopGuard;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 语义循环检测在工具和协作目标上的提醒、询问与关闭行为。 */
class SemanticLoopTest {
    private static final LimitsDTO LIMITS = LimitsDTO.defaults();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC);
    private static final SupervisorPolicy SEMANTIC_POLICY =
            SupervisorPolicy.defaults()
                    .withLoop(new SupervisorPolicy.LoopPolicy(true, 3, true, true, 12, 0.85, 2));

    @Test
    void similarToolArgumentsWarnThenAskForConfirmation() {
        RunContext context = context(SEMANTIC_POLICY);
        assertEquals(
                SemanticLoopGuard.Decision.PROCEED,
                context.checkSemanticToolLoop("query_weather", weather("2026-11-02"))
                        .decision());
        SemanticLoopGuard.Result second =
                context.checkSemanticToolLoop("query_weather", weather("2026-11-03"));
        assertEquals(SemanticLoopGuard.Decision.WARN, second.decision());
        assertTrue(second.similarity() >= 0.85);
        SemanticLoopGuard.Result third =
                context.checkSemanticToolLoop("query_weather", weather("2026-11-04"));
        assertEquals(SemanticLoopGuard.Decision.WARN, third.decision());
        SemanticLoopGuard.Result fourth =
                context.checkSemanticToolLoop("query_weather", weather("2026-11-05"));
        assertEquals(SemanticLoopGuard.Decision.ASK, fourth.decision());
    }

    @Test
    void similarAgentGoalIsDetectedAcrossDifferentCallees() {
        RunContext context = context(SEMANTIC_POLICY);
        assertEquals(
                SemanticLoopGuard.Decision.PROCEED,
                context.checkSemanticAgentGoal(
                                "/root/weather", "请帮我查询杭州明天白天的天气")
                        .decision());
        SemanticLoopGuard.Result result =
                context.checkSemanticAgentGoal(
                        "/root/train", "请帮我查询杭州明天白天的天气预报");
        assertEquals(SemanticLoopGuard.Decision.WARN, result.decision());
        assertEquals("/root/weather", result.matchedKey());
    }

    @Test
    void disabledSwitchDoesNotWriteSemanticHistory() {
        RunContext context = context(SupervisorPolicy.defaults());
        for (String date : List.of("2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05")) {
            assertEquals(
                    SemanticLoopGuard.Decision.PROCEED,
                    context.checkSemanticToolLoop("query_weather", weather(date)).decision());
        }
        assertNull(context.control.checkpoint(SemanticLoopGuard.KEY));
    }

    @Test
    void warningIsReturnedToTheModelWithoutExecutingTheRepeatedQuery() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> warning = new AtomicReference<>("");
        Model model =
                new Model() {
                    public String getModelName() {
                        return "semantic-warn";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int call = calls.getAndIncrement();
                        if (call < 2) {
                            return Flux.just(
                                    tool("query_weather", weather("2026-11-0" + (call + 2))));
                        }
                        for (Msg message : messages) {
                            for (ToolResultBlock result :
                                    message.getContentBlocks(ToolResultBlock.class)) {
                                if (result.getName().equals("query_weather")) {
                                    String output = output(result);
                                    if (output.contains("LOOP_DETECTED")) {
                                        warning.set(output);
                                    }
                                }
                            }
                        }
                        return Flux.just(text("已停止重复查询。"));
                    }
                };
        Supervisor supervisor =
                new Supervisor(
                        role -> role.equals("compact") ? compactModel() : model,
                        CLOCK,
                        LIMITS,
                        dataSource(reads));
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("semantic-warn", "查询杭州天气", List.of()),
                                RunControl.local(LIMITS, SEMANTIC_POLICY))
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(1, reads.get());
        assertTrue(warning.get().contains("LOOP_DETECTED"));
        assertTrue(
                result.events().stream().anyMatch(event -> event.contains("语义循环检测")));
    }

    @Test
    void repeatedSimilarQueriesPauseTheRunForUserConfirmation() {
        AtomicInteger calls = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "semantic-ask";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        int call = calls.getAndIncrement();
                        return Flux.just(
                                tool("query_weather", weather("2026-11-0" + (call + 2))));
                    }
                };
        Supervisor supervisor =
                new Supervisor(
                        role -> role.equals("compact") ? compactModel() : model,
                        CLOCK,
                        LIMITS,
                        dataSource(new AtomicInteger()));
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("semantic-ask", "查询杭州天气", List.of()),
                                RunControl.local(LIMITS, SEMANTIC_POLICY))
                        .block();
        assertEquals(Status.NEEDS_INPUT, result.status(), result.events() + " / " + result.answer());
        assertEquals(4, calls.get());
        assertTrue(result.answer().contains("相似目标"));
    }

    private static RunContext context(SupervisorPolicy policy) {
        return new RunContext(
                new RequestDTO("semantic", "查询杭州天气", List.of()),
                LIMITS,
                RunControl.local(LIMITS, policy));
    }

    private static Map<String, Object> weather(String date) {
        return new LinkedHashMap<>(Map.of("city", "杭州", "date", date));
    }

    private static TravelTools.DataSource dataSource(AtomicInteger reads) {
        return new TravelTools.DataSource() {
            public Map<String, Object> weather(String city, String date) {
                reads.incrementAndGet();
                return Map.of("temperature", 18, "condition", "多云");
            }

            public List<Map<String, Object>> trains(
                    String from, String to, String date) {
                return List.of();
            }
        };
    }

    private static Model compactModel() {
        return new Model() {
            public String getModelName() {
                return "semantic-compact";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(text("已压缩会话记录。"));
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

    private static String output(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}
