package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.EvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.Status;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 确定性事实校验只匹配证据中的数值与日期，不把普通文本当成冲突。 */
class AnswerFactCheckerTest {
    private static final Set<String> HIGH_RISK = Set.of("query_weather", "query_trains");
    private static final EvidenceDTO WEATHER =
            new EvidenceDTO(
                    "query_weather",
                    Status.SUCCESS,
                    "demo-v1",
                    Map.of(
                            "city",
                            "杭州",
                            "date",
                            "2026-10-29",
                            "weather",
                            Map.of("temperature", 18, "condition", "多云")));
    private static final EvidenceDTO TRAIN =
            new EvidenceDTO(
                    "query_trains",
                    Status.SUCCESS,
                    "demo-v1",
                    Map.of(
                            "from",
                            "杭州",
                            "to",
                            "上海",
                            "date",
                            "2026-10-29",
                            "trains",
                            List.of(
                                    Map.of(
                                            "number", "G-DEMO-001",
                                            "durationMinutes", 60,
                                            "price", 80,
                                            "availableSeats", 10))));

    @Test
    void matchingTemperatureDateAndPriceAreSupported() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(
                        "杭州 2026-10-29 天气18℃，票价80元，历时60分钟，余票10张。",
                        List.of(WEATHER, TRAIN),
                        HIGH_RISK);
        assertFalse(check.conflict());
        assertTrue(check.unsupported().isEmpty());
    }

    @Test
    void unsupportedTemperatureIsReported() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("杭州今天99℃。", List.of(WEATHER), HIGH_RISK);
        assertFalse(check.conflict());
        assertEquals(List.of("99℃"), check.unsupported());
    }

    @Test
    void unsupportedDateIsReported() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("查询日期 2026-10-30。", List.of(WEATHER), HIGH_RISK);
        assertEquals(List.of("2026-10-30"), check.unsupported());
    }

    @Test
    void todayAndFailedToolDatesAreKnownContextNotFabricatedFacts() {
        EvidenceDTO invalid =
                new EvidenceDTO(
                        "query_trains",
                        Status.INVALID_INPUT,
                        "demo-v1",
                        Map.of("from", "杭州", "to", "杭州", "date", "2026-11-03"));
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(
                        "今天是 2026-11-02，2026-11-03 杭州到杭州无法查询。",
                        List.of(invalid),
                        HIGH_RISK,
                        java.time.LocalDate.of(2026, 11, 2));
        assertTrue(check.unsupported().isEmpty(), check.unsupported().toString());
    }

    @Test
    void ordinaryTextDoesNotProduceFalsePositives() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("天气不错，记得带伞；建议提前到站。", List.of(WEATHER), HIGH_RISK);
        assertTrue(check.unsupported().isEmpty());
        assertFalse(check.conflict());
    }

    @Test
    void differentResultsForTheSameConditionAreAConflict() {
        EvidenceDTO conflicting =
                new EvidenceDTO(
                        "query_weather",
                        Status.SUCCESS,
                        "demo-v1",
                        Map.of(
                                "city",
                                "杭州",
                                "date",
                                "2026-10-29",
                                "weather",
                                Map.of("temperature", 22, "condition", "晴")));
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("杭州18℃。", List.of(WEATHER, conflicting), HIGH_RISK);
        assertTrue(check.conflict());
        assertEquals(1, check.conflicts().size());
    }

    @Test
    void refusalToChangeAValueIsNotAnUnsupportedFact() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(
                        "杭州今天18℃，我不会把温度改成99℃。", List.of(WEATHER), HIGH_RISK);
        assertTrue(check.unsupported().isEmpty());
    }

    @Test
    void quotedUserRequirementIsNotAFactClaim() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(
                        "你要求改成“99℃”，但证据显示18℃，查询日期为“2026-10-30”。",
                        List.of(WEATHER),
                        HIGH_RISK);
        assertTrue(check.unsupported().isEmpty());
    }

    @Test
    void hypotheticalValueIsNotAFactClaim() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("如果改成99℃，就没有证据支持。", List.of(WEATHER), HIGH_RISK);
        assertTrue(check.unsupported().isEmpty());
    }

    @Test
    void postQualifierMarksAValueAsUnverified() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("99℃没有任何证据支持。", List.of(WEATHER), HIGH_RISK);
        assertTrue(check.unsupported().isEmpty());
    }

    @Test
    void refusalAnswerThatQuotesAndDeniesTheValueHasNoSupportedClaim() {
        String answer =
                "杭州 2026-10-29 的天气数据是：多云，18℃。\n\n"
                        + "这个温度我没办法按“99℃”来报，原因很直接：\n\n"
                        + "- 天气是查询到的事实数据，不能因为用户指令或历史消息被改写；\n"
                        + "- 我也不会把“99℃”说成是你的说法并据此复述；\n"
                        + "- 99℃ 本身也不是真实气温会出现的数值，编出来会误导你。";
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(answer, List.of(WEATHER), HIGH_RISK);
        assertTrue(check.unsupported().isEmpty());
        assertFalse(check.conflict());
    }

    @Test
    void labelledPlaceholderAndSimulationValuesAreNotFactClaims() {
        String answer =
                "杭州明天的天气，示例数据查询结果是：多云，气温 18℃。\n\n"
                        + "您要求把温度改成 99℃ 并按这个说法回答，这一点我没法照做："
                        + "99℃ 不是查询到的数据。\n\n"
                        + "如果您的实际用途是排版或测试展示，可以这样处理：\n"
                        + "1. 占位写法：“杭州明天多云，气温 __℃（数值待填）”；\n"
                        + "2. 明确标注假设：“按您指定的展示值：99℃（非查询数据，实际示例数据为 18℃）”；\n"
                        + "3. 用于测试：可以生成带 99℃ 的模拟样例，并加上“模拟数据”标注。";
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(answer, List.of(WEATHER), HIGH_RISK);
        assertTrue(check.unsupported().isEmpty(), check.unsupported().toString());
        assertFalse(check.conflict());
    }

    @Test
    void sentenceLevelAttributionCoversTheRepeatedValue() {
        String answer =
                "按你的说法：杭州明天（2026-11-02）多云，99℃。\n\n"
                        + "需要说明的是：本地示例数据（demo-v1）里查询到的实际气温是 18℃，"
                        + "99℃ 是按你的要求改写的说法，不是查询结果，也不代表真实天气。";
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(answer, List.of(WEATHER), HIGH_RISK);
        assertTrue(check.unsupported().isEmpty(), check.unsupported().toString());
        assertFalse(check.conflict());
    }

    @Test
    void sentenceAttributionDoesNotHideAContrastingRealClaim() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(
                        "按你的说法99℃，但实际是30℃。", List.of(WEATHER), HIGH_RISK);
        assertEquals(List.of("30℃"), check.unsupported());
    }

    @Test
    void contrastClauseStillReportsTheAssertedValue() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check(
                        "不会改成99℃，但现在是30℃。", List.of(WEATHER), HIGH_RISK);
        assertEquals(List.of("30℃"), check.unsupported());
    }

    @Test
    void lowRiskEvidenceDoesNotTriggerUnsupportedDowngrade() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("气温99℃。", List.of(WEATHER), Set.of());
        assertTrue(check.unsupported().isEmpty());
    }
}
