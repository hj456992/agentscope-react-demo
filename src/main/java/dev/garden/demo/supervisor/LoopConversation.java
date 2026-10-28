package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.util.JsonUtils;

import java.util.*;

/** 记录真正的模型会话；助手工具请求和每个结果都单独落检查点，恢复时只补缺失结果。 */
final class LoopConversation {
    private static final String KEY = "loop:v3.3";
    private final RunContext context;
    final List<Msg> messages = new ArrayList<>();
    Status replyStatus;
    String replyText;
    private int compactionGeneration;
    private String compactionSummary;
    private String lastStartedTurnId;

    public record Snapshot(
            int protocol,
            int revision,
            RequestDTO request,
            List<Msg> messages,
            List<EvidenceDTO> evidence,
            List<String> agents,
            DecisionDTO decision,
            List<String> failedCalls,
            String referenceDate,
            Status replyStatus,
            String replyText,
            int compactionGeneration,
            String compactionSummary,
            String lastStartedTurnId) {}

    LoopConversation(RunContext context) {
        this.context = context;
        String saved = context.control.checkpoint(KEY);
        String turnStart = context.control.checkpoint("agent:turn-start");
        String interrupted = context.control.checkpoint("agent:interrupted");
        RequestDTO incomingRequest = context.request;
        if (saved == null) {
            String inherited = context.control.checkpoint("agent:inherited-history");
            if (inherited != null) {
                Msg[] inheritedMessages = JsonUtils.getJsonCodec().fromJson(inherited, Msg[].class);
                // V2 preserve_fork_prefix 保留上下文，但父工具是历史记录，不能在子线程执行。
                for (Msg inheritedMessage : ContextCompactor.normalize(Arrays.asList(inheritedMessages))) {
                    Map<String, Object> metadata = new HashMap<>(inheritedMessage.getMetadata());
                    metadata.put("inheritedUserMessage", true);
                    messages.add(inheritedMessage.withMetadata(metadata));
                }
                messages.add(Msg.builder().role(MsgRole.USER).textContent(
                        "You are now a newly spawned child agent. The preceding conversation is inherited parent context. "
                        + "Follow your own role, identity, and task below; earlier identities, tasks, and action-specific approvals belong to your ancestors. "
                        + "Inherited tool calls are historical records; live tool sessions and process handles are not inherited. Report your result to your parent.")
                        .metadata(Map.of("contextNotice", true)).build());
            } else {
                for (TurnDTO turn : context.request.history()) {
                    if (turn != null && !blank(turn.question())) {
                        messages.add(user(turn.question()));
                    }
                    if (turn != null && !blank(turn.answer())) {
                        messages.add(Msg.builder().role(MsgRole.ASSISTANT).textContent(turn.answer()).build());
                    }
                }
            }
            messages.add(user(context.request.question()));
            // v3.2 未完成任务保留原预算和工具成功缓存，不执行旧 rewrite/intent 检查点。
            context.control.checkpoint("protocol", "3.3");
        } else {
            Snapshot snapshot = JsonUtils.getJsonCodec().fromJson(saved, Snapshot.class);
            if (snapshot.protocol() != 1 && snapshot.protocol() != 2) {
                throw new IllegalStateException("无法识别的循环记录版本");
            }
            if (snapshot.protocol() == 1) {
                // v3.3 将旧问答藏在 request.reference；升级时迁成真实消息一次。
                Set<String> recordedUsers = new HashSet<>();
                snapshot.messages().stream().filter(m -> m.getRole() == MsgRole.USER)
                        .forEach(m -> recordedUsers.add(m.getTextContent()));
                for (TurnDTO turn : snapshot.request().history()) {
                    if (turn != null && !blank(turn.question()) && !recordedUsers.contains(turn.question())) {
                        messages.add(user(turn.question()));
                        if (!blank(turn.answer())) {
                            messages.add(Msg.builder().role(MsgRole.ASSISTANT).textContent(turn.answer()).build());
                        }
                    }
                }
            }
            messages.addAll(snapshot.messages());
            context.decision = snapshot.decision();
            if (snapshot.referenceDate() != null) {
                context.today = java.time.LocalDate.parse(snapshot.referenceDate());
            }
            if (snapshot.failedCalls() != null) {
                snapshot.failedCalls().forEach(context::childFailed);
            }
            for (EvidenceDTO e : snapshot.evidence()) {
                if (!context.control.identity().allows(e.tool())) {
                    throw new RunControl.Cancelled("历史工具结果权限已变化，不能继续回放");
                }
                context.add(e);
            }
            snapshot.agents().forEach(context::agent);
            replyStatus = snapshot.replyStatus();
            replyText = snapshot.replyText();
            compactionGeneration = snapshot.compactionGeneration();
            compactionSummary = snapshot.compactionSummary();
            lastStartedTurnId = snapshot.lastStartedTurnId();
            context.request = snapshot.request();
            context.query = snapshot.request().question();
            // 未确认输入由 RunControl 持久投递批次重放，不能用 revision 猜一条用户消息。
        }
        if (turnStart != null && !Objects.equals(lastStartedTurnId, context.control.turnId())) {
            if (interrupted != null) {
                List<Msg> normalized = ContextCompactor.normalize(messages);
                messages.clear();
                messages.addAll(normalized);
                messages.add(Msg.builder().role(MsgRole.SYSTEM).textContent(interrupted)
                        .metadata(Map.of("contextNotice", true)).build());
            }
            // followup 由带来源的 mailbox 注入；此标记只重开回合，不能伪造成用户原话。
            context.request = incomingRequest;
            context.query = incomingRequest.question();
            replyStatus = null;
            replyText = null;
            lastStartedTurnId = context.control.turnId();
        }
        save();
        // 新回合标识与消息同快照，先落盘再清标记；崩溃重放不会追加第二次。
        if (turnStart != null) {
            context.control.checkpoint("agent:turn-start", null);
            context.control.checkpoint("agent:interrupted", null);
        }
    }

    boolean receiveInput() {
        List<String> inputs = context.control.drainInputs();
        boolean received = false;
        for (String input : inputs) {
            if (input != null && !input.isBlank()) {
                accept(input);
                received = true;
            }
        }
        if (received) {
            save();
        }
        return received;
    }

    private void accept(String input) {
        context.acceptInput(input);
        messages.add(user(input));
        replyStatus = null;
        replyText = null;
    }

    void append(Msg message) {
        messages.add(message);
        save();
    }

    void assistant(Msg message) {
        messages.add(message);
        if (message.getContentBlocks(ToolUseBlock.class).isEmpty()) {
            replyStatus = Status.SUCCESS;
            replyText = message.getTextContent();
        }
        // 模型终答和结束标记是同一个检查点，避免崩溃后重复发送模型请求。
        save();
    }

    void reply(Status status, String text) {
        replyStatus = status;
        replyText = text;
        save();
    }

    void save() {
        persist(messages, compactionGeneration, compactionSummary);
    }

    /** 完整替换与压缩代数写入同一检查点；持久化失败时不发布新的内存历史。 */
    void replaceCompacted(List<Msg> replacement, String summary) {
        context.check();
        List<Msg> frozen = List.copyOf(replacement);
        int nextGeneration = compactionGeneration + 1;
        persist(frozen, nextGeneration, summary);
        messages.clear();
        messages.addAll(frozen);
        compactionGeneration = nextGeneration;
        compactionSummary = summary;
    }

    int compactionGeneration() {
        return compactionGeneration;
    }

    private void persist(List<Msg> history, int generation, String summary) {
        context.control.checkpoint(KEY, json(new Snapshot(
                2, context.control.revision(), context.request, List.copyOf(history),
                context.evidence(), context.result(Status.RUNNING, "").agents(), context.decision,
                context.failedCalls(), context.today.toString(), replyStatus, replyText,
                generation, summary, lastStartedTurnId)));
    }

    List<ToolUseBlock> pendingCalls() {
        int lastAssistant = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = i;
                break;
            }
        }
        if (lastAssistant < 0) {
            return List.of();
        }
        Set<String> answered = new HashSet<>();
        for (int i = lastAssistant + 1; i < messages.size(); i++)
            messages.get(i)
                    .getContentBlocks(ToolResultBlock.class)
                    .forEach(r -> answered.add(r.getId()));
        return messages.get(lastAssistant).getContentBlocks(ToolUseBlock.class).stream()
                .filter(call -> !answered.contains(call.getId()))
                .toList();
    }

    static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
