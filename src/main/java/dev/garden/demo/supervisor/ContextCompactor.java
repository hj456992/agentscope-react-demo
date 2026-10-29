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
    static final String SUMMARIZATION_PROMPT =
            """
You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task.

Include:
- Current progress and key decisions made
- Important context, constraints, or user preferences
- What remains to be done (clear next steps)
- Any critical data, examples, or references needed to continue

Be concise, structured, and focused on helping the next LLM seamlessly continue the work.
""";
    static final String SUMMARY_PREFIX =
            "Another language model started to solve this problem and produced a summary of its"
                + " thinking process. You also have access to the state of the tools that were used"
                + " by that language model. Use this to build on the work that has already been"
                + " done and avoid duplicating work. Here is the summary produced by the other"
                + " language model, use the information in this summary to assist with your own"
                + " analysis:";
    private final RunContext context;
    private final Model model;

    /** model 已由 RunContext.budgetedCompaction 包装，共用任务预算、取消和绝对截止时间。 */
    ContextCompactor(RunContext context, Model model) {
        this.context = Objects.requireNonNull(context);
        this.model = Objects.requireNonNull(model);
    }

    Mono<Boolean> compactIfNeeded(
            String prompt, LoopConversation conversation, List<ToolSchema> tools) {
        return Mono.defer(
                () -> {
                    context.check();
                    if (inputSize(prompt, conversation.messages, tools)
                            <= ContextBuilder.INPUT_LIMIT) {
                        return Mono.just(false);
                    }
                    return compact(prompt, conversation, tools);
                });
    }

    /** 普通采样被供应商拒绝为超窗时强制压缩；其余错误原样交回调用者。 */
    Mono<Boolean> compactAfterOverflow(
            String prompt, LoopConversation conversation, List<ToolSchema> tools, Throwable error) {
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
            if (text.contains("context_length_exceeded")
                    || text.contains("context_window_exceeded")
                    || text.contains("maximum context length")
                    || text.contains("context window exceeded")) {
                return true;
            }
        }
        return false;
    }

    private Mono<Boolean> compact(
            String prompt, LoopConversation conversation, List<ToolSchema> tools) {
        context.check();
        List<Msg> original = List.copyOf(conversation.messages);
        List<Msg> temporary = new ArrayList<>(normalize(original));
        temporary.add(LoopConversation.user(SUMMARIZATION_PROMPT));
        int before = inputSize(prompt, original, tools);
        List<String> requirements =
                original.stream().filter(ContextCompactor::isRealUser).map(Msg::getTextContent).toList();
        return summarize(prompt, temporary)
                .map(
                        summary -> {
                            context.check();
                            List<Msg> replacement = replacement(prompt, original, tools, summary);
                            int after = inputSize(prompt, replacement, tools);
                            if (after >= before) {
                                throw new RunControl.ContextLimit("压缩未缩小上下文，不能重复压缩同一窗口。");
                            }
                            conversation.replaceCompacted(replacement, summary);
                            context.event(
                                    "上下文已压缩，第 " + conversation.compactionGeneration() + " 个替换窗口");
                            reportFaithfulness(summary, requirements, before, after);
                            return true;
                        });
    }

    private Mono<String> summarize(String prompt, List<Msg> temporary) {
        return Mono.defer(
                () -> {
                    context.check();
                    List<Msg> input =
                            ContextBuilder.modelInput(
                                    prompt,
                                    context.request,
                                    context.today,
                                    normalize(temporary),
                                    List.of());
                    GenerateOptions options =
                            GenerateOptions.builder()
                                    .maxTokens(context.limits.outputTokens())
                                    .executionConfig(
                                            ExecutionConfig.builder().maxAttempts(1).build())
                                    .build();
                    return model.stream(input, List.of(), options)
                            .collectList()
                            .map(
                                    responses -> {
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
                                    })
                            .onErrorResume(
                                    error -> {
                                        if (!isContextOverflow(error)) {
                                            return Mono.error(error);
                                        }
                                        // 最后一项是合成摘要提示，必须保留；原正式历史在重试期间完全不变。
                                        if (temporary.size() <= 1) {
                                            return Mono.error(
                                                    new RunControl.ContextLimit(
                                                            "摘要提示本身超过模型窗口，无法继续压缩。"));
                                        }
                                        removeOldestWithPair(temporary);
                                        return summarize(prompt, temporary);
                                    });
                });
    }

    /**
     * 构造替换历史：保留的真实用户条件、当前用户问题、最近完整工具配对，摘要固定放最后。
     *
     * <p>当前问题先放入；放不下时只截断它并标记，绝不因摘要或旧历史丢弃它。任何一步
     * 失败都抛 {@link RunControl.ContextLimit}，调用者尚未安装替换，因此原历史保持不变。
     */
    private List<Msg> replacement(
            String prompt, List<Msg> original, List<ToolSchema> tools, String summary) {
        int currentIndex = -1;
        for (int index = original.size() - 1; index >= 0; index--) {
            if (isRealUser(original.get(index))) {
                currentIndex = index;
                break;
            }
        }
        List<Selected> selected = new ArrayList<>();
        // 当前问题优先保留全文；摘要和最近工具配对只能在它之后争取剩余空间。
        Msg minimalReserve = summaryMessage("…", true);
        Msg summaryReserve = summaryMessage(summary, false);
        if (currentIndex >= 0) {
            Msg current = original.get(currentIndex);
            Msg fitted =
                    fitAtEnd(prompt, selectedMessages(selected), tools, current, minimalReserve);
            if (fitted == null) {
                throw new RunControl.ContextLimit("当前问题与固定指令超过模型窗口，未替换历史。");
            }
            selected.add(new Selected(currentIndex, fitted));
            for (int index = currentIndex - 1; index >= 0; index--) {
                Msg message = original.get(index);
                if (!isRealUser(message)) {
                    continue;
                }
                Msg older =
                        fitAtFront(
                                prompt,
                                selectedMessages(selected),
                                tools,
                                message,
                                summaryReserve);
                if (older == null) {
                    break;
                }
                selected.add(0, new Selected(index, older));
                if (older != message) {
                    // 已经截断更早的条件，再向前保留只会得到空内容。
                    break;
                }
            }
        }
        Pair pair = latestCompletePair(original);
        if (pair != null) {
            // 配对必须给完整摘要让路：摘要携带继续任务所需的工具状态，比可重新获取的
            // 结果正文更重要；放不下完整配对时宁可不保留这一轮。
            List<Msg> fittedPair = fitPair(prompt, selected, tools, pair, summaryReserve);
            if (fittedPair != null) {
                for (int index = 0; index < fittedPair.size(); index++) {
                    selected.add(new Selected(pair.indices().get(index), fittedPair.get(index)));
                }
                selected.sort(Comparator.comparingInt(Selected::index));
            }
        }
        List<Msg> result = new ArrayList<>(selectedMessages(selected));
        Msg summaryMessage = summaryMessage(summary, false);
        result.add(summaryMessage);
        if (!fits(prompt, result, tools)) {
            result.remove(result.size() - 1);
            Msg truncated = truncateSummary(prompt, result, tools, summary);
            if (truncated == null) {
                throw new RunControl.ContextLimit("当前问题、工具配对与摘要无法同时放入模型窗口，未替换历史。");
            }
            result.add(truncated);
        }
        if (!fits(prompt, result, tools)) {
            throw new RunControl.ContextLimit("替换历史仍超过模型窗口，未替换历史。");
        }
        boolean currentPresent = false;
        for (Selected item : selected) {
            if (item.index() == currentIndex) {
                currentPresent = true;
                break;
            }
        }
        if (currentIndex >= 0 && !currentPresent) {
            throw new RunControl.ContextLimit("替换历史缺少当前用户问题，未替换历史。");
        }
        return List.copyOf(result);
    }

    private void reportFaithfulness(
            String summary, List<String> requirements, int before, int after) {
        SupervisorPolicy.SummaryPolicy policy = context.control.policy().summary();
        SummaryFaithfulness.Report report =
                SummaryFaithfulness.evaluate(
                        summary, requirements, before, after, policy.fidelityThreshold());
        context.event(
                "摘要忠实度：保留率 "
                        + percent(report.retention())
                        + "，压缩率 "
                        + percent(report.compressionRatio()));
        if (policy.alertEnabled() && !report.passed()) {
            context.event(
                    "摘要忠实度告警：缺少 " + String.join("、", report.missing()));
        }
    }

    private static String percent(double value) {
        return Math.round(value * 100.0) + "%";
    }

    private Msg fitAtEnd(
            String prompt,
            List<Msg> selected,
            List<ToolSchema> tools,
            Msg original,
            Msg summaryReserve) {
        List<Msg> trial = new ArrayList<>(selected);
        trial.add(original);
        trial.add(summaryReserve);
        if (fits(prompt, trial, tools)) {
            return original;
        }
        return truncateToFit(prompt, selected, tools, original, false, summaryReserve);
    }

    private Msg fitAtFront(
            String prompt,
            List<Msg> selected,
            List<ToolSchema> tools,
            Msg original,
            Msg summaryReserve) {
        List<Msg> trial = new ArrayList<>(selected);
        trial.add(0, original);
        trial.add(summaryReserve);
        if (fits(prompt, trial, tools)) {
            return original;
        }
        return truncateToFit(prompt, selected, tools, original, true, summaryReserve);
    }

    /** 二分查找能放入窗口的最长前缀；至少要保留 1 个 code point，否则返回 null。 */
    private Msg truncateToFit(
            String prompt,
            List<Msg> selected,
            List<ToolSchema> tools,
            Msg original,
            boolean front,
            Msg summaryReserve) {
        String text = original.getTextContent();
        int[] points = text.codePoints().toArray();
        if (points.length == 0) {
            return null;
        }
        int low = 1;
        int high = points.length;
        Msg best = null;
        Map<String, Object> metadata = new HashMap<>(original.getMetadata());
        metadata.put("retainedSourcesIncomplete", true);
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Msg candidate =
                    original.withContent(
                                    List.of(
                                            TextBlock.builder()
                                                    .text(new String(points, 0, mid))
                                                    .build()))
                            .withMetadata(metadata);
            List<Msg> trial = new ArrayList<>(selected);
            if (front) {
                trial.add(0, candidate);
            } else {
                trial.add(candidate);
            }
            trial.add(summaryReserve);
            if (fits(prompt, trial, tools)) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    /** 保留最近一次调用及其全部结果；缺结果时返回 null，避免产生半截配对。 */
    private static Pair latestCompletePair(List<Msg> original) {
        for (int assistantIndex = original.size() - 1; assistantIndex >= 0; assistantIndex--) {
            Msg assistant = original.get(assistantIndex);
            if (assistant.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            List<ToolUseBlock> calls = assistant.getContentBlocks(ToolUseBlock.class);
            if (calls.isEmpty()) {
                continue;
            }
            Set<String> callIds = new HashSet<>();
            for (ToolUseBlock call : calls) {
                callIds.add(call.getId());
            }
            Set<String> answered = new HashSet<>();
            List<Integer> indices = new ArrayList<>();
            indices.add(assistantIndex);
            for (int index = assistantIndex + 1; index < original.size(); index++) {
                Msg message = original.get(index);
                if (message.getRole() == MsgRole.ASSISTANT) {
                    break;
                }
                boolean matched = false;
                for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                    if (callIds.contains(result.getId())) {
                        answered.add(result.getId());
                        matched = true;
                    }
                }
                if (matched) {
                    indices.add(index);
                }
            }
            if (answered.containsAll(callIds)) {
                List<Msg> messages = new ArrayList<>();
                for (int index : indices) {
                    messages.add(original.get(index));
                }
                return new Pair(List.copyOf(indices), List.copyOf(messages));
            }
        }
        return null;
    }

    /**
     * 配对整体放不下时按统一 code point 上限截断每个文本块；调用块与结果块数量保持不变，
     * 不会拆出只有调用或只有结果的历史。
     */
    private List<Msg> fitPair(
            String prompt,
            List<Selected> selected,
            List<ToolSchema> tools,
            Pair pair,
            Msg summaryReserve) {
        List<Msg> full = withReserve(mergedMessages(selected, pair, pair.messages()), summaryReserve);
        if (fits(prompt, full, tools)) {
            return pair.messages();
        }
        int maximum = 0;
        for (Msg message : pair.messages()) {
            maximum = Math.max(maximum, textWidth(message));
        }
        if (maximum == 0) {
            return null;
        }
        int low = 1;
        int high = maximum;
        List<Msg> best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            List<Msg> truncated = truncatePairMessages(pair.messages(), middle);
            List<Msg> trial =
                    withReserve(mergedMessages(selected, pair, truncated), summaryReserve);
            if (fits(prompt, trial, tools)) {
                best = truncated;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private static List<Msg> withReserve(List<Msg> messages, Msg reserve) {
        List<Msg> result = new ArrayList<>(messages);
        result.add(reserve);
        return result;
    }

    private static List<Msg> mergedMessages(
            List<Selected> selected, Pair pair, List<Msg> pairMessages) {
        List<Selected> merged = new ArrayList<>(selected);
        for (int index = 0; index < pairMessages.size(); index++) {
            merged.add(new Selected(pair.indices().get(index), pairMessages.get(index)));
        }
        merged.sort(Comparator.comparingInt(Selected::index));
        List<Msg> messages = new ArrayList<>();
        for (Selected item : merged) {
            messages.add(item.message());
        }
        return messages;
    }

    private static List<Msg> truncatePairMessages(List<Msg> messages, int maximumCodePoints) {
        List<Msg> result = new ArrayList<>();
        for (Msg message : messages) {
            List<ContentBlock> blocks = new ArrayList<>();
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock text) {
                    blocks.add(
                            TextBlock.builder()
                                    .text(prefix(text.getText(), maximumCodePoints))
                                    .build());
                } else if (block instanceof ToolResultBlock output) {
                    List<ContentBlock> parts = new ArrayList<>();
                    for (ContentBlock part : output.getOutput()) {
                        if (part instanceof TextBlock text) {
                            parts.add(
                                    TextBlock.builder()
                                            .text(prefix(text.getText(), maximumCodePoints))
                                            .build());
                        } else {
                            parts.add(part);
                        }
                    }
                    blocks.add(new ToolResultBlock(output.getId(), output.getName(), parts));
                } else {
                    blocks.add(block);
                }
            }
            Map<String, Object> metadata = new HashMap<>(message.getMetadata());
            metadata.put("retainedSourcesIncomplete", true);
            result.add(message.withContent(blocks).withMetadata(metadata));
        }
        return List.copyOf(result);
    }

    private static int textWidth(Msg message) {
        int width = 0;
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock text) {
                width = Math.max(width, text.getText().codePointCount(0, text.getText().length()));
            } else if (block instanceof ToolResultBlock output) {
                for (ContentBlock part : output.getOutput()) {
                    if (part instanceof TextBlock text) {
                        width =
                                Math.max(
                                        width,
                                        text.getText().codePointCount(0, text.getText().length()));
                    }
                }
            }
        }
        return width;
    }

    private static String prefix(String text, int maximumCodePoints) {
        int[] points = text.codePoints().toArray();
        if (points.length <= maximumCodePoints) {
            return text;
        }
        return new String(points, 0, maximumCodePoints);
    }

    private Msg truncateSummary(
            String prompt, List<Msg> selected, List<ToolSchema> tools, String summary) {
        int[] points = summary.codePoints().toArray();
        if (points.length == 0) {
            return null;
        }
        int low = 1;
        int high = points.length;
        Msg best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Msg candidate = summaryMessage(new String(points, 0, middle), true);
            List<Msg> trial = new ArrayList<>(selected);
            trial.add(candidate);
            if (fits(prompt, trial, tools)) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private static Msg summaryMessage(String summary, boolean truncated) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("contextCompaction", true);
        if (truncated) {
            metadata.put("summaryTruncated", true);
        }
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(SUMMARY_PREFIX + "\n" + summary)
                .metadata(metadata)
                .build();
    }

    private static List<Msg> selectedMessages(List<Selected> selected) {
        List<Msg> messages = new ArrayList<>();
        for (Selected item : selected) {
            messages.add(item.message());
        }
        return messages;
    }

    private boolean fits(String prompt, List<Msg> messages, List<ToolSchema> tools) {
        return inputSize(prompt, messages, tools) <= ContextBuilder.INPUT_LIMIT;
    }

    /** 真实用户消息：排除摘要、上下文通知、代理消息、继承历史和工具结果。 */
    private static boolean isRealUser(Msg message) {
        return message.getRole() == MsgRole.USER
                && !isSummary(message)
                && !Boolean.TRUE.equals(message.getMetadata().get("contextNotice"))
                && !Boolean.TRUE.equals(message.getMetadata().get("agentMessage"))
                && !Boolean.TRUE.equals(message.getMetadata().get("inheritedUserMessage"))
                && message.getContentBlocks(ToolResultBlock.class).isEmpty();
    }

    private record Selected(int index, Msg message) {}

    private record Pair(List<Integer> indices, List<Msg> messages) {}

    static boolean isSummary(Msg message) {
        return Boolean.TRUE.equals(message.getMetadata().get("contextCompaction"))
                || message.getTextContent().startsWith(SUMMARY_PREFIX + "\n");
    }

    private int inputSize(String prompt, List<Msg> messages, List<ToolSchema> tools) {
        return ContextBuilder.estimate(
                Map.of(
                        "messages",
                        ContextBuilder.modelInput(
                                prompt, context.request, context.today, messages, tools),
                        "tools",
                        tools));
    }

    /** Codex normalize_history：补 aborted 结果，再删除孤立输出，不修改调用者的消息。 */
    static List<Msg> normalize(List<Msg> input) {
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (Msg message : input) {
            message.getContentBlocks(ToolUseBlock.class).forEach(call -> calls.add(call.getId()));
            message.getContentBlocks(ToolResultBlock.class)
                    .forEach(result -> results.add(result.getId()));
        }
        List<Msg> normalized = new ArrayList<>();
        for (Msg message : input) {
            List<ContentBlock> kept =
                    message.getContent().stream()
                            .filter(
                                    block ->
                                            !(block instanceof ToolResultBlock result)
                                                    || calls.contains(result.getId()))
                            .toList();
            if (!kept.isEmpty()) {
                normalized.add(
                        kept.size() == message.getContent().size()
                                ? message
                                : message.withContent(kept));
            }
            for (ToolUseBlock call : message.getContentBlocks(ToolUseBlock.class)) {
                if (!results.contains(call.getId())) {
                    normalized.add(
                            Msg.builder()
                                    .role(MsgRole.TOOL)
                                    .content(
                                            List.of(
                                                    new ToolResultBlock(
                                                            call.getId(),
                                                            call.getName(),
                                                            TextBlock.builder()
                                                                    .text("aborted")
                                                                    .build())))
                                    .build());
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
        removed.getContentBlocks(ToolUseBlock.class)
                .forEach(call -> removedCalls.add(call.getId()));
        removed.getContentBlocks(ToolResultBlock.class)
                .forEach(result -> removedResults.add(result.getId()));
        ListIterator<Msg> iterator = messages.listIterator();
        while (iterator.hasNext()) {
            Msg message = iterator.next();
            List<ContentBlock> kept =
                    message.getContent().stream()
                            .filter(
                                    block ->
                                            !(block instanceof ToolResultBlock result
                                                            && removedCalls.contains(
                                                                    result.getId()))
                                                    && !(block instanceof ToolUseBlock call
                                                            && removedResults.contains(
                                                                    call.getId())))
                            .toList();
            if (kept.isEmpty()) {
                iterator.remove();
            } else if (kept.size() != message.getContent().size()) {
                iterator.set(message.withContent(kept));
            }
        }
    }
}
