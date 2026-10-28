package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;
import io.agentscope.core.message.*;
import io.agentscope.core.util.JsonUtils;
import java.util.*;

/**
 * 记录真正的模型会话；助手工具请求和每个结果都单独落检查点，恢复时只补缺失结果。
 *
 * <p>为什么把“消息列表”和“检查点”绑在一起：会话是模型看到的全部上下文，
 * 而任务可能因为进程重启在任意一步中断。这里每追加一条消息就写一次快照，
 * 恢复时按消息里的 tool_use / tool_result id 判断哪些调用还没结果，只补缺的那部分，
 * 不会重复执行已完成的工具。检查点写入走 {@code RunControl}，本类不做数据库操作。
 */
final class LoopConversation {
    private static final String KEY = "loop:v3.3";
    static final String TRIMMED = "（早期回答已裁剪，事实仍以工具结果为准）";
    private final RunContext context;
    final List<Msg> messages = new ArrayList<>();
    Status replyStatus;
    String replyText;
    /** 由 answer_directly 声明的非事实答复；纯文本答复为 false。 */
    boolean directAnswer;

    public record Snapshot(int protocol, int revision, Request request, List<Msg> messages,
                           List<Evidence> evidence, List<String> agents, Decision decision,
                           Status replyStatus, String replyText, boolean directAnswer) {}

    LoopConversation(RunContext context) {
        this.context = context;
        String saved = context.control.checkpoint(KEY);
        if (saved == null) {
            messages.add(user(context.request.question()));
            // v3.2 未完成任务保留原预算、输入和有效成功证据，不执行旧 rewrite/intent 检查点。
            restoreLegacyEvidence();
            context.control.checkpoint("protocol", "3.3");
        } else {
            Snapshot snapshot = JsonUtils.getJsonCodec().fromJson(saved, Snapshot.class);
            if (snapshot.protocol() != 1) throw new IllegalStateException("无法识别的循环记录版本");
            messages.addAll(snapshot.messages());
            context.decision = snapshot.decision();
            for (Evidence e : snapshot.evidence()) {
                if (!context.control.identity().allows(e.tool()))
                    throw new RunControl.Cancelled("历史工具结果权限已变化，不能继续回放");
                context.add(e);
            }
            snapshot.agents().forEach(context::agent);
            replyStatus = snapshot.replyStatus();
            replyText = snapshot.replyText();
            directAnswer = snapshot.directAnswer();
            Request latest = context.request;
            context.request = snapshot.request();
            context.query = snapshot.request().question();
            if (snapshot.revision() < context.control.revision()) {
                // 输入已被任务事务消费，但进程在保存循环记录前退出：用持久的最新用户输入补齐。
                accept(latest.question());
            }
        }
        save();
    }

    /** 旧协议只保存了 progress 结果：沿用其中的有效成功证据，重建新循环。 */
    private void restoreLegacyEvidence() {
        String legacy = context.control.checkpoint("progress");
        if (legacy == null) return;
        Result previous;
        try {
            previous = JsonUtils.getJsonCodec().fromJson(legacy, Result.class);
        } catch (RuntimeException malformed) {
            return;
        }
        if (previous == null || previous.evidence() == null) return;
        if (previous.agents() != null) previous.agents().forEach(context::agent);
        int restored = 0;
        for (Evidence item : previous.evidence()) {
            if (item == null || !context.control.identity().allows(item.tool())) continue;
            try {
                context.add(item);
                restored++;
            } catch (IllegalArgumentException tooLarge) {
                // 单条旧证据超过上限时不沿用，其余证据继续保留。
            }
        }
        if (restored > 0) context.event("沿用旧协议已完成证据 " + restored + " 条");
    }

    boolean receiveInput() {
        String input = context.control.drainInput();
        if (input == null || input.isBlank()) return false;
        accept(input);
        save();
        return true;
    }

    private void accept(String input) {
        context.acceptInput(input);
        messages.add(user(input));
        replyStatus = null;
        replyText = null;
        directAnswer = false;
    }

    void append(Msg message) { messages.add(message); save(); }

    void reply(Status status, String text) { reply(status, text, true); }

    void reply(Status status, String text, boolean declared) {
        replyStatus = status;
        replyText = text;
        directAnswer = declared;
        save();
    }

    void save() {
        context.control.checkpoint(KEY, json(new Snapshot(1, context.control.revision(), context.request,
                List.copyOf(messages), context.evidence(), context.result(Status.RUNNING, "").agents(),
                context.decision, replyStatus, replyText, directAnswer)));
    }

    /**
     * 装配给模型的历史：超长时先裁剪旧回答，工具请求与结果保持成对。
     *
     * <p>注意这是“副本”：真正的 {@link #messages} 不动，检查点里仍是全文，
     * 这样做的好处是裁剪只影响本次模型请求，审计和恢复仍能看到完整历史。
     * 裁剪顺序是从最早的回答开始替换成占位符——旧回答对当前决策价值最低；
     * 带 tool_use 的助手消息一律跳过，否则会破坏请求/结果配对。
     */
    List<Msg> historyFor(int budget) {
        List<Msg> view = new ArrayList<>(messages);
        if (ContextBuilder.estimate(view) <= budget) return view;
        boolean trimmed = false;
        for (int i = 0; i < view.size(); i++) {
            Msg message = view.get(i);
            if (message.getRole() != MsgRole.ASSISTANT) continue;
            // 带工具请求的助手消息与结果配对，不能截断。
            if (!message.getContentBlocks(ToolUseBlock.class).isEmpty()) continue;
            String text = message.getTextContent();
            if (text == null || text.isBlank() || text.equals(TRIMMED)) continue;
            view.set(i, Msg.builder().role(MsgRole.ASSISTANT).textContent(TRIMMED).build());
            trimmed = true;
            if (ContextBuilder.estimate(view) <= budget) break;
        }
        if (trimmed) context.event("已裁剪早期回答以适配上下文预算");
        return view;
    }

    List<ToolUseBlock> pendingCalls() {
        int lastAssistant = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.ASSISTANT) { lastAssistant = i; break; }
        }
        if (lastAssistant < 0) return List.of();
        Set<String> answered = new HashSet<>();
        for (int i = lastAssistant + 1; i < messages.size(); i++)
            messages.get(i).getContentBlocks(ToolResultBlock.class).forEach(r -> answered.add(r.getId()));
        return messages.get(lastAssistant).getContentBlocks(ToolUseBlock.class).stream()
                .filter(call -> !answered.contains(call.getId())).toList();
    }

    static Msg user(String text) { return Msg.builder().role(MsgRole.USER).textContent(text).build(); }
}
