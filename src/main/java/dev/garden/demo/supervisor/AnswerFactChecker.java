package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.EvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.Status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性事实校验：检查答案中的数值、单位和日期是否能在成功证据中找到。
 *
 * <p>同一工具同一条件出现多个不同成功结果时报告冲突；非结构化文本和中文数字不做猜测。
 * 该检查不调用模型，也不改变工具证据本身。
 */
final class AnswerFactChecker {
    private static final Pattern VALUE =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(℃|元|分钟|张)");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private AnswerFactChecker() {}

    record Check(boolean conflict, List<String> conflicts, List<String> unsupported) {}

    static Check check(String answer, List<EvidenceDTO> evidence, Set<String> highRiskTools) {
        List<EvidenceDTO> facts =
                evidence.stream().filter(item -> item.status() == Status.SUCCESS).toList();
        List<String> conflicts = conflicts(facts);
        Map<String, Set<BigDecimal>> numbers = new HashMap<>();
        Set<String> dates = new LinkedHashSet<>();
        for (EvidenceDTO fact : facts) {
            collect(fact.data(), numbers, dates);
        }
        List<String> unsupported = new ArrayList<>();
        if (answer != null) {
            Set<String> seen = new LinkedHashSet<>();
            Matcher values = VALUE.matcher(answer);
            while (values.find()) {
                BigDecimal number = new BigDecimal(values.group(1)).stripTrailingZeros();
                Set<BigDecimal> allowed =
                        numbers.getOrDefault(unitKey(values.group(2)), Set.of());
                if (!allowed.contains(number)) {
                    seen.add(values.group(1) + values.group(2));
                }
            }
            Matcher dateMatcher = DATE.matcher(answer);
            while (dateMatcher.find()) {
                if (!dates.contains(dateMatcher.group())) {
                    seen.add(dateMatcher.group());
                }
            }
            unsupported.addAll(seen);
        }
        boolean highRisk =
                evidence.stream().anyMatch(item -> highRiskTools.contains(item.tool()));
        if (!highRisk) {
            unsupported.clear();
        }
        return new Check(!conflicts.isEmpty(), List.copyOf(conflicts), List.copyOf(unsupported));
    }

    private static List<String> conflicts(List<EvidenceDTO> facts) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (EvidenceDTO fact : facts) {
            String key = fact.tool() + ":" + RunContext.conditionKey(fact);
            grouped.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                    .add(RunContext.fingerprint(fact.data()));
        }
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collect(
            Object value, Map<String, Set<BigDecimal>> numbers, Set<String> dates) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object item = entry.getValue();
                String unit = unit(key);
                if (unit != null && item instanceof Number number) {
                    numbers.computeIfAbsent(unit, ignored -> new LinkedHashSet<>())
                            .add(new BigDecimal(number.toString()).stripTrailingZeros());
                } else if (unit != null && item instanceof String text) {
                    try {
                        numbers.computeIfAbsent(unit, ignored -> new LinkedHashSet<>())
                                .add(new BigDecimal(text).stripTrailingZeros());
                    } catch (NumberFormatException ignored) {
                        /* 非数值字段不参与比对。 */
                    }
                }
                if (item instanceof String text && DATE.matcher(text).matches()) {
                    dates.add(text);
                }
                collect(item, numbers, dates);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collect(item, numbers, dates);
            }
        }
    }

    private static String unit(String key) {
        return switch (key) {
            case "temperature" -> "℃";
            case "price" -> "元";
            case "durationMinutes" -> "分钟";
            case "availableSeats" -> "张";
            default -> null;
        };
    }

    private static String unitKey(String unit) {
        return switch (unit) {
            case "℃" -> "℃";
            case "元" -> "元";
            case "分钟" -> "分钟";
            case "张" -> "张";
            default -> unit;
        };
    }
}
