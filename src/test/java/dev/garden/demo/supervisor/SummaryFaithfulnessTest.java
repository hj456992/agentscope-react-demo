package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 确定性摘要忠实度评测的 token 提取、缺失报告与压缩率。 */
class SummaryFaithfulnessTest {
    private static final List<String> REQUIREMENTS =
            List.of("2026-10-30 从北京到上海 二等座 553元 历时270分钟 到达18℃");

    @Test
    void fullSummaryRetainsEveryCriticalToken() {
        SummaryFaithfulness.Report report =
                SummaryFaithfulness.evaluate(
                        "用户要求 2026-10-30 从北京到上海，二等座，553元，历时270分钟，到达18℃。",
                        REQUIREMENTS,
                        1000,
                        250,
                        1.0);
        assertTrue(report.passed());
        assertEquals(1.0, report.retention(), 0.0001);
        assertTrue(report.missing().isEmpty());
        assertEquals(0.75, report.compressionRatio(), 0.0001);
    }

    @Test
    void missingDateSeatAndMoneyAreReported() {
        SummaryFaithfulness.Report report =
                SummaryFaithfulness.evaluate(
                        "用户要查询从北京到上海的车票，耗时270分钟，到达18℃。",
                        REQUIREMENTS,
                        1000,
                        500,
                        1.0);
        assertFalse(report.passed());
        assertTrue(report.missing().contains("2026-10-30"));
        assertTrue(report.missing().contains("二等座"));
        assertTrue(report.missing().contains("553元"));
        assertFalse(report.missing().contains("北京到上海"));
        assertEquals(5.0 / 8.0, report.retention(), 0.0001);
    }

    @Test
    void emptyRequirementsPassWithoutTokens() {
        SummaryFaithfulness.Report report =
                SummaryFaithfulness.evaluate("摘要", List.of(), 100, 50, 1.0);
        assertTrue(report.passed());
        assertEquals(1.0, report.retention(), 0.0001);
        assertTrue(report.missing().isEmpty());
        assertEquals(0.5, report.compressionRatio(), 0.0001);
    }

    @Test
    void tokensAreDeduplicatedInStableOrder() {
        List<String> tokens =
                SummaryFaithfulness.criticalTokens(
                        List.of("北京到上海，北京出发", "上海天气18℃，明天18℃"));
        assertEquals(List.of("北京", "上海", "北京到上海", "18℃"), tokens);
    }

    @Test
    void compressionRatioIsClampedAndHandlesEmptyOriginal() {
        assertEquals(
                0.0,
                SummaryFaithfulness.evaluate("摘要", List.of(), 100, 200, 1.0)
                        .compressionRatio(),
                0.0001);
        assertEquals(
                0.0,
                SummaryFaithfulness.evaluate("摘要", List.of(), 0, 0, 1.0)
                        .compressionRatio(),
                0.0001);
    }

    @Test
    void thresholdControlsPassing() {
        SummaryFaithfulness.Report report =
                SummaryFaithfulness.evaluate("北京", REQUIREMENTS, 100, 10, 0.1);
        assertTrue(report.passed());
        assertTrue(report.retention() >= 0.1);
    }
}
