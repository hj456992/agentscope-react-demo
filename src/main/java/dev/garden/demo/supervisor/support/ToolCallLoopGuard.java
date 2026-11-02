package dev.garden.demo.supervisor.support;

import io.agentscope.core.util.JsonUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同一工具、同一参数的重复调用计数器。
 *
 * <p>计数保存在单个检查点内，而不是为每个调用新建检查点，避免长任务耗尽检查点名额。
 * 键由工具名和参数指纹组成；只保留最近 {@value #MAX_ENTRIES} 个键，更早的键被淘汰。
 * 淘汰后可能重新计数，但总步数上限仍是最终兜底。
 */
public final class ToolCallLoopGuard {
    /** 计数器所在的检查点键。 */
    public static final String KEY = "loop:v1:tool-calls";

    /** 最多保留的不同工具参数组合数量。 */
    public static final int MAX_ENTRIES = 128;

    private ToolCallLoopGuard() {}

    /** 记录一次调用并返回处理决定；超过上限后不删除已计数项。 */
    public static Result record(
            String saved, String tool, String argumentsFingerprint, int maxRepeated) {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (argumentsFingerprint == null || argumentsFingerprint.isBlank()) {
            throw new IllegalArgumentException("工具参数指纹不能为空");
        }
        if (maxRepeated < 1) {
            throw new IllegalArgumentException("重复工具调用上限必须大于 0");
        }
        Map<String, Integer> counts = read(saved);
        String key = tool + ":" + argumentsFingerprint;
        int count = counts.getOrDefault(key, 0) + 1;
        // 先移除再放回，使最近调用的键保持在末尾，淘汰时优先移除最早的键。
        counts.remove(key);
        counts.put(key, count);
        while (counts.size() > MAX_ENTRIES) {
            String oldest = counts.keySet().iterator().next();
            counts.remove(oldest);
        }
        Decision decision;
        if (count <= maxRepeated) {
            decision = Decision.PROCEED;
        } else if (count == maxRepeated + 1) {
            decision = Decision.WARN;
        } else {
            decision = Decision.FAIL;
        }
        return new Result(decision, count, Contracts.json(counts));
    }

    /** 读取计数表；空值表示还没有调用，损坏内容直接拒绝。 */
    static Map<String, Integer> read(String saved) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (saved == null || saved.isBlank()) {
            return counts;
        }
        Map<?, ?> raw;
        try {
            raw = JsonUtils.getJsonCodec().fromJson(saved, Map.class);
        } catch (RuntimeException error) {
            throw new IllegalStateException("工具调用计数损坏", error);
        }
        if (raw == null) {
            return counts;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Number number)) {
                throw new IllegalStateException("工具调用计数损坏");
            }
            counts.put(entry.getKey().toString(), number.intValue());
        }
        return counts;
    }

    /** 单次调用的处理决定：继续、返回工具错误或终止本轮。 */
    public enum Decision {
        PROCEED,
        WARN,
        FAIL
    }

    /** 计数结果；{@code value} 是更新后的检查点内容。 */
    public record Result(Decision decision, int count, String value) {}
}
