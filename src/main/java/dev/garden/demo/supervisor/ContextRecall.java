package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;

import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 压缩前原始会话记录的持久化与分页召回。
 *
 * <p>每次压缩把被替换的完整历史按角色、工具调用和工具结果展开为文本，写入
 * {@code context-recall:v1:<压缩代数>} 检查点。模型需要精确原文时调用
 * {@code recall_context}，按字符偏移分页读取，不必依赖摘要复述。
 */
final class ContextRecall {
    /** 单份原始记录的最大字符数；低于检查点 131072 的存储上限。 */
    static final int MAX_CHARS = 120_000;

    /** 未指定分页大小时的默认字符数。 */
    static final int DEFAULT_LIMIT = 4_000;

    /** 单次召回的字符数上限。 */
    static final int MAX_LIMIT = 8_000;

    private ContextRecall() {}

    /** 压缩前保存原始历史；召回开关关闭时不写检查点。 */
    static void store(RunContext context, int generation, List<Msg> messages) {
        if (!context.control.policy().summary().recallEnabled()) {
            return;
        }
        String transcript = transcript(messages);
        if (transcript.codePointCount(0, transcript.length()) > MAX_CHARS) {
            transcript = prefix(transcript, MAX_CHARS) + "\n[记录超过存储上限，后续内容已截断]";
        }
        context.control.checkpoint(key(generation), transcript);
        context.event("压缩原文已保存，第 " + generation + " 个替换窗口");
    }

    /** 读取指定压缩代数的原始记录；不存在时返回 {@code null}。 */
    static String read(RunContext context, int generation) {
        return context.control.checkpoint(key(generation));
    }

    /** 召回工具本身；只在摘要策略开启召回时注册。 */
    static AgentTool tool(RunContext context) {
        return new AgentTool() {
            public String getName() {
                return "recall_context";
            }

            public String getDescription() {
                return "按压缩编号和字符偏移读取压缩前的原始会话记录，用于核对摘要遗漏的细节。";
            }

            public Map<String, Object> getParameters() {
                return Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "compression_id",
                                Map.of("type", "integer", "minimum", 1),
                                "offset",
                                Map.of("type", "integer", "minimum", 0),
                                "limit",
                                Map.of(
                                        "type",
                                        "integer",
                                        "minimum",
                                        1,
                                        "maximum",
                                        MAX_LIMIT)),
                        "required",
                        List.of("compression_id"),
                        "additionalProperties",
                        false);
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
                return Mono.fromCallable(() -> recall(context, call.getInput()));
            }
        };
    }

    private static ToolResultBlock recall(RunContext context, Map<String, Object> input) {
        context.check();
        // 回读压缩原文前，按当前身份重新核对历史工具权限；权限已变化时不返回原文。
        context.restorePermissions(context.requiredPermissions());
        int generation = number(input.get("compression_id"), -1);
        if (generation < 1) {
            return ToolResultBlock.error("compression_id 必须是大于 0 的压缩编号。");
        }
        String saved = read(context, generation);
        if (saved == null) {
            return ToolResultBlock.error("没有找到该压缩编号对应的原始记录。");
        }
        int[] points = saved.codePoints().toArray();
        int offset = Math.max(0, number(input.get("offset"), 0));
        int limit = number(input.get("limit"), DEFAULT_LIMIT);
        limit = Math.min(MAX_LIMIT, Math.max(1, limit));
        if (offset >= points.length) {
            return ToolResultBlock.error(
                    "offset 超过记录长度；当前记录共 " + points.length + " 个字符。");
        }
        int end = Math.min(points.length, offset + limit);
        String text = new String(points, offset, end - offset);
        context.event(
                "上下文召回：第 "
                        + generation
                        + " 代，字符 "
                        + offset
                        + "-"
                        + end
                        + " / "
                        + points.length
                        + "，回读 "
                        + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        + " 字节，来源 "
                        + source(saved, offset)
                        + "，权限复核通过");
        return ToolResultBlock.text(
                "[原始记录 "
                        + generation
                        + "，字符 "
                        + offset
                        + "-"
                        + end
                        + " / "
                        + points.length
                        + "]\n"
                        + text);
    }

    /** 从回读位置向前寻找最近的来源标记，用于事件中区分用户原文和工具结果。 */
    private static String source(String transcript, int offset) {
        int position = Math.max(0, Math.min(transcript.length(), offset));
        int start = transcript.lastIndexOf('\n', position - 1) + 1;
        if (start >= transcript.length()) {
            return "记录末尾";
        }
        int end = transcript.indexOf('\n', start);
        String line = transcript.substring(start, end < 0 ? transcript.length() : end).strip();
        return line.startsWith("[") ? sourcePrefix(line, 80) : "字符 " + offset;
    }

    private static String sourcePrefix(String text, int maximumCodePoints) {
        int[] points = text.codePoints().toArray();
        if (points.length <= maximumCodePoints) {
            return text;
        }
        return new String(points, 0, maximumCodePoints) + "…";
    }

    /** 把消息展开为可检索文本；工具调用参数用规范化 JSON 保存。 */
    static String transcript(List<Msg> messages) {
        StringBuilder text = new StringBuilder();
        for (Msg message : messages) {
            String role = message.getRole().name();
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock part) {
                    append(text, "[" + role + "]", part.getText());
                } else if (block instanceof ToolUseBlock call) {
                    append(
                            text,
                            "[工具调用 " + Objects.toString(call.getName(), "") + "]",
                            json(call.getInput()));
                } else if (block instanceof ToolResultBlock result) {
                    StringBuilder output = new StringBuilder();
                    for (ContentBlock part : result.getOutput()) {
                        if (part instanceof TextBlock value) {
                            output.append(value.getText());
                        }
                    }
                    append(
                            text,
                            "[工具结果 " + Objects.toString(result.getName(), "") + "]",
                            output.toString());
                } else {
                    append(text, "[" + role + "]", json(block));
                }
            }
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String marker, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append(marker).append('\n').append(content);
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static String prefix(String text, int maximumCodePoints) {
        int[] points = text.codePoints().toArray();
        if (points.length <= maximumCodePoints) {
            return text;
        }
        return new String(points, 0, maximumCodePoints);
    }

    /** 检查点键；压缩代数从 1 开始，与 {@code compactionGeneration} 对齐。 */
    static String key(int generation) {
        return "context-recall:v1:" + generation;
    }
}
