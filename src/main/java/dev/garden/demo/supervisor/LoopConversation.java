package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

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

    public record Snapshot(
            int protocol,
            int revision,
            Request request,
            List<Msg> messages,
            List<Evidence> evidence,
            List<String> agents,
            Decision decision,
            List<String> failedCalls,
            String referenceDate,
            Status replyStatus,
            String replyText) {}

    LoopConversation(RunContext context) {
        this.context = context;
        String saved = context.control.checkpoint(KEY);
        if (saved == null) {
            messages.add(user(context.request.question()));
            // v3.2 未完成任务保留原预算和工具成功缓存，不执行旧 rewrite/intent 检查点。
            context.control.checkpoint("protocol", "3.3");
        } else {
            Snapshot snapshot = JsonUtils.getJsonCodec().fromJson(saved, Snapshot.class);
            if (snapshot.protocol() != 1) throw new IllegalStateException("无法识别的循环记录版本");
            messages.addAll(snapshot.messages());
            context.decision = snapshot.decision();
            if (snapshot.referenceDate() != null)
                context.today = java.time.LocalDate.parse(snapshot.referenceDate());
            if (snapshot.failedCalls() != null)
                snapshot.failedCalls().forEach(context::childFailed);
            for (Evidence e : snapshot.evidence()) {
                if (!context.control.identity().allows(e.tool()))
                    throw new RunControl.Cancelled("历史工具结果权限已变化，不能继续回放");
                context.add(e);
            }
            snapshot.agents().forEach(context::agent);
            replyStatus = snapshot.replyStatus();
            replyText = snapshot.replyText();
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
        context.control.checkpoint(
                KEY,
                json(
                        new Snapshot(
                                1,
                                context.control.revision(),
                                context.request,
                                List.copyOf(messages),
                                context.evidence(),
                                context.result(Status.RUNNING, "").agents(),
                                context.decision,
                                context.failedCalls(),
                                context.today.toString(),
                                replyStatus,
                                replyText)));
    }

    List<ToolUseBlock> pendingCalls() {
        int lastAssistant = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = i;
                break;
            }
        }
        if (lastAssistant < 0) return List.of();
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
