package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.ToolCallParam;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.util.*;

/** 压缩后的原文必须可按压缩编号分页召回，关闭开关时不写记录。 */
class ContextRecallTest {
    private static final SupervisorPolicy RECALL_POLICY =
            SupervisorPolicy.defaults()
                    .withSummary(
                            new SupervisorPolicy.SummaryPolicy(
                                    false,
                                    1.0,
                                    true,
                                    SupervisorPolicy.SummaryPolicy.MissingConditionPolicy
                                            .KEEP_CONDITION_VERBATIM));

    @Test
    void transcriptKeepsUserAssistantAndToolDetails() {
        List<Msg> messages =
                List.of(
                        LoopConversation.user("查询杭州天气"),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("call-1")
                                                        .name("query_weather")
                                                        .input(Map.of("city", "杭州"))
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        List.of(
                                                new ToolResultBlock(
                                                        "call-1",
                                                        "query_weather",
                                                        TextBlock.builder()
                                                                .text("18℃ 多云")
                                                                .build())))
                                .build());
        String transcript = ContextRecall.transcript(messages);
        assertTrue(transcript.contains("[USER]"));
        assertTrue(transcript.contains("查询杭州天气"));
        assertTrue(transcript.contains("[工具调用 query_weather]"));
        assertTrue(transcript.contains("杭州"));
        assertTrue(transcript.contains("[工具结果 query_weather]"));
        assertTrue(transcript.contains("18℃ 多云"));
    }

    @Test
    void storedTranscriptCanBeReadByGenerationAndPaged() {
        RunContext context = context(RECALL_POLICY);
        ContextRecall.store(
                context,
                1,
                List.of(LoopConversation.user("第一段原始问题"), LoopConversation.user("第二段原始条件")));
        assertTrue(ContextRecall.read(context, 1).contains("第一段原始问题"));
        assertNull(ContextRecall.read(context, 2));

        ToolResultBlock block =
                ContextRecall.tool(context)
                        .callAsync(
                                ToolCallParam.builder()
                                        .input(Map.of("compression_id", 1, "offset", 7, "limit", 3))
                                        .build())
                        .block();
        assertNotNull(block);
        String output = output(block);
        assertTrue(output.contains("字符 7-10"));
        assertTrue(output.contains("第一段"));
        ResultDTO result = context.result(Status.SUCCESS, "");
        assertTrue(
                result.events().stream()
                        .anyMatch(
                                event ->
                                        event.contains("上下文召回")
                                                && event.contains("回读")
                                                && event.contains("权限复核通过")));
    }

    @Test
    void disabledRecallDoesNotWriteCheckpoint() {
        RunContext context = context(SupervisorPolicy.defaults());
        ContextRecall.store(context, 1, List.of(LoopConversation.user("原始问题")));
        assertNull(context.control.checkpoint(ContextRecall.key(1)));
    }

    @Test
    void compactionStoresTheReplacedHistoryBeforeInstallingSummary() {
        LimitsDTO limits = LimitsDTO.defaults();
        RunContext context =
                new RunContext(
                        new RequestDTO("recall", "保留最新问题", List.of()),
                        limits,
                        RunControl.local(limits, RECALL_POLICY));
        LoopConversation conversation = new LoopConversation(context);
        conversation.append(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("需要召回的旧答复 ".repeat(1000))
                        .build());
        ContextCompactor compactor =
                new ContextCompactor(
                        context,
                        model(
                                (messages, tools) ->
                                        Flux.just(text("已经查询，下一步核对结果。"))));
        assertTrue(compactor.compactIfNeeded("可信指令", conversation, List.of()).block());
        assertEquals(1, conversation.compactionGeneration());
        String saved = ContextRecall.read(context, 1);
        assertNotNull(saved);
        assertTrue(saved.contains("需要召回的旧答复"));
        assertFalse(
                conversation.messages.stream()
                        .anyMatch(message -> message.getTextContent().contains("需要召回的旧答复")));
    }

    private static RunContext context(SupervisorPolicy policy) {
        LimitsDTO limits = LimitsDTO.defaults();
        return new RunContext(
                new RequestDTO("recall", "查询", List.of()),
                limits,
                RunControl.local(limits, policy));
    }

    private static String output(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }

    private interface Reply {
        Flux<ChatResponse> next(List<Msg> messages, List<ToolSchema> tools);
    }

    private static Model model(Reply reply) {
        return new Model() {
            public String getModelName() {
                return "recall-test";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> reply.next(messages, tools));
            }
        };
    }
}
