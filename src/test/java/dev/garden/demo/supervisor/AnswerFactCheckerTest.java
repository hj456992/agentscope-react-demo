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
    void lowRiskEvidenceDoesNotTriggerUnsupportedDowngrade() {
        AnswerFactChecker.Check check =
                AnswerFactChecker.check("气温99℃。", List.of(WEATHER), Set.of());
        assertTrue(check.unsupported().isEmpty());
    }
}
