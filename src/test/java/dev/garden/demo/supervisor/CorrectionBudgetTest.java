package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 纠错预算按工具与错误类型累计，支持重启恢复，并在耗尽时终止当前轮次。 */
class CorrectionBudgetTest {
    private static final SupervisorPolicy POLICY =
            SupervisorPolicy.defaults()
                    .withCorrection(new SupervisorPolicy.CorrectionPolicy(true, 2, 2, 2, 6));

    @Test
    void twoConsecutiveSchemaErrorsDisableTheTool() {
        RunContext context = context(POLICY, RunControl.local(LimitsDTO.defaults(), POLICY));
        assertEquals(
                CorrectionDecision.PROCEED,
                context.correction(CorrectionType.INVALID_SCHEMA, "query_weather"));
        assertEquals(
                CorrectionDecision.DISABLE_TOOL,
                context.correction(CorrectionType.INVALID_SCHEMA, "query_weather"));
        assertTrue(context.toolDisabled("query_weather"));
    }

    @Test
    void aSuccessfulCallResetsTheConsecutiveSchemaStreak() {
        RunContext context = context(POLICY, RunControl.local(LimitsDTO.defaults(), POLICY));
        assertEquals(
                CorrectionDecision.PROCEED,
                context.correction(CorrectionType.INVALID_SCHEMA, "query_weather"));
        context.correctionSuccess("query_weather");
        assertEquals(
                CorrectionDecision.PROCEED,
                context.correction(CorrectionType.INVALID_SCHEMA, "query_weather"));
        assertFalse(context.toolDisabled("query_weather"));
    }

    @Test
    void totalCorrectionBudgetStopsFurtherAttempts() {
        SupervisorPolicy policy =
                SupervisorPolicy.defaults()
                        .withCorrection(
                                new SupervisorPolicy.CorrectionPolicy(true, 2, 2, 2, 3));
        RunControl control = RunControl.local(LimitsDTO.defaults(), policy);
        RunContext context = context(policy, control);
        assertEquals(
                CorrectionDecision.PROCEED,
                context.correction(CorrectionType.INVALID_SCHEMA, "tool-one"));
        assertEquals(
                CorrectionDecision.PROCEED,
                context.correction(CorrectionType.INVALID_SCHEMA, "tool-two"));
        assertEquals(
                CorrectionDecision.PROCEED,
                context.correction(CorrectionType.INVALID_SCHEMA, "tool-three"));
        assertEquals(
                CorrectionDecision.FAIL,
                context.correction(CorrectionType.INVALID_SCHEMA, "tool-four"));
    }

    @Test
    void countsAndDisabledToolsSurviveRestart() {
        RunControl control = RunControl.local(LimitsDTO.defaults(), POLICY);
        RunContext first = context(POLICY, control);
        assertEquals(
                CorrectionDecision.PROCEED,
                first.correction(CorrectionType.INVALID_SCHEMA, "query_weather"));
        RunContext restarted = context(POLICY, control);
        assertEquals(
                CorrectionDecision.DISABLE_TOOL,
                restarted.correction(CorrectionType.INVALID_SCHEMA, "query_weather"));
        assertTrue(restarted.toolDisabled("query_weather"));
    }

    @Test
    void nonexistentToolForcesClarificationThenFailsTheTurn() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "not-found-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    // 工具表扩大后第三次模型边界会先触发上下文压缩；
                                    // 压缩请求不是一次工具尝试，返回摘要而不计入调用次数。
                                    if (compaction(messages)) {
                                        return Flux.just(
                                                text("已完成必要查询，仍需继续核对能力。"));
                                    }
                                    calls.incrementAndGet();
                                    return Flux.just(tool("not_registered", Map.of()));
                                });
                    }
                };
        LimitsDTO limits = LimitsDTO.defaults();
        Supervisor supervisor =
                new Supervisor(
                        role -> model,
                        Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC),
                        limits,
                        counting(reads));
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("missing", "查询", List.of()),
                                RunControl.local(limits, POLICY))
                        .block();
        assertEquals(Status.FAILED, result.status());
        assertEquals(3, calls.get());
        assertEquals(0, reads.get());
        assertTrue(result.answer().contains("能力暂时不可用"), result.status() + " / " + result.answer() + " / " + result.events());
    }

    @Test
    void disabledToolIsRemovedFromTheNextToolSnapshotAndNeverExecuted() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        List<List<ToolSchema>> toolViews = new ArrayList<>();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "schema-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () -> {
                                    toolViews.add(List.copyOf(tools));
                                    int call = calls.incrementAndGet();
                                    if (call <= 2) {
                                        return Flux.just(
                                                tool(
                                                        "query_weather",
                                                        Map.of("city", "杭州")));
                                    }
                                    return Flux.just(text("本轮结束。"));
                                });
                    }
                };
        LimitsDTO limits = LimitsDTO.defaults();
        Supervisor supervisor =
                new Supervisor(
                        role -> model,
                        Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC),
                        limits,
                        counting(reads));
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("schema", "查询", List.of()),
                                RunControl.local(limits, POLICY))
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(0, reads.get());
        assertEquals(3, calls.get());
        assertTrue(result.events().stream().anyMatch(event -> event.contains("工具已暂停")));
        assertTrue(
                toolViews.get(2).stream().noneMatch(tool -> tool.getName().equals("query_weather")));
    }

    private static RunContext context(SupervisorPolicy policy, RunControl control) {
        RunContext context = new RunContext(new RequestDTO("correction", "查询", List.of()), LimitsDTO.defaults(), control);
        context.today = LocalDate.of(2026, 10, 29);
        return context;
    }

    private static TravelTools.DataSource counting(AtomicInteger reads) {
        return new TravelTools.DataSource() {
            public Map<String, Object> weather(String city, String date) {
                reads.incrementAndGet();
                return Map.of("temperature", 18);
            }

            public List<Map<String, Object>> trains(String from, String to, String date) {
                reads.incrementAndGet();
                return List.of();
            }
        };
    }

    private static boolean compaction(List<Msg> messages) {
        if (messages.isEmpty()) {
            return false;
        }
        String text = messages.get(messages.size() - 1).getTextContent();
        return text != null && text.contains("CONTEXT CHECKPOINT COMPACTION");
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
