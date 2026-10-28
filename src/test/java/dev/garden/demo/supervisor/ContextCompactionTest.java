package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;
import io.agentscope.core.model.*;
import reactor.core.publisher.*;
import java.util.concurrent.atomic.*;
import java.time.LocalDate;
import java.util.*;

/** 验证真实历史进入模型并由压缩器替换，不能继续以 system 条件清单绕过消息身份。 */
class ContextCompactionTest {
    @Test
    void historicalUserAndAssistantMessagesAreRecordedOnceInConversation() {
        RequestDTO request = new RequestDTO("compact", "当前问题", List.of(new TurnDTO("过去问题", "过去答复")));
        RunContext context = context(request, RunControl.local(LimitsDTO.defaults()));
        LoopConversation conversation = new LoopConversation(context);
        assertEquals(List.of("过去问题", "过去答复", "当前问题"),
                conversation.messages.stream().map(Msg::getTextContent).toList());
        assertEquals(List.of(MsgRole.USER, MsgRole.ASSISTANT, MsgRole.USER),
                conversation.messages.stream().map(Msg::getRole).toList());
    }

    @Test
    void modelInputDoesNotSilentlyDropAnOldAnswerOrDuplicateHistoryInSystem() {
        RequestDTO request = new RequestDTO("compact", "当前问题",
                List.of(new TurnDTO("历史条件", "历史答复".repeat(2000))));
        List<Msg> messages = List.of(LoopConversation.user("历史条件"),
                Msg.builder().role(MsgRole.ASSISTANT).textContent("历史答复".repeat(2000)).build(),
                LoopConversation.user("当前问题"));
        List<Msg> input = ContextBuilder.modelInput("可信规则", request, LocalDate.of(2026, 10, 29), messages, List.of());
        assertFalse(input.get(0).getTextContent().contains("历史条件"));
        assertFalse(input.get(0).getTextContent().contains("历史答复"));
        assertEquals(messages, input.subList(1, input.size()));
    }

    @Test
    void compactReplacesOldOutputsAndRestoresTheReplacementAfterRestart() {
        RequestDTO request = new RequestDTO("compact", "保留最新问题", List.of());
        RunControl control = RunControl.local(LimitsDTO.defaults());
        RunContext context = context(request, control);
        LoopConversation conversation = new LoopConversation(context);
        conversation.append(Msg.builder().role(MsgRole.ASSISTANT).textContent("old answer ".repeat(1000)).build());
        AtomicInteger sends = new AtomicInteger();
        ContextCompactor compactor = new ContextCompactor(context, model((messages, tools) -> {
            sends.incrementAndGet();
            assertTrue(tools.isEmpty());
            assertTrue(messages.get(messages.size() - 1).getTextContent().contains("CONTEXT CHECKPOINT COMPACTION"));
            return Flux.just(response("已经查询，下一步核对结果。"));
        }));
        assertTrue(compactor.compactIfNeeded("可信指令", conversation, List.of()).block());
        assertEquals(1, sends.get());
        assertTrue(conversation.messages.stream().anyMatch(m -> m.getTextContent().equals("保留最新问题")));
        assertFalse(conversation.messages.stream().anyMatch(m -> m.getTextContent().contains("old answer")));
        LoopConversation restored = new LoopConversation(context(request, control));
        assertEquals(1, restored.compactionGeneration());
        assertEquals(conversation.messages.stream().map(Msg::getTextContent).toList(),
                restored.messages.stream().map(Msg::getTextContent).toList());
        assertFalse(compactor.compactIfNeeded("可信指令", restored, List.of()).block());
        assertEquals(1, sends.get());
    }

    @Test
    void overflowRetriesOnlyTemporaryHistoryAndRemovesPairedResults() {
        RunContext context = context(new RequestDTO("compact", "最新条件", List.of()), RunControl.local(LimitsDTO.defaults()));
        LoopConversation conversation = new LoopConversation(context);
        Msg call = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(
                ToolUseBlock.builder().id("old-call").name("query").input(Map.of()).build())).build();
        Msg output = Msg.builder().role(MsgRole.TOOL).content(List.of(
                new ToolResultBlock("old-call", "query", TextBlock.builder().text("old-output").build()))).build();
        conversation.messages.add(0, output);
        conversation.messages.add(0, call);
        conversation.append(Msg.builder().role(MsgRole.ASSISTANT).textContent("large ".repeat(1500)).build());
        List<Msg> original = List.copyOf(conversation.messages);
        AtomicInteger attempts = new AtomicInteger();
        ContextCompactor compactor = new ContextCompactor(context, model((messages, tools) -> {
            assertEquals(original, conversation.messages);
            if (attempts.getAndIncrement() == 0) {
                return Flux.error(new RunControl.ContextLimit("provider context window exceeded"));
            }
            assertTrue(messages.stream().flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream()).findAny().isEmpty());
            assertTrue(messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).findAny().isEmpty());
            return Flux.just(response("已保存最新条件。"));
        }));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertEquals(2, attempts.get());
    }

    @Test
    void cancelledOrIncompleteCompactionDoesNotInstallPartialHistory() {
        RunContext context = context(new RequestDTO("compact", "最新问题", List.of()), RunControl.local(LimitsDTO.defaults()));
        LoopConversation conversation = new LoopConversation(context);
        conversation.append(Msg.builder().role(MsgRole.ASSISTANT).textContent("旧结果".repeat(3000)).build());
        List<Msg> original = List.copyOf(conversation.messages);
        ContextCompactor compactor = new ContextCompactor(context, model((messages, tools) ->
                Flux.concat(Flux.just(response("部分摘要")), Flux.error(new RunControl.Cancelled("已取消")))));
        assertThrows(RunControl.Cancelled.class, () -> compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertEquals(original, conversation.messages);
        assertEquals(0, conversation.compactionGeneration());
    }

    @Test
    void historyForkUsesInheritedMessagesBeforeChildTask() {
        RequestDTO request = new RequestDTO("child", "子任务", List.of(new TurnDTO("不重复旧请求", "旧答")));
        RunControl control = RunControl.local(LimitsDTO.defaults());
        control.checkpoint("agent:inherited-history", dev.garden.demo.supervisor.support.Contracts.json(
                List.of(LoopConversation.user("父消息"))));
        LoopConversation child = new LoopConversation(context(request, control));
        assertEquals("父消息", child.messages.get(0).getTextContent());
        assertEquals(true, child.messages.get(0).getMetadata().get("inheritedUserMessage"));
        assertEquals("子任务", child.messages.get(child.messages.size() - 1).getTextContent());
        assertTrue(child.messages.get(1).getTextContent().contains("Inherited tool calls are historical records"));
        assertFalse(child.messages.stream().anyMatch(m -> m.getTextContent().contains("不重复旧请求")));
    }

    @Test
    void oldSummaryIsNotRetainedAsARealUserMessage() {
        RunContext context = context(new RequestDTO("compact", "当前条件", List.of()), RunControl.local(LimitsDTO.defaults()));
        LoopConversation conversation = new LoopConversation(context);
        conversation.messages.add(0, Msg.builder().role(MsgRole.USER).textContent("旧摘要独有文本")
                .metadata(Map.of("contextCompaction", true)).build());
        conversation.append(Msg.builder().role(MsgRole.ASSISTANT).textContent("旧输出".repeat(3000)).build());
        ContextCompactor compactor = new ContextCompactor(context, model((messages, tools) -> Flux.just(response("新摘要"))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertFalse(conversation.messages.stream().anyMatch(m -> m.getTextContent().contains("旧摘要独有文本")));
        assertEquals("当前条件", conversation.messages.get(0).getTextContent());
    }

    @Test
    void legacySnapshotMigratesHistoricalMessagesOnlyOnce() {
        RequestDTO request = new RequestDTO("legacy", "当前问题", List.of(new TurnDTO("旧条件", "旧回答")));
        RunControl control = RunControl.local(LimitsDTO.defaults());
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("protocol", 1);
        legacy.put("revision", 1);
        legacy.put("request", request);
        legacy.put("messages", List.of(LoopConversation.user("当前问题")));
        legacy.put("evidence", List.of());
        legacy.put("agents", List.of());
        legacy.put("decision", new DecisionDTO(Intent.UNKNOWN, "", "", "", "", 0));
        legacy.put("failedCalls", List.of());
        legacy.put("referenceDate", "2026-10-29");
        control.checkpoint("loop:v3.3", dev.garden.demo.supervisor.support.Contracts.json(legacy));
        new LoopConversation(context(request, control));
        LoopConversation recoveredAgain = new LoopConversation(context(request, control));
        assertEquals(List.of("旧条件", "旧回答", "当前问题"),
                recoveredAgain.messages.stream().map(Msg::getTextContent).toList());
    }

    @Test
    void normalizedCompactionHistoryPairsMissingCallsAndRemovesOrphans() {
        Msg call = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(
                ToolUseBlock.builder().id("pending").name("query").input(Map.of()).build())).build();
        Msg orphan = Msg.builder().role(MsgRole.TOOL).content(List.of(
                new ToolResultBlock("unknown", "query", TextBlock.builder().text("orphan").build()))).build();
        List<Msg> normalized = ContextCompactor.normalize(List.of(call, orphan));
        assertEquals(2, normalized.size());
        ToolResultBlock result = normalized.get(1).getContentBlocks(ToolResultBlock.class).get(0);
        assertEquals("pending", result.getId());
        assertEquals("aborted", ((TextBlock) result.getOutput().get(0)).getText());
    }

    @Test
    void failedCheckpointDoesNotPublishCompactedHistory() {
        RequestDTO request = new RequestDTO("save", "用户问题", List.of());
        RunControl base = RunControl.local(LimitsDTO.defaults());
        AtomicBoolean reject = new AtomicBoolean();
        RunControl control = new SupervisorLoopTest.DelegatingControl(base) {
            public void checkpoint(String key, String value) {
                if (reject.get() && key.equals("loop:v3.3")) {
                    throw new IllegalStateException("模拟数据库失败");
                }
                base.checkpoint(key, value);
            }
        };
        LoopConversation conversation = new LoopConversation(context(request, control));
        List<Msg> original = List.copyOf(conversation.messages);
        reject.set(true);
        assertThrows(IllegalStateException.class,
                () -> conversation.replaceCompacted(List.of(LoopConversation.user("摘要")), "摘要"));
        assertEquals(original, conversation.messages);
        assertEquals(0, conversation.compactionGeneration());
    }

    @Test
    void childFollowupReopensCompletedSnapshotWithoutPretendingMailboxIsUserInput() {
        RunControl base = RunControl.local(LimitsDTO.defaults());
        AtomicInteger generation = new AtomicInteger(1);
        RunControl control = new SupervisorLoopTest.DelegatingControl(base) {
            public int revision() { return generation.get(); }
            public String turnId() { return runId() + ":" + generation.get(); }
        };
        RequestDTO first = new RequestDTO("child", "原始子任务", List.of());
        LoopConversation completed = new LoopConversation(context(first, control));
        completed.assistant(Msg.builder().role(MsgRole.ASSISTANT).textContent("原始答复").build());
        generation.incrementAndGet();
        control.checkpoint("agent:turn-start", "true");
        control.checkpoint("agent:interrupted", "The previous turn was interrupted.");
        RequestDTO next = new RequestDTO("child", "来自父Agent的追问", List.of());
        LoopConversation reopened = new LoopConversation(context(next, control));
        assertNull(reopened.replyStatus);
        assertNull(reopened.replyText);
        assertFalse(reopened.messages.stream().anyMatch(m -> m.getRole() == MsgRole.USER
                && m.getTextContent().contains("来自父Agent的追问")));
        assertEquals(1, reopened.messages.stream().filter(m -> m.getTextContent().contains("previous turn was interrupted")).count());
        // 模拟已落快照但清 marker 之前崩溃，恢复不可重复注入通知。
        control.checkpoint("agent:turn-start", "true");
        control.checkpoint("agent:interrupted", "The previous turn was interrupted.");
        LoopConversation restored = new LoopConversation(context(next, control));
        assertEquals(reopened.messages.size(), restored.messages.size());
    }

    @Test
    void localWindowOverflowIsRecoveredBeforeReservingTheActualSummaryCall() {
        RunControl control = RunControl.local(LimitsDTO.defaults());
        RunContext context = context(new RequestDTO("budget", "最新条件", List.of()), control);
        LoopConversation conversation = new LoopConversation(context);
        conversation.append(Msg.builder().role(MsgRole.ASSISTANT).textContent("巨大工具输出".repeat(2500)).build());
        ContextCompactor compactor = new ContextCompactor(context,
                context.budgetedCompaction(model((messages, tools) -> Flux.just(response("任务尚需继续。")))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertEquals(1, control.modelCalls());
        assertDoesNotThrow(context::check);
        assertTrue(conversation.messages.stream().anyMatch(m -> m.getTextContent().equals("最新条件")));
    }

    @Test
    void forkDoesNotExecuteAnUnfinishedParentToolCall() {
        RunControl control = RunControl.local(LimitsDTO.defaults());
        Msg parentCall = Msg.builder().role(MsgRole.ASSISTANT).content(List.of(
                ToolUseBlock.builder().id("parent-spawn").name("spawn_agent").input(Map.of()).build())).build();
        control.checkpoint("agent:inherited-history", dev.garden.demo.supervisor.support.Contracts.json(List.of(parentCall)));
        LoopConversation child = new LoopConversation(context(new RequestDTO("fork", "子任务", List.of()), control));
        assertTrue(child.pendingCalls().isEmpty());
        assertEquals("子任务", child.messages.get(child.messages.size() - 1).getTextContent());
    }

    @Test
    void agentMailboxContentIsSummarizedWithoutBecomingRetainedUserAuthorization() {
        RunContext context = context(new RequestDTO("agent-msg", "真实用户问题", List.of()), RunControl.local(LimitsDTO.defaults()));
        LoopConversation conversation = new LoopConversation(context);
        conversation.append(Msg.builder().role(MsgRole.USER).textContent("代理通知不是真实用户授权".repeat(1000))
                .metadata(Map.of("agentMessage", true)).build());
        ContextCompactor compactor = new ContextCompactor(context, model((messages, tools) -> Flux.just(response("已收到代理进度。"))));
        assertTrue(compactor.compactIfNeeded("规则", conversation, List.of()).block());
        assertTrue(conversation.messages.stream().anyMatch(m -> m.getTextContent().equals("真实用户问题")));
        assertFalse(conversation.messages.stream().anyMatch(m -> m.getTextContent().contains("代理通知不是真实用户授权")));
    }

    private interface Reply {
        Flux<ChatResponse> respond(List<Msg> messages, List<ToolSchema> tools);
    }

    private static Model model(Reply reply) {
        return new Model() {
            public String getModelName() { return "compact-script"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> reply.respond(messages, tools));
            }
        };
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).finishReason("stop").build();
    }

    private static RunContext context(RequestDTO request, RunControl control) {
        RunContext context = new RunContext(request, LimitsDTO.defaults(), control);
        context.today = LocalDate.of(2026, 10, 29);
        return context;
    }
}
