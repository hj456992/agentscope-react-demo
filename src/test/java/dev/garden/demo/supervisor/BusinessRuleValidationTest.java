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
import java.util.concurrent.atomic.AtomicReference;

/** 业务语义违规必须在调用数据源前返回结构化内部错误，并允许模型修正参数。 */
class BusinessRuleValidationTest {
    private static final SupervisorPolicy POLICY =
            SupervisorPolicy.defaults()
                    .withBusiness(SupervisorPolicy.BusinessPolicy.enabled(30));

    @Test
    void pastDateReturnsBusinessViolationWithoutReadingTheDataSource() {
        AtomicInteger reads = new AtomicInteger();
        RunContext context = context(POLICY);
        new TravelTools(context, counting(reads)).weather("杭州", "2026-10-28");
        assertEquals(0, reads.get());
        EvidenceDTO evidence = onlyEvidence(context);
        assertEquals(Status.INVALID_INPUT, evidence.status());
        assertEquals("BUSINESS_RULE_VIOLATION", evidence.data().get("errorCode"));
        assertEquals("DATE_IN_PAST", evidence.data().get("ruleId"));
        assertEquals("date", evidence.data().get("field"));
        assertNotNull(evidence.data().get("suggestion"));
    }

    @Test
    void sameCityIsAStructuredBusinessViolation() {
        AtomicInteger reads = new AtomicInteger();
        RunContext context = context(POLICY);
        new TravelTools(context, counting(reads)).trains("杭州", "杭州", "2026-10-29");
        assertEquals(0, reads.get());
        EvidenceDTO evidence = onlyEvidence(context);
        assertEquals(Status.INVALID_INPUT, evidence.status());
        assertEquals("SAME_CITY", evidence.data().get("ruleId"));
        assertEquals("to", evidence.data().get("field"));
    }

    @Test
    void dateBeyondTheConfiguredWindowIsRejected() {
        AtomicInteger reads = new AtomicInteger();
        RunContext context = context(POLICY);
        new TravelTools(context, counting(reads)).weather("杭州", "2026-11-29");
        assertEquals(0, reads.get());
        EvidenceDTO evidence = onlyEvidence(context);
        assertEquals(Status.INVALID_INPUT, evidence.status());
        assertEquals("DATE_TOO_FAR", evidence.data().get("ruleId"));
    }

    @Test
    void validTomorrowAndRouteReachTheDataSource() {
        AtomicInteger reads = new AtomicInteger();
        RunContext context = context(POLICY);
        TravelTools tools = new TravelTools(context, counting(reads));
        tools.weather("杭州", "2026-10-30");
        tools.trains("杭州", "上海", "2026-10-30");
        assertEquals(2, reads.get());
        assertEquals(2, context.evidence().size());
        assertTrue(context.evidence().stream().allMatch(e -> e.status() == Status.SUCCESS));
    }

    @Test
    void schemaFailureStaysGenericAndDoesNotBecomeABusinessRule() {
        AtomicInteger reads = new AtomicInteger();
        RunContext context = context(POLICY);
        new TravelTools(context, counting(reads)).weather("", "2026-10-29");
        assertEquals(0, reads.get());
        EvidenceDTO evidence = onlyEvidence(context);
        assertEquals(Status.INVALID_INPUT, evidence.status());
        assertFalse(evidence.data().containsKey("errorCode"));
    }

    @Test
    void disabledBusinessPolicyKeepsExistingBehavior() {
        AtomicInteger reads = new AtomicInteger();
        RunContext context = context(SupervisorPolicy.defaults());
        new TravelTools(context, counting(reads)).weather("杭州", "2026-10-28");
        assertEquals(1, reads.get());
        assertEquals(Status.SUCCESS, onlyEvidence(context).status());
    }

    @Test
    void scriptedModelReceivesTheViolationAndCorrectsTheDate() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<Msg>> secondTurn = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "business-rule-script";
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
                                                                "2026-10-28")));
                                    }
                                    if (call == 1) {
                                        secondTurn.set(List.copyOf(messages));
                                        return Flux.just(
                                                tool(
                                                        "query_weather",
                                                        Map.of(
                                                                "city",
                                                                "杭州",
                                                                "date",
                                                                "2026-10-29")));
                                    }
                                    return Flux.just(text("杭州今天多云，18℃。"));
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
        RunControl control = RunControl.local(limits, POLICY);
        ResultDTO result =
                supervisor
                        .run(new RequestDTO("business", "杭州天气", List.of()), control)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(1, reads.get());
        assertTrue(
                result.events().stream()
                        .anyMatch(event -> event.contains("BUSINESS_RULE_VIOLATION")));
        assertTrue(
                secondTurn.get().stream()
                        .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .flatMap(toolResult -> toolResult.getOutput().stream())
                        .anyMatch(
                                block ->
                                        block instanceof TextBlock text
                                                && text.getText()
                                                        .contains("BUSINESS_RULE_VIOLATION")));
        assertEquals(Status.SUCCESS, result.evidence().get(result.evidence().size() - 1).status());
    }

    private static RunContext context(SupervisorPolicy policy) {
        RunContext context =
                new RunContext(
                        new RequestDTO("business", "查询", List.of()),
                        LimitsDTO.defaults(),
                        RunControl.local(LimitsDTO.defaults(), policy));
        context.today = LocalDate.of(2026, 10, 29);
        return context;
    }

    private static EvidenceDTO onlyEvidence(RunContext context) {
        assertEquals(1, context.evidence().size());
        return context.evidence().get(0);
    }

    private static TravelTools.DataSource counting(AtomicInteger reads) {
        return new TravelTools.DataSource() {
            public Map<String, Object> weather(String city, String date) {
                reads.incrementAndGet();
                return Map.of("temperature", 18, "condition", "多云");
            }

            public List<Map<String, Object>> trains(String from, String to, String date) {
                reads.incrementAndGet();
                return List.of(Map.of("number", "G-TEST", "seat", "二等座", "price", 80));
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
