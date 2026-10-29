package dev.garden.demo.supervisor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按错误类别与工具累计纠错次数，并把计数、连续 schema 错误和禁用工具写入检查点。
 *
 * <p>每次运行创建新实例；重启时从同一 {@link RunControl} 的检查点恢复，因此纠错上限
 * 不会因进程重启而清零。权限拒绝只记事件，不占用可修正预算。
 */
final class CorrectionTracker {
    private static final String TOTAL_KEY = "correction:v1:total";
    private static final String DISABLED_KEY = "correction:v1:disabled";

    private final RunControl control;
    private final SupervisorPolicy.CorrectionPolicy policy;
    private final Set<String> disabled = new LinkedHashSet<>();
    private int total;

    CorrectionTracker(RunControl control, SupervisorPolicy.CorrectionPolicy policy) {
        this.control = control;
        this.policy = policy;
        this.total = readInt(TOTAL_KEY);
        String saved = control.checkpoint(DISABLED_KEY);
        if (saved != null && !saved.isBlank()) {
            Arrays.stream(saved.split(","))
                    .map(String::strip)
                    .filter(name -> !name.isEmpty())
                    .forEach(disabled::add);
        }
    }

    synchronized CorrectionDecision record(CorrectionType type, String tool) {
        if (!policy.enabled() || type == CorrectionType.PERMISSION_DENIED) {
            return CorrectionDecision.PROCEED;
        }
        total++;
        control.checkpoint(TOTAL_KEY, Integer.toString(total));
        if (total > policy.maxTotalCorrections()) {
            return CorrectionDecision.FAIL;
        }
        int count = increment(typeKey(type, tool));
        return switch (type) {
            case INVALID_SCHEMA -> {
                int streak = increment(streakKey(tool));
                if (streak >= policy.maxConsecutiveSchemaErrors()) {
                    disable(tool);
                    yield CorrectionDecision.DISABLE_TOOL;
                }
                yield CorrectionDecision.PROCEED;
            }
            case TOOL_NOT_FOUND -> {
                if (count > policy.maxToolNotFound()) {
                    yield CorrectionDecision.FAIL;
                }
                yield count == policy.maxToolNotFound()
                        ? CorrectionDecision.FORCE_CLARIFY
                        : CorrectionDecision.PROCEED;
            }
            case BUSINESS_RULE_VIOLATION -> {
                if (count > policy.maxBusinessViolations()) {
                    yield CorrectionDecision.FAIL;
                }
                yield CorrectionDecision.PROCEED;
            }
            case INVALID_INPUT, PERMISSION_DENIED -> CorrectionDecision.PROCEED;
        };
    }

    synchronized void success(String tool) {
        control.checkpoint(streakKey(tool), "0");
    }

    synchronized boolean disabled(String tool) {
        return disabled.contains(tool);
    }

    synchronized Set<String> disabledTools() {
        return Set.copyOf(disabled);
    }

    synchronized Map<String, Integer> stats() {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("total", total);
        values.put("disabled", disabled.size());
        return Map.copyOf(values);
    }

    private void disable(String tool) {
        if (disabled.add(tool)) {
            control.checkpoint(DISABLED_KEY, String.join(",", sorted(disabled)));
        }
    }

    private int increment(String key) {
        int value = readInt(key) + 1;
        control.checkpoint(key, Integer.toString(value));
        return value;
    }

    private int readInt(String key) {
        String value = control.checkpoint(key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String typeKey(CorrectionType type, String tool) {
        return "correction:v1:type:" + type.name() + ":" + (tool == null ? "" : tool);
    }

    private static String streakKey(String tool) {
        return "correction:v1:streak:" + (tool == null ? "" : tool);
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        result.sort(String::compareTo);
        return result;
    }
}
