package dev.garden.demo.supervisor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性摘要忠实度评测：只检查用户原文中的日期、城市、座席、金额、分钟、天数和温度 token。
 *
 * <p>这不是自然语言推理；完整语义级评测需要独立评测集。保留率用于发现明显丢失，
 * 不足以证明摘要忠实。评测只读文本，不改任务状态。
 */
final class SummaryFaithfulness {
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern MONEY = Pattern.compile("\\d+(?:\\.\\d+)?元");
    private static final Pattern MINUTES = Pattern.compile("\\d+分钟");
    private static final Pattern DAYS = Pattern.compile("\\d+天");
    private static final Pattern TEMPERATURE = Pattern.compile("-?\\d+℃");
    private static final Set<String> SEATS =
            Set.of("二等座", "一等座", "商务座", "硬座", "软座", "硬卧", "软卧", "无座");
    private static final List<String> CITIES =
            List.of(
                    "北京", "上海", "杭州", "南京", "苏州", "宁波", "广州", "深圳", "天津", "重庆",
                    "成都", "武汉", "西安", "长沙", "郑州", "济南", "青岛", "合肥", "福州", "厦门",
                    "南昌", "昆明", "贵阳", "南宁", "海口", "沈阳", "大连", "长春", "哈尔滨", "石家庄",
                    "太原", "兰州", "西宁", "银川", "呼和浩特", "乌鲁木齐", "拉萨", "香港", "澳门", "台北");

    private SummaryFaithfulness() {}

    record Report(
            double retention,
            List<String> missing,
            double compressionRatio,
            boolean passed) {}

    static Report evaluate(
            String summary,
            List<String> requirements,
            int originalBytes,
            int replacementBytes,
            double threshold) {
        List<String> critical = criticalTokens(requirements);
        List<String> missing = new ArrayList<>();
        for (String token : critical) {
            if (summary == null || !summary.contains(token)) {
                missing.add(token);
            }
        }
        double retention =
                critical.isEmpty()
                        ? 1.0
                        : (double) (critical.size() - missing.size()) / critical.size();
        double ratio =
                originalBytes <= 0
                        ? 0.0
                        : Math.max(
                                0.0,
                                Math.min(
                                        1.0,
                                        1.0 - (double) replacementBytes / (double) originalBytes));
        return new Report(retention, List.copyOf(missing), ratio, retention >= threshold);
    }

    /** 按日期、金额、时长、天数、温度、座席、城市的固定顺序提取并去重。 */
    static List<String> criticalTokens(List<String> requirements) {
        Set<String> tokens = new LinkedHashSet<>();
        if (requirements == null) {
            return List.of();
        }
        for (String text : requirements) {
            if (text == null || text.isBlank()) {
                continue;
            }
            addMatches(tokens, DATE, text);
            addMatches(tokens, MONEY, text);
            addMatches(tokens, MINUTES, text);
            addMatches(tokens, DAYS, text);
            addMatches(tokens, TEMPERATURE, text);
            for (String seat : SEATS) {
                if (text.contains(seat)) {
                    tokens.add(seat);
                }
            }
            for (String city : CITIES) {
                if (text.contains(city)) {
                    tokens.add(city);
                }
            }
            addRoutes(tokens, text);
        }
        return List.copyOf(tokens);
    }

    /**
     * 提取带方向的城市短语，例如“杭州到上海”“从杭州出发去上海”。
     *
     * <p>单独保存城市名会丢失出发地与目的地的顺序；保留完整短语能让压缩后的摘要
     * 仍按用户原文方向理解行程。
     */
    private static void addRoutes(Set<String> tokens, String text) {
        for (String from : CITIES) {
            for (String to : CITIES) {
                if (from.equals(to)) {
                    continue;
                }
                for (String route :
                        List.of(
                                from + "到" + to,
                                from + "至" + to,
                                from + "去" + to,
                                from + "出发去" + to,
                                from + "出发到" + to)) {
                    if (text.contains(route)) {
                        tokens.add(route);
                    }
                }
            }
        }
    }

    private static void addMatches(Set<String> tokens, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
    }
}
