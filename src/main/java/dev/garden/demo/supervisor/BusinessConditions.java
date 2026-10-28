package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;

/** Demo 的可核实语义边界：条件必须能回指用户原文，不能只与模型自己比较。 */
final class BusinessConditions {
    static final List<String> CITIES = List.of("杭州", "上海", "北京", "成都");

    static String validate(ChatInput request, Decision decision, LocalDate today) {
        String problem = clarification(decision);
        if (!problem.isEmpty() || decision.intent() == Intent.UNKNOWN) return problem;
        if (decision.days() > 30) return "示例最多规划30天，请确认行程天数。";
        String original = request.question();
        // 否定、二选一和复杂更正需要用户明确，演示规则不声称能理解任意自然语言。
        if (original.matches("(?s).*(不要|不是|除了|或者|还是).*")) return "存在否定或多个候选条件，请明确本次城市、日期和路线。";
        String statements =
                original
                        + "\n"
                        + request.history().stream()
                                .map(Turn::question)
                                .reduce("", (a, b) -> a + "\n" + b);
        if (decision.intent() != Intent.WEATHER
                && statements.matches("(?s).*(只要|只坐|必须|预算|最晚|最早|一等座|商务座|硬卧|软卧).*"))
            return "检测到座席、预算或时间限制，当前查询条件尚不能完整表达，请明确需要优先满足的限制。";
        Matcher days = Pattern.compile("(\\d+)天").matcher(original);
        int statedDays =
                days.find() ? Integer.parseInt(days.group(1)) : original.contains("一天") ? 1 : 0;
        if (decision.intent() == Intent.ITINERARY
                && statedDays > 0
                && statedDays != decision.days()) return "识别的行程天数与用户原文不一致，请确认天数。";
        List<String> fields =
                decision.intent() == Intent.WEATHER
                        ? List.of(decision.city())
                        : List.of(decision.from(), decision.to());
        for (String city : fields) {
            if (!CITIES.contains(city)) return "示例只识别杭州、上海、北京、成都，请确认城市。";
            if (!statements.contains(city)) return "城市缺少用户原文依据，请明确查询条件。";
        }
        List<String> currentCities = CITIES.stream().filter(original::contains).toList();
        if (decision.intent() == Intent.WEATHER
                && currentCities.size() == 1
                && !currentCities.get(0).equals(decision.city())) return "模型识别的城市与本次要求不一致，请确认城市。";
        Matcher route = Pattern.compile("(杭州|上海|北京|成都)(?:到|去)(杭州|上海|北京|成都)").matcher(original);
        if (decision.intent() != Intent.WEATHER && route.find()
                && (!route.group(1).equals(decision.from())
                        || !route.group(2).equals(decision.to()))) return "识别的出发地或目的地与用户要求不一致。";
        String date = explicitDate(original, today);
        if (date == null) {
            for (int i = request.history().size() - 1; i >= 0 && date == null; i--)
                date = explicitDate(request.history().get(i).question(), today);
        }
        if (date == null || !date.equals(decision.date())) return "日期缺少明确依据或与本次要求不一致，请提供完整日期。";
        return "";
    }

    private static String explicitDate(String text, LocalDate today) {
        Matcher m = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text);
        if (m.find()) return m.group();
        if (text.contains("后天")) return today.plusDays(2).toString();
        if (text.contains("明天")) return today.plusDays(1).toString();
        if (text.contains("今天")) return today.toString();
        return null;
    }

    static String clarification(Decision d) {
        if (d.intent() == Intent.UNKNOWN) return "";
        if (d.intent() == Intent.WEATHER && blank(d.city())) return "请告诉我要查询天气的城市。";
        if (d.intent() != Intent.WEATHER) {
            if (blank(d.from())) return "请告诉我出发城市。";
            if (blank(d.to())) return "请告诉我目的地。";
            if (d.from().equals(d.to())) return "出发地和目的地相同，请确认查询条件。";
        }
        if (blank(d.date())) return "请告诉我查询或出发日期。";
        try {
            LocalDate.parse(d.date());
        } catch (DateTimeParseException ex) {
            return "日期无法识别，请提供完整年月日。";
        }
        if (d.intent() == Intent.ITINERARY && d.days() < 1) return "请告诉我计划游玩几天。";
        return "";
    }
}
