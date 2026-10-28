package dev.garden.demo.supervisor;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import reactor.core.publisher.Mono;
import java.util.*;

/**
 * Codex compact.rs 的本地模型摘要路径。供应商没有 Responses CompactionTrigger 能力时使用。
 * 压缩请求和失败尝试仅操作副本；完成摘要后才安装完整替换历史。
 */
final class ContextCompactor {
    static final String SUMMARIZATION_PROMPT = """
            You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task.

            Include:
            - Current progress and key decisions made
            - Important context, constraints, or user preferences
            - What remains to be done (clear next steps)
            - Any critical data, examples, or references needed to continue

            Be concise, structured, and focused on helping the next LLM seamlessly continue the work.
            """;
    static final String SUMMARY_PREFIX = "Another language model started to solve this problem and produced a summary of its thinking process. You also have access to the state of the tools that were used by that language model. Use this to build on the work that has already been done and avoid duplicating work. Here is the summary produced by the other language model, use the information in this summary to assist with your own analysis:";
    private final RunContext context;
    private final Model model;

    /** model 已由 RunContext.budgetedCompaction 包装，共用任务预算、取消和绝对截止时间。 */
    ContextCompactor(RunContext context, Model model) {
        this.context = Objects.requireNonNull(context);
        this.model = Objects.requireNonNull(model);
    }

    Mono<Boolean> compactIfNeeded(String prompt, LoopConversation conversation, List<ToolSchema> tools) {
        return Mono.defer(() -> {
            context.check();
            if (inputSize(prompt, conversation.messages, tools) <= ContextBuilder.INPUT_LIMIT) {
                return Mono.just(false);
            }
            return compact(prompt, conversation, tools);
        });
    }

    /** 普通采样被供应商拒绝为超窗时强制压缩；其余错误原样交回调用者。 */
    Mono<Boolean> compactAfterOverflow(String prompt, LoopConversation conversation,
            List<ToolSchema> tools, Throwable error) {
        if (!isContextOverflow(error)) {
            return Mono.error(error);
        }
        return Mono.defer(() -> compact(prompt, conversation, tools));
    }

    static boolean isContextOverflow(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RunControl.ContextLimit) {
                return true;
            }
            String text = Objects.toString(cause.getMessage(), "").toLowerCase(Locale.ROOT);
            if (text.contains("context_length_exceeded") || text.contains("context_window_exceeded")
                    || text.contains("maximum context length") || text.contains("context window exceeded")) {
                return true;
            }
        }
        return false;
    }

    private Mono<Boolean> compact(String prompt, LoopConversation conversation, List<ToolSchema> tools) {
        context.check();
        List<Msg> original = List.copyOf(conversation.messages);
        List<Msg> temporary = new ArrayList<>(normalize(original));
        temporary.add(LoopConversation.user(SUMMARIZATION_PROMPT));
        int before = inputSize(prompt, original, tools);
        return summarize(prompt, temporary).map(summary -> {
            context.check();
            List<Msg> replacement = replacement(prompt, original, tools, summary);
            int after = inputSize(prompt, replacement, tools);
            if (after >= before) {
                throw new RunControl.ContextLimit("压缩未缩小上下文，不能重复压缩同一窗口。");
            }
            conversation.replaceCompacted(replacement, summary);
            context.event("上下文已压缩，第 " + conversation.compactionGeneration() + " 个替换窗口");
            return true;
        });
    }

    private Mono<String> summarize(String prompt, List<Msg> temporary) {
        return Mono.defer(() -> {
            context.check();
            List<Msg> input = ContextBuilder.modelInput(prompt, context.request, context.today,
                    normalize(temporary), List.of());
            GenerateOptions options = GenerateOptions.builder().maxTokens(context.limits.outputTokens())
                    .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build();
            return model.stream(input, List.of(), options).collectList().map(responses -> {
                if (responses.isEmpty()) {
                    throw new IllegalStateException("压缩模型未返回完整摘要");
                }
                StringBuilder text = new StringBuilder();
                for (ChatResponse response : responses) {
                    if ("length".equals(response.getFinishReason())) {
                        throw new IllegalStateException("压缩摘要响应未完成");
                    }
                    for (ContentBlock block : response.getContent()) {
                        if (block instanceof TextBlock part) {
                            text.append(part.getText());
                        } else if (block instanceof ToolUseBlock) {
                            throw new IllegalStateException("压缩模型不能调用工具");
                        }
                    }
                }
                if (text.toString().isBlank()) {
                    throw new IllegalStateException("压缩模型未返回摘要文本");
                }
                return text.toString();
            }).onErrorResume(error -> {
                if (!isContextOverflow(error)) {
                    return Mono.error(error);
                }
                // 最后一项是合成摘要提示，必须保留；原正式历史在重试期间完全不变。
                if (temporary.size() <= 1) {
                    return Mono.error(new RunControl.ContextLimit("摘要提示本身超过模型窗口，无法继续压缩。"));
                }
                removeOldestWithPair(temporary);
                return summarize(prompt, temporary);
            });
        });
    }

    private List<Msg> replacement(String prompt, List<Msg> original, List<ToolSchema> tools, String summary) {
        Msg summaryMessage = Msg.builder().role(MsgRole.USER).textContent(SUMMARY_PREFIX + "\n" + summary)
                .metadata(Map.of("contextCompaction", true)).build();
        LinkedList<Msg> selected = new LinkedList<>();
        selected.add(summaryMessage);
        if (inputSize(prompt, selected, tools) > ContextBuilder.INPUT_LIMIT) {
            throw new RunControl.ContextLimit("摘要与固定指令超过模型窗口，未替换历史。");
        }
        // 上游 local 的20,000近似token是用户保留上限；本适配6656字节窗口更小，
        // 必须同时受实际请求（含系统提示、摘要与工具定义）的剩余空间约束。
        int retainedBytes = 0;
        for (int i = original.size() - 1; i >= 0; i--) {
            Msg message = original.get(i);
            if (message.getRole() != MsgRole.USER || isSummary(message)
                    || Boolean.TRUE.equals(message.getMetadata().get("contextNotice"))
                    || Boolean.TRUE.equals(message.getMetadata().get("agentMessage"))
                    || !message.getContentBlocks(ToolResultBlock.class).isEmpty()) {
                continue;
            }
            int bytes = message.getTextContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            selected.addFirst(message);
            if (retainedBytes + bytes <= 80_000 && inputSize(prompt, selected, tools) <= ContextBuilder.INPUT_LIMIT) {
                retainedBytes += bytes;
                continue;
            }
            selected.removeFirst();
            Msg truncated = truncateToFit(prompt, selected, tools, message);
            if (truncated != null) {
                selected.addFirst(truncated);
            }
            break;
        }
        return List.copyOf(selected);
    }

    private Msg truncateToFit(String prompt, List<Msg> selected, List<ToolSchema> tools, Msg original) {
        String text = original.getTextContent();
        int[] points = text.codePoints().toArray();
        int low = 0;
        int high = points.length;
        Msg best = null;
        Map<String, Object> metadata = new HashMap<>(original.getMetadata());
        metadata.put("retainedSourcesIncomplete", true);
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Msg candidate = original.withContent(List.of(TextBlock.builder()
                    .text(new String(points, 0, mid)).build())).withMetadata(metadata);
            List<Msg> trial = new ArrayList<>(selected);
            trial.add(0, candidate);
            if (inputSize(prompt, trial, tools) <= ContextBuilder.INPUT_LIMIT) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    static boolean isSummary(Msg message) {
        return Boolean.TRUE.equals(message.getMetadata().get("contextCompaction"))
                || message.getTextContent().startsWith(SUMMARY_PREFIX + "\n");
    }

    private int inputSize(String prompt, List<Msg> messages, List<ToolSchema> tools) {
        return ContextBuilder.estimate(Map.of("messages",
                ContextBuilder.modelInput(prompt, context.request, context.today, messages, tools), "tools", tools));
    }

    /** Codex normalize_history：补 aborted 结果，再删除孤立输出，不修改调用者的消息。 */
    static List<Msg> normalize(List<Msg> input) {
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (Msg message : input) {
            message.getContentBlocks(ToolUseBlock.class).forEach(call -> calls.add(call.getId()));
            message.getContentBlocks(ToolResultBlock.class).forEach(result -> results.add(result.getId()));
        }
        List<Msg> normalized = new ArrayList<>();
        for (Msg message : input) {
            List<ContentBlock> kept = message.getContent().stream()
                    .filter(block -> !(block instanceof ToolResultBlock result) || calls.contains(result.getId())).toList();
            if (!kept.isEmpty()) {
                normalized.add(kept.size() == message.getContent().size() ? message : message.withContent(kept));
            }
            for (ToolUseBlock call : message.getContentBlocks(ToolUseBlock.class)) {
                if (!results.contains(call.getId())) {
                    normalized.add(Msg.builder().role(MsgRole.TOOL).content(List.of(
                            new ToolResultBlock(call.getId(), call.getName(), TextBlock.builder().text("aborted").build()))).build());
                }
            }
        }
        return normalized;
    }

    /** 删除最早消息时，仅去掉其配对块，同一消息的其他结果或文本仍保留。 */
    private static void removeOldestWithPair(List<Msg> messages) {
        Msg removed = messages.remove(0);
        Set<String> removedCalls = new HashSet<>();
        Set<String> removedResults = new HashSet<>();
        removed.getContentBlocks(ToolUseBlock.class).forEach(call -> removedCalls.add(call.getId()));
        removed.getContentBlocks(ToolResultBlock.class).forEach(result -> removedResults.add(result.getId()));
        ListIterator<Msg> iterator = messages.listIterator();
        while (iterator.hasNext()) {
            Msg message = iterator.next();
            List<ContentBlock> kept = message.getContent().stream().filter(block ->
                    !(block instanceof ToolResultBlock result && removedCalls.contains(result.getId()))
                    && !(block instanceof ToolUseBlock call && removedResults.contains(call.getId()))).toList();
            if (kept.isEmpty()) {
                iterator.remove();
            } else if (kept.size() != message.getContent().size()) {
                iterator.set(message.withContent(kept));
            }
        }
    }
}
