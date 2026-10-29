package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class CoreSourceParityTest {
    @Test
    void finalModelTextIsDeliveredWithoutASecondAnswerModel() {
        List<String> roles = new ArrayList<>();
        Supervisor supervisor =
                new Supervisor(
                        role -> {
                            roles.add(role);
                            return model((messages, tools) -> text("这是模型自己的完整回答，不是旅行能力模板。"));
                        },
                        Clock.systemUTC(),
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        ResultDTO result =
                supervisor.run(new RequestDTO("source", "请解释一下你收到的问题", List.of())).block();
        assertEquals("这是模型自己的完整回答，不是旅行能力模板。", result.answer());
        assertEquals(List.of("supervisor"), roles);
    }

    @Test
    void questionWaitsForActualAnswerAndKeepsCallId() {
        AtomicInteger calls = new AtomicInteger();
        RunControl control = RunControl.local(LimitsDTO.defaults());
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) -> {
                                            calls.incrementAndGet();
                                            return tool(
                                                    "request_user_input",
                                                    Map.of(
                                                            "questions",
                                                            List.of(
                                                                    Map.of(
                                                                            "id",
                                                                            "preference",
                                                                            "header",
                                                                            "偏好",
                                                                            "question",
                                                                            "更希望省钱还是缩短路程？"))));
                                        }),
                        Clock.systemUTC(),
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        ResultDTO result =
                supervisor.run(new RequestDTO("ask", "帮我比较方案", List.of()), control).block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertEquals("更希望省钱还是缩短路程？", result.answer());
        String snapshot = control.checkpoint("loop:v3.3");
        assertTrue(snapshot.contains("question-call"));
        assertFalse(snapshot.contains("等待用户补充："));
        assertEquals(1, calls.get());
    }

    @Test
    void actualAnswerResumesTheSameQuestionCallThroughPersistentService() {
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                model(
                                        (messages, tools) -> {
                                            if (turns.getAndIncrement() == 0) {
                                                return tool(
                                                        "request_user_input",
                                                        Map.of(
                                                                "questions",
                                                                List.of(
                                                                        Map.of(
                                                                                "id",
                                                                                "preference",
                                                                                "header",
                                                                                "偏好",
                                                                                "question",
                                                                                "省钱还是省时？"))));
                                            }
                                            List<ToolResultBlock> results =
                                                    messages.stream()
                                                            .flatMap(
                                                                    m ->
                                                                            m
                                                                                    .getContentBlocks(
                                                                                            ToolResultBlock
                                                                                                    .class)
                                                                                    .stream())
                                                            .toList();
                                            assertEquals(1, results.size());
                                            assertEquals("question-call", results.get(0).getId());
                                            assertTrue(
                                                    ChildAgentDispatcher.text(results.get(0))
                                                            .contains("省时"));
                                            assertFalse(
                                                    messages.stream()
                                                            .anyMatch(
                                                                    m ->
                                                                            m.getRole()
                                                                                            == MsgRole
                                                                                                    .USER
                                                                                    && m.getTextContent()
                                                                                            .equals(
                                                                                                    "省时")),
                                                    "真实答案通过原工具调用返回，不伪装成另一条用户任务");
                                            return text("按省时方案继续。");
                                        }),
                        Clock.systemUTC(),
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url);
                var tasks =
                        TestServices.taskService(
                                repository,
                                SupervisorRuntimeIntegrationTest.settings(url),
                                supervisor::run)) {
            var waiting =
                    tasks.submit(
                                    IdentityDTO.local(),
                                    new RequestDTO("answer", "比较方案", List.of(), "start"),
                                    false)
                            .block();
            assertEquals("NEEDS_INPUT", waiting.state());
            tasks.instruct(
                            IdentityDTO.local(),
                            new dev.garden.demo.supervisor.pojo.param.InstructionParam(
                                    waiting.runId(),
                                    "answer-1",
                                    waiting.revision(),
                                    Action.SUPPLEMENT,
                                    "省时"))
                    .block();
            var completed = SupervisorRuntimeIntegrationTest.done(tasks, waiting.runId());
            assertEquals("SUCCESS", completed.state(), completed.toString());
            assertEquals("按省时方案继续。", completed.result().answer());
            assertEquals(2, turns.get());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void childCompletionWhileAskingDoesNotSpinOrInventAUserAnswer() throws Exception {
        AtomicInteger mainTurns = new AtomicInteger();
        reactor.core.publisher.Sinks.One<Boolean> releaseChild = reactor.core.publisher.Sinks.one();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                new Model() {
                                    public String getModelName() {
                                        return "question-mailbox-" + role;
                                    }

                                    public Flux<ChatResponse> stream(
                                            List<Msg> messages,
                                            List<ToolSchema> tools,
                                            GenerateOptions options) {
                                        if (!role.equals("supervisor")) {
                                            return releaseChild
                                                    .asMono()
                                                    .map(ignored -> text("子任务已完成"))
                                                    .flux();
                                        }
                                        if (mainTurns.getAndIncrement() == 0) {
                                            return Flux.just(
                                                    ChatResponse.builder()
                                                            .content(
                                                                    List.of(
                                                                            ToolUseBlock.builder()
                                                                                    .id(
                                                                                            "spawn-lookup")
                                                                                    .name(
                                                                                            "spawn_agent")
                                                                                    .input(
                                                                                            Map.of(
                                                                                                    "task_name",
                                                                                                    "lookup",
                                                                                                    "message",
                                                                                                    "查询资料",
                                                                                                    "fork_turns",
                                                                                                    "none"))
                                                                                    .build()))
                                                            .finishReason("tool_calls")
                                                            .build());
                                        }
                                        releaseChild.tryEmitValue(true);
                                        return reactor.core.publisher.Mono.delay(
                                                        Duration.ofMillis(150))
                                                .map(
                                                        ignored ->
                                                                tool(
                                                                        "request_user_input",
                                                                        Map.of(
                                                                                "questions",
                                                                                List.of(
                                                                                        Map.of(
                                                                                                "id",
                                                                                                "choice",
                                                                                                "header",
                                                                                                "选择",
                                                                                                "question",
                                                                                                "选哪个方案？")))))
                                                .flux();
                                    }
                                },
                        Clock.systemUTC(),
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        try (var repository = new TestRepository(url);
                var tasks =
                        TestServices.taskService(
                                repository,
                                SupervisorRuntimeIntegrationTest.settings(url),
                                supervisor::run)) {
            var result =
                    tasks.submit(
                                    IdentityDTO.local(),
                                    new RequestDTO("ask-mail", "比较方案", List.of(), "start"),
                                    false)
                            .block(Duration.ofSeconds(4));
            assertEquals("NEEDS_INPUT", result.state(), result.toString());
            assertEquals("选哪个方案？", result.result().answer());
            assertEquals(2, mainTurns.get());
            var root =
                    repository.all().stream()
                            .filter(t -> t.parentRunId == null)
                            .findFirst()
                            .orElseThrow();
            assertTrue(root.checkpoints.get("loop:v3.3").contains("子任务已完成"));
            assertTrue(root.mailbox.isEmpty());
            assertTrue(root.inflightMailbox.isEmpty());
        }
    }

    interface Reply {
        ChatResponse next(List<Msg> messages, List<ToolSchema> tools);
    }

    static Model model(Reply reply) {
        return new Model() {
            public String getModelName() {
                return "source-test";
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
                                        .id("question-call")
                                        .name(name)
                                        .input(input)
                                        .build()))
                .finishReason("tool_calls")
                .build();
    }
}
