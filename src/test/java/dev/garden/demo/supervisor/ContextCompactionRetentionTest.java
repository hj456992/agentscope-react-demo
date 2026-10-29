package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.*;

import java.time.LocalDate;
import java.util.*;

/** 压缩必须保留当前问题与完整工具配对；失败时不得安装半截历史。 */
class ContextCompactionRetentionTest {
    @Test
    void oversizedHistoryAndSummaryStillKeepTheCurrentQuestion() {
        RunContext context = context(new RequestDTO("retain", "当前问题", List.of()));
        LoopConversation conversation = new LoopConversation(context);
        conversation.messages.add(
                0,
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("旧输出".repeat(3000))
                        .build());
        conversation.messages.add(0, LoopConversation.user("历史条件".repeat(1200)));
        String summary = "摘要".repeat(8000);
        ContextCompactor compactor =
                new ContextCompactor(context, model((messages, tools) -> Flux.just(response(summary))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertTrue(
                conversation.messages.stream()
                        .anyMatch(
                                message ->
                                        message.getRole() == MsgRole.USER
                                                && message.getTextContent().equals("当前问题")));
        assertTrue(
                conversation.messages.stream().anyMatch(ContextCompactor::isSummary));
        assertTrue(
                conversation.messages.stream()
                        .noneMatch(message -> message.getTextContent().isBlank()));
        assertEquals(
                ContextCompactor.SUMMARY_PREFIX,
                conversation.messages.get(conversation.messages.size() - 1)
                        .getTextContent()
                        .lines()
                        .findFirst()
                        .orElse(""));
    }

    @Test
    void oversizedCurrentQuestionIsTruncatedAndMarked() {
        String question = "当前条件".repeat(5000);
        RunContext context = context(new RequestDTO("truncate", question, List.of()));
        LoopConversation conversation = new LoopConversation(context);
        conversation.messages.add(
                0,
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("旧输出".repeat(2000))
                        .build());
        int originalLength = question.length();
        ContextCompactor compactor =
                new ContextCompactor(context, model((messages, tools) -> Flux.just(response("摘要"))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        Msg retained =
                conversation.messages.stream()
                        .filter(
                                message ->
                                        !ContextCompactor.isSummary(message)
                                                && message.getRole() == MsgRole.USER)
                        .findFirst()
                        .orElseThrow();
        assertTrue(retained.getTextContent().length() > 0);
        assertTrue(retained.getTextContent().length() < originalLength);
        assertEquals(
                true, retained.getMetadata().get("retainedSourcesIncomplete"));
        assertTrue(retained.getTextContent().startsWith("当前条件"));
    }

    @Test
    void impossibleWindowLeavesOriginalHistoryUntouched() {
        RunContext context = context(new RequestDTO("limit", "问题", List.of()));
        LoopConversation conversation = new LoopConversation(context);
        conversation.append(
                Msg.builder().role(MsgRole.ASSISTANT).textContent("旧输出".repeat(1000)).build());
        List<Msg> original = List.copyOf(conversation.messages);
        List<ToolSchema> oversizedTools =
                List.of(
                        ToolSchema.builder()
                                .name("query_weather")
                                .description("工具".repeat(6000))
                                .parameters(Map.of("type", "object"))
                                .build());
        ContextCompactor compactor =
                new ContextCompactor(context, model((messages, tools) -> Flux.just(response("摘要"))));
        assertThrows(
                RunControl.ContextLimit.class,
                () -> compactor.compactIfNeeded("规则", conversation, oversizedTools).block());
        assertEquals(original, conversation.messages);
        assertEquals(0, conversation.compactionGeneration());
    }

    @Test
    void latestCompleteToolPairIsRetainedWithoutSplittingCallAndResult() {
        RunContext context = context(new RequestDTO("pair", "当前问题", List.of()));
        LoopConversation conversation = new LoopConversation(context);
        Msg call =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("query_weather")
                                                .input(Map.of("city", "北京"))
                                                .build()))
                        .build();
        Msg result =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                List.of(
                                        new ToolResultBlock(
                                                "call-1",
                                                "query_weather",
                                                TextBlock.builder()
                                                        .text("温度18℃".repeat(3000))
                                                        .build())))
                        .build();
        conversation.messages.add(0, result);
        conversation.messages.add(0, call);
        conversation.append(
                Msg.builder().role(MsgRole.ASSISTANT).textContent("旧输出".repeat(1000)).build());
        ContextCompactor compactor =
                new ContextCompactor(context, model((messages, tools) -> Flux.just(response("摘要"))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        List<ToolUseBlock> calls =
                conversation.messages.stream()
                        .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                        .toList();
        assertEquals(1, calls.size());
        assertEquals(
                1,
                conversation.messages.stream()
                        .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .count());
        ToolResultBlock retainedResult =
                conversation.messages.stream()
                        .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .findFirst()
                        .orElseThrow();
        assertEquals("call-1", retainedResult.getId());
        assertTrue(
                ((TextBlock) retainedResult.getOutput().get(0)).getText().startsWith("温度18℃"));
        assertTrue(
                conversation.messages.stream()
                        .anyMatch(
                                message ->
                                        Boolean.TRUE.equals(
                                                message.getMetadata()
                                                        .get("retainedSourcesIncomplete"))));
        assertTrue(conversation.messages.stream().anyMatch(ContextCompactor::isSummary));
    }

    @Test
    void unfinishedToolCallIsNotRetainedWithoutItsResult() {
        RunContext context = context(new RequestDTO("orphan", "当前问题", List.of()));
        LoopConversation conversation = new LoopConversation(context);
        Msg call =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("unanswered")
                                                .name("query_weather")
                                                .input(Map.of())
                                                .build()))
                        .build();
        conversation.messages.add(0, call);
        conversation.append(
                Msg.builder().role(MsgRole.ASSISTANT).textContent("旧输出".repeat(1000)).build());
        ContextCompactor compactor =
                new ContextCompactor(context, model((messages, tools) -> Flux.just(response("摘要"))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertTrue(
                conversation.messages.stream()
                        .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                        .findAny()
                        .isEmpty());
        assertTrue(
                conversation.messages.stream()
                        .anyMatch(message -> message.getTextContent().equals("当前问题")));
    }

    @Test
    void inheritedUserMessagesAreNotRetainedAsCurrentConditions() {
        RunContext context = context(new RequestDTO("inherit", "当前问题", List.of()));
        LoopConversation conversation = new LoopConversation(context);
        conversation.messages.add(
                0,
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("继承的用户条件".repeat(1000))
                        .metadata(Map.of("inheritedUserMessage", true))
                        .build());
        conversation.append(
                Msg.builder().role(MsgRole.ASSISTANT).textContent("旧输出".repeat(1000)).build());
        ContextCompactor compactor =
                new ContextCompactor(context, model((messages, tools) -> Flux.just(response("摘要"))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertTrue(
                conversation.messages.stream()
                        .noneMatch(
                                message ->
                                        message.getTextContent().contains("继承的用户条件")));
        assertTrue(
                conversation.messages.stream()
                        .anyMatch(message -> message.getTextContent().equals("当前问题")));
    }

    private interface Reply {
        Flux<ChatResponse> respond(List<Msg> messages, List<ToolSchema> tools);
    }

    private static Model model(Reply reply) {
        return new Model() {
            public String getModelName() {
                return "compaction-retention-script";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> reply.respond(messages, tools));
            }
        };
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build();
    }

    private static RunContext context(RequestDTO request) {
        RunContext context = new RunContext(request, LimitsDTO.defaults(), RunControl.local(LimitsDTO.defaults()));
        context.today = LocalDate.of(2026, 10, 29);
        return context;
    }
}
