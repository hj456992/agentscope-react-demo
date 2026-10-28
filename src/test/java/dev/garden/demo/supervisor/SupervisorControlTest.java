package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.service.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.config.*;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

class SupervisorControlTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC);
    final RequestDTO request = new RequestDTO("control", "杭州明天的天气", List.of());

    Model model(Supplier<ChatResponse> next) {
        return new Model() {
            public String getModelName() {
                return "adversarial";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> Flux.just(next.get()));
            }
        };
    }

    ChatResponse tool(String name, Map<String, Object> input) {
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

    ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }

    Supervisor with(Function<String, Model> models, TravelTools.DataSource data) {
        return new Supervisor(models, clock, LimitsDTO.defaults(), data);
    }

    @Test
    void runtimeDoesNotGuessCityFromUserText() {
        AtomicInteger reads = new AtomicInteger(), turns = new AtomicInteger();
        ResultDTO result = with(role -> model(() -> turns.getAndIncrement() == 0
                ? tool("query_weather", Map.of("city", "上海", "date", "2026-10-29"))
                : text("已按工具请求查询上海。")), counting(reads)).run(request).block();
        assertEquals(1, reads.get());
        assertEquals("上海", result.evidence().get(0).data().get("city"));
        assertEquals("已按工具请求查询上海。", result.answer());
    }

    @Test
    void nonexistentToolIsReturnedToModelAndBoundedAcrossNewCallIds() {
        AtomicInteger calls = new AtomicInteger();
        Supervisor supervisor = new Supervisor(role -> model(() -> {
            calls.incrementAndGet();
            return tool("not_registered", Map.of());
        }), clock, new LimitsDTO(Duration.ofSeconds(30), 2, 1024), TravelTools.demoData());
        ResultDTO result = supervisor.run(request).block();
        assertEquals(Status.BUDGET_EXHAUSTED, result.status());
        assertEquals(2, calls.get());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.events().stream().anyMatch(e -> e.contains("TOOL_NOT_FOUND")));
    }

    @Test
    void repeatedToolCallDoesNotRepeatRead() {
        AtomicInteger steps = new AtomicInteger(), reads = new AtomicInteger();
        ResultDTO result =
                with(
                                role ->
                                        role.equals("weather_agent")
                                                ? model(
                                                        () ->
                                                                steps.incrementAndGet() <= 2
                                                                        ? tool(
                                                                                "query_weather",
                                                                                Map.of(
                                                                                        "city",
                                                                                        "杭州",
                                                                                        "date",
                                                                                        "2026-10-29"))
                                                                        : text("完成"))
                                                : new OfflineScenarioModel(role),
                                counting(reads))
                        .run(request)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(1, reads.get());
    }

    @Test
    void wrongSchemaCanBeCorrectedThroughRealSdkLoop() {
        AtomicInteger steps = new AtomicInteger(), reads = new AtomicInteger();
        ResultDTO result =
                with(
                                role ->
                                        role.equals("weather_agent")
                                                ? model(
                                                        () ->
                                                                switch (steps.incrementAndGet()) {
                                                                    case 1 ->
                                                                            tool(
                                                                                    "query_weather",
                                                                                    Map.of(
                                                                                            "city",
                                                                                            123,
                                                                                            "date",
                                                                                            "2026-10-29"));
                                                                    case 2 ->
                                                                            tool(
                                                                                    "query_weather",
                                                                                    Map.of(
                                                                                            "city",
                                                                                            "杭州",
                                                                                            "date",
                                                                                            "2026-10-29"));
                                                                    default -> text("完成");
                                                                })
                                                : new OfflineScenarioModel(role),
                                counting(reads))
                        .run(request)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(1, reads.get());
        assertEquals(3, steps.get(), "无效参数回传后模型修正，再生成终答");
    }

    @Test
    void evidenceVerifierIsAnExplicitComponentNotAnAutomaticFinalAnswerStage() {
        AtomicInteger answers = new AtomicInteger();
        RunContext context = new RunContext(request, LimitsDTO.defaults(), RunControl.local(LimitsDTO.defaults()));
        context.add(new EvidenceDTO("query_weather", Status.SUCCESS, "demo-v1", Map.of("city", "杭州", "date", "2026-10-29", "weather", Map.of("temperature", 18, "condition", "晴"))));
        TravelAgentFactory factory = new TravelAgentFactory(context, role -> model(() -> {
            answers.incrementAndGet();
            return tool("generate_response", Map.of("response", new AnswerVerifier.Draft(List.of(
                    new AnswerVerifier.Claim("query_weather", "{\"temperature\":99}")))));
        }), TravelTools.demoData());
        ResultDTO result = new AnswerVerifier(context, factory).finish("错误候选答案").block();
        assertEquals(2, answers.get());
        assertTrue(result.answer().contains("18"));
        assertFalse(result.answer().contains("99"));
    }

    @Test
    void longHistoryKeepsEarlierUserConstraintAfterActualModelCompaction() {
        List<TurnDTO> turns = new ArrayList<>();
        turns.add(new TurnDTO("只坐二等座", ""));
        for (int i = 0; i < 8; i++) {
            turns.add(new TurnDTO("第" + i + "轮补充", "无关回答".repeat(1000)));
        }
        RunContext context = new RunContext(new RequestDTO("x", "杭州明天的天气", turns),
                LimitsDTO.defaults(), RunControl.local(LimitsDTO.defaults()));
        context.today = LocalDate.of(2026, 10, 28);
        LoopConversation conversation = new LoopConversation(context);
        ContextCompactor compactor = new ContextCompactor(context, model(() -> text("已确认只坐二等座，继续查询天气。")));
        assertTrue(compactor.compactIfNeeded("可信规则", conversation, List.of()).block());
        assertTrue(conversation.messages.stream().anyMatch(m -> m.getRole() == MsgRole.USER
                && m.getTextContent().equals("只坐二等座")));
        assertFalse(conversation.messages.stream().anyMatch(m -> m.getTextContent().contains("无关回答")));
        List<Msg> input = ContextBuilder.modelInput("可信规则", context.request, context.today, conversation.messages, List.of());
        assertFalse(input.get(0).getTextContent().contains("只坐二等座"));
        assertTrue(ContextBuilder.estimate(Map.of("messages", input, "tools", List.of())) <= ContextBuilder.INPUT_LIMIT);
    }

    @Test
    void oversizedUserHistoryRetainsLatestInputAndMarksTheTruncatedBoundary() {
        RunContext context = new RunContext(new RequestDTO("large", "杭州明天的天气",
                List.of(new TurnDTO("历史用户条件".repeat(3000), ""))),
                LimitsDTO.defaults(), RunControl.local(LimitsDTO.defaults()));
        context.today = LocalDate.of(2026, 10, 28);
        LoopConversation conversation = new LoopConversation(context);
        ContextCompactor compactor = new ContextCompactor(context, model(() -> text("已整理历史条件，下一步查询杭州天气。")));
        assertTrue(compactor.compactIfNeeded("可信规则", conversation, List.of()).block());
        assertTrue(conversation.messages.stream().anyMatch(m -> m.getTextContent().equals("杭州明天的天气")));
        assertTrue(conversation.messages.stream().anyMatch(m ->
                Boolean.TRUE.equals(m.getMetadata().get("retainedSourcesIncomplete"))));
        List<Msg> input = ContextBuilder.modelInput("可信规则", context.request, context.today, conversation.messages, List.of());
        assertTrue(ContextBuilder.estimate(Map.of("messages", input, "tools", List.of())) <= ContextBuilder.INPUT_LIMIT);
    }

    @Test
    void knowledgeExpiryExcludesOldEvidence() {
        EvidenceDTO evidence = new KnowledgeService().search("行程规划", LocalDate.of(2100, 1, 1));
        assertEquals(Status.NO_DATA, evidence.status());
    }

    @Test
    void checkpointedChildIsReusedWithSameConditions() {
        AtomicInteger reads = new AtomicInteger();
        RunControl control = RunControl.local(LimitsDTO.defaults());
        Supervisor supervisor = with(OfflineScenarioModel::new, counting(reads));
        assertEquals(Status.SUCCESS, supervisor.run(request, control).block().status());
        int used = control.modelCalls();
        assertEquals(Status.SUCCESS, supervisor.run(request, control).block().status());
        assertEquals(1, reads.get());
        assertEquals(used, control.modelCalls(), "已保存终答恢复不重复调用模型");
    }

    @Test
    void sdkRateLimitRetriesWithoutRepeatingWholeTask() {
        AtomicInteger attempts = new AtomicInteger();
        ResultDTO result =
                with(
                                role -> {
                                    Model delegate = new OfflineScenarioModel(role);
                                    return new Model() {
                                        public String getModelName() {
                                            return delegate.getModelName();
                                        }

                                        public Flux<ChatResponse> stream(
                                                List<Msg> messages,
                                                List<ToolSchema> tools,
                                                GenerateOptions options) {
                                            if (role.equals("weather_agent")
                                                    && attempts.incrementAndGet() == 1) {
                                                return Flux.error(
                                                        new io.agentscope.extensions.model.openai
                                                                .exception.RateLimitException(
                                                                "limited", "rate_limit", ""));
                                            }
                                            return delegate.stream(messages, tools, options);
                                        }
                                    };
                                },
                                TravelTools.demoData())
                        .run(request)
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(3, attempts.get());
    }

    @Test
    void sameLogicalModelStepCannotResetAttemptsWithNewMessageIds() {
        RunContext context =
                new RunContext(
                        request, LimitsDTO.defaults(), RunControl.local(LimitsDTO.defaults()));
        AtomicInteger sends = new AtomicInteger();
        Model delegate =
                model(
                        () -> {
                            sends.incrementAndGet();
                            return text("ok");
                        });
        Model wrapped = context.budgeted(delegate);
        for (int i = 0; i < 3; i++)
            wrapped.stream(
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("same logical prompt")
                                            .build()),
                            List.of(),
                            GenerateOptions.builder().build())
                    .blockLast();
        assertThrows(
                RunControl.BudgetExceeded.class,
                () ->
                        wrapped.stream(
                                        List.of(
                                                Msg.builder()
                                                        .role(MsgRole.USER)
                                                        .textContent("same logical prompt")
                                                        .build()),
                                        List.of(),
                                        GenerateOptions.builder().build())
                                .blockLast());
        assertEquals(3, sends.get());
    }

    @Test
    void unsupportedSeatConstraintMustNotBeSilentlyIgnored() {
        ResultDTO result =
                Supervisor.offline(clock)
                        .run(new RequestDTO("seat", "明天从杭州到上海的火车票，只要一等座", List.of()))
                        .block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void runtimePreservesModelQuestionWithoutInventingTravelFields() {
        ResultDTO result = with(role -> model(() -> tool("request_user_input", Map.of("questions", List.of(
                Map.of("id", "days", "header", "天数", "question", "请确认行程天数。"))))), TravelTools.demoData())
                .run(new RequestDTO("days", "明天从杭州到上海玩3天", List.of())).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertEquals("请确认行程天数。", result.answer());
        assertTrue(result.evidence().isEmpty());
    }

    TravelTools.DataSource counting(AtomicInteger count) {
        return new TravelTools.DataSource() {
            public Map<String, Object> weather(String city, String date) {
                count.incrementAndGet();
                return TravelTools.demoData().weather(city, date);
            }

            public List<Map<String, Object>> trains(String from, String to, String date) {
                return TravelTools.demoData().trains(from, to, date);
            }
        };
    }
}
