package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.EvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.Status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    /** 断言句前的否定、拒绝、假设和要求语境；只取同一分句内的局部窗口。 */
    private static final Pattern NON_ASSERTION =
            Pattern.compile(
                    "不会|不能|无法|拒绝|不同意|不予|不允许|禁止|不要|别把|不改|不修改|不调整"
                            + "|不篡改|不采用|不认可|不建议|不必|无需|不是|并非|没有"
                            + "|未核实|未能|未经|尚未|未确认"
                            + "|如果|假如|假设|倘若|要是|一旦|就算|即使"
                            + "|要求|请求|希望|提出|改成|改为|修改为|调整为|替换为|设置为");
    /** 数值之后的反驳、不确定或引用说明，同样不构成事实断言。 */
    private static final Pattern POST_QUALIFIER =
            Pattern.compile(
                    "不正确|不成立|不准确|不真实|无依据|缺少依据|没有依据|没有任何依据"
                            + "|无证据|缺少证据|没有证据|没有任何证据"
                            + "|未核实|无法核实|不能确认|无法确认|不确定|不存在|不符|不一致"
                            + "|非事实|错误|是用户要求|属于用户要求|来自用户|是引用|为引用"
                            + "|本身不是|本身也不是|并不是|也不是真实|不属于"
                            + "|非查询数据|不是查询数据|不是查询到的|非真实数据|不是真实数据"
                            + "|非实际数据|不是实际数据|非实测数据|不是实测数据|非预报数据"
                            + "|模拟数据|模拟样例|模拟值|样例数据|示例占位"
                            + "|按.{0,6}(?:要求|说法|指定|指示)"
                            + "|被拒绝|遭拒绝|不允许");
    /** 句首把整句标为转述或用户要求；分句边界会切断该语境，需按整句识别。 */
    private static final Pattern SENTENCE_ATTRIBUTION =
            Pattern.compile("按.{0,8}(?:说法|要求|指定|指示)|你(?:要求|指定)|用户(?:要求|指定)");
    /** 转述句中出现这些词，说明后续数值重新回到现实断言。 */
    private static final Pattern CONTRAST =
            Pattern.compile("实际|真实|事实|实测|但|然而|不过|相反|其实");
    private static final String CLAUSE_BOUNDARY = "。！？!?；;\n，,、";
    private static final String SENTENCE_BOUNDARY = "。！？!?；;\n";

    private AnswerFactChecker() {}

    record Check(boolean conflict, List<String> conflicts, List<String> unsupported) {}

    static Check check(String answer, List<EvidenceDTO> evidence, Set<String> highRiskTools) {
        return check(answer, evidence, highRiskTools, null);
    }

    /**
     * 事实校验的带上下文版本。
     *
     * <p>日期不只来自成功证据：用户问题、工具请求和运行上下文里的“今天”都属于已知时间，
     * 回答转述这些日期不构成编造。数值仍然只允许来自成功证据，避免用失败结果支撑结论。
     */
    static Check check(
            String answer,
            List<EvidenceDTO> evidence,
            Set<String> highRiskTools,
            LocalDate today) {
        List<EvidenceDTO> facts =
                evidence.stream().filter(item -> item.status() == Status.SUCCESS).toList();
        List<String> conflicts = conflicts(facts);
        Map<String, Set<BigDecimal>> numbers = new HashMap<>();
        Set<String> dates = new LinkedHashSet<>();
        for (EvidenceDTO item : evidence) {
            collectDates(item.data(), dates);
        }
        if (today != null) {
            dates.add(today.toString());
        }
        for (EvidenceDTO fact : facts) {
            collect(fact.data(), numbers, dates);
        }
        List<String> unsupported = new ArrayList<>();
        if (answer != null) {
            Set<String> seen = new LinkedHashSet<>();
            Matcher values = VALUE.matcher(answer);
            while (values.find()) {
                if (!asserted(answer, values.start(), values.end())) {
                    continue;
                }
                BigDecimal number = new BigDecimal(values.group(1)).stripTrailingZeros();
                Set<BigDecimal> allowed =
                        numbers.getOrDefault(unitKey(values.group(2)), Set.of());
                if (!allowed.contains(number)) {
                    seen.add(values.group(1) + values.group(2));
                }
            }
            Matcher dateMatcher = DATE.matcher(answer);
            while (dateMatcher.find()) {
                if (asserted(answer, dateMatcher.start(), dateMatcher.end())
                        && !dates.contains(dateMatcher.group())) {
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

    /**
     * 判断数值或日期是否处于事实断言语境。
     *
     * <p>只检查同一分句内的前置和后置文字：引用、否定、拒绝、假设和“要求改成”都不构成
     * 对现实事实的断言。引号、书名号和反引号内的内容整体视为引用。该规则是确定性文本
     * 启发式，不做自然语言推理，宁可漏报也不把拒绝篡改误判为事实错误。
     */
    private static boolean asserted(String text, int start, int end) {
        if (insideQuote(text, start)) {
            return false;
        }
        int sentenceStart = start;
        while (sentenceStart > 0
                && SENTENCE_BOUNDARY.indexOf(text.charAt(sentenceStart - 1)) < 0) {
            sentenceStart--;
        }
        String sentencePrefix = text.substring(sentenceStart, start);
        if (SENTENCE_ATTRIBUTION.matcher(sentencePrefix).find()
                && !CONTRAST.matcher(sentencePrefix).find()) {
            return false;
        }
        int prefixStart = start;
        while (prefixStart > 0 && CLAUSE_BOUNDARY.indexOf(text.charAt(prefixStart - 1)) < 0) {
            prefixStart--;
        }
        String prefix = text.substring(prefixStart, start);
        int suffixEnd = end;
        while (suffixEnd < text.length() && CLAUSE_BOUNDARY.indexOf(text.charAt(suffixEnd)) < 0) {
            suffixEnd++;
        }
        String suffix = text.substring(end, suffixEnd);
        if (NON_ASSERTION.matcher(prefix).find()
                || POST_QUALIFIER.matcher(suffix).find()) {
            return false;
        }
        return true;
    }

    /** 返回字符位置是否落在成对引号、书名号或反引号内部。 */
    private static boolean insideQuote(String text, int position) {
        char[][] pairs = {{'“', '”'}, {'「', '」'}, {'『', '』'}, {'"', '"'}, {'`', '`'}};
        for (char[] pair : pairs) {
            int open = -1;
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (current == pair[0] && (open < 0 || pair[0] != pair[1])) {
                    open = index;
                } else if (current == pair[1] && open >= 0) {
                    if (position > open && position < index) {
                        return true;
                    }
                    open = -1;
                }
            }
        }
        return false;
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
    private static void collectDates(Object value, Set<String> dates) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object item = entry.getValue();
                if (item instanceof String text && DATE.matcher(text).matches()) {
                    dates.add(text);
                }
                collectDates(item, dates);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectDates(item, dates);
            }
        }
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
