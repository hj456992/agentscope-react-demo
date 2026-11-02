package dev.garden.demo.supervisor.support;

import io.agentscope.core.util.JsonUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于目标文本指纹的轻量语义循环检测。
 *
 * <p>这里不调用模型，也不做向量检索。文本先做 NFKC 归一化、去掉标点和空白，再切成
 * 字符双元组；两次目标文本的双元组 Jaccard 相似度达到阈值时，视为“换了说法但仍在做
 * 同一件事”。检测结果只用于提醒或要求用户确认，不直接判定任务失败。
 *
 * <p>记录写入根任务的共享检查点，因此父、子会话和重启恢复看到同一段近期目标历史。
 * 条目数由策略窗口限制，避免检查点随任务无限增长。
 */
public final class SemanticLoopGuard {
    /** 语义目标记录的检查点键。 */
    public static final String KEY = "loop:v1:semantic-goals";

    /** 单条文本最多保留的字符数；只用于错误提示，不参与相似度计算。 */
    private static final int SAMPLE_LIMIT = 240;

    /** 少于该数量的双元组不参与检测，避免短词产生大量误报。 */
    private static final int MIN_TOKENS = 4;

    private SemanticLoopGuard() {}

    /** 检测结果；{@code value} 非空表示需要写回检查点。 */
    public record Result(
            Decision decision, double similarity, String matchedKey, int count, String value) {
        public static Result proceed(String value) {
            return new Result(Decision.PROCEED, 0.0, "", 0, value);
        }
    }

    /** 循环检测结论。 */
    public enum Decision {
        /** 没有发现相似目标，继续执行。 */
        PROCEED,
        /** 发现相似目标，返回工具错误让模型改变策略。 */
        WARN,
        /** 相似目标反复出现，转为询问用户或要求补充需求。 */
        ASK
    }

    /**
     * 记录一次目标并返回检测结论。
     *
     * @param saved 检查点中的旧记录，空值表示还没有记录
     * @param scope 目标类型，例如 {@code agent} 或 {@code tool}
     * @param key 目标标识，例如子会话路径或工具名
     * @param text 目标文本；调用方应传入模型生成的任务描述或工具参数
     * @param window 保留的近期目标数量
     * @param threshold 相似度阈值，范围 0 到 1
     * @param maxWarnings 允许返回 WARN 的次数，超过后返回 ASK
     * @param crossKey 是否跨同类型目标比较；协作目标使用 true，工具调用使用 false
     */
    public static Result record(
            String saved,
            String scope,
            String key,
            String text,
            int window,
            double threshold,
            int maxWarnings,
            boolean crossKey) {
        if (scope == null || scope.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("语义循环目标不能为空");
        }
        if (window < 1 || threshold <= 0 || threshold > 1 || maxWarnings < 1) {
            throw new IllegalArgumentException("语义循环参数无效");
        }
        Set<String> tokens = tokens(text);
        if (tokens.size() < MIN_TOKENS) {
            return Result.proceed(null);
        }
        List<Map<String, Object>> entries = read(saved);
        int matchedIndex = -1;
        double best = 0.0;
        for (int index = 0; index < entries.size(); index++) {
            Map<String, Object> entry = entries.get(index);
            if (!Objects.equals(scope, entry.get("scope"))) {
                continue;
            }
            if (!crossKey && !Objects.equals(key, entry.get("key"))) {
                continue;
            }
            double similarity = similarity(tokens, tokenSet(entry.get("tokens")));
            if (similarity > best) {
                best = similarity;
                matchedIndex = index;
            }
        }
        if (matchedIndex >= 0 && best >= threshold) {
            Map<String, Object> entry = entries.remove(matchedIndex);
            int warnings = intValue(entry.get("warnings")) + 1;
            entry.put("warnings", warnings);
            entries.add(entry);
            return new Result(
                    warnings <= maxWarnings ? Decision.WARN : Decision.ASK,
                    best,
                    Objects.toString(entry.get("key"), ""),
                    warnings,
                    Contracts.json(entries));
        }
        entries.add(entry(scope, key, tokens, text, 0));
        while (entries.size() > window) {
            entries.remove(0);
        }
        return new Result(Decision.PROCEED, 0.0, "", 0, Contracts.json(entries));
    }

    /** 计算两段文本的字符双元组 Jaccard 相似度；测试和文档使用同一口径。 */
    public static double similarity(String first, String second) {
        return similarity(tokens(first), tokens(second));
    }

    private static double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    /** 归一化文本并切成字符双元组；数字和日期保留，避免把不同日期误判成同一目标。 */
    static Set<String> tokens(String text) {
        if (text == null) {
            return Set.of();
        }
        String normalized =
                Normalizer.normalize(text, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", "");
        if (normalized.length() < 2) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < normalized.length(); index++) {
            result.add(normalized.substring(index, index + 2));
        }
        return result;
    }

    private static Map<String, Object> entry(
            String scope, String key, Set<String> tokens, String text, int warnings) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("scope", scope);
        entry.put("key", key);
        entry.put("tokens", String.join(" ", tokens));
        entry.put("sample", truncate(text));
        entry.put("warnings", warnings);
        return entry;
    }

    private static Set<String> tokenSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        String text = Objects.toString(value, "");
        for (String part : text.split(" ")) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }

    /**
     * 读取检查点记录；空值表示没有历史，内容损坏时直接拒绝，避免静默绕过检测。
     */
    static List<Map<String, Object>> read(String saved) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (saved == null || saved.isBlank()) {
            return result;
        }
        Object raw;
        try {
            raw = JsonUtils.getJsonCodec().fromJson(saved, List.class);
        } catch (RuntimeException error) {
            throw new IllegalStateException("语义循环记录损坏", error);
        }
        if (!(raw instanceof List<?> values)) {
            throw new IllegalStateException("语义循环记录损坏");
        }
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalStateException("语义循环记录损坏");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : map.entrySet()) {
                entry.put(Objects.toString(field.getKey(), ""), field.getValue());
            }
            if (Contracts.blank(Objects.toString(entry.get("scope"), ""))
                    || Contracts.blank(Objects.toString(entry.get("key"), ""))
                    || Contracts.blank(Objects.toString(entry.get("tokens"), ""))) {
                throw new IllegalStateException("语义循环记录损坏");
            }
            result.add(entry);
        }
        return result;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(value, "0"));
        } catch (NumberFormatException error) {
            throw new IllegalStateException("语义循环记录损坏", error);
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= SAMPLE_LIMIT) {
            return Objects.toString(text, "");
        }
        return text.substring(0, SAMPLE_LIMIT) + "…";
    }
}
