package dev.garden.demo.supervisor.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 语义目标指纹的相似度、提醒升级、跨目标比较和窗口淘汰。 */
class SemanticLoopGuardTest {
    private static final String BASE = "请帮我查询杭州明天白天的天气";
    private static final String PARAPHRASE = "请帮我查询杭州明天白天的天气预报";

    @Test
    void paraphraseExceedsConfiguredSimilarity() {
        double similarity = SemanticLoopGuard.similarity(BASE, PARAPHRASE);
        assertTrue(similarity >= 0.85, "相似度应达到阈值，实际为 " + similarity);
        assertTrue(similarity <= 1.0);
    }

    @Test
    void repeatedSimilarGoalWarnsThenAsksForConfirmation() {
        SemanticLoopGuard.Result first = record(null, BASE, true);
        assertEquals(SemanticLoopGuard.Decision.PROCEED, first.decision());
        assertNotNull(first.value());

        SemanticLoopGuard.Result second = record(first.value(), PARAPHRASE, true);
        assertEquals(SemanticLoopGuard.Decision.WARN, second.decision());
        assertEquals(1, second.count());
        assertTrue(second.similarity() >= 0.85);

        SemanticLoopGuard.Result third = record(second.value(), BASE, true);
        assertEquals(SemanticLoopGuard.Decision.WARN, third.decision());
        assertEquals(2, third.count());

        SemanticLoopGuard.Result fourth = record(third.value(), PARAPHRASE, true);
        assertEquals(SemanticLoopGuard.Decision.ASK, fourth.decision());
        assertEquals(3, fourth.count());
    }

    @Test
    void sameScopeDifferentKeysAreOnlyComparedWhenCrossKeyIsEnabled() {
        SemanticLoopGuard.Result saved = record(null, BASE, true);

        SemanticLoopGuard.Result isolated =
                SemanticLoopGuard.record(
                        saved.value(), "agent", "/root/train", PARAPHRASE, 12, 0.85, 2, false);
        assertEquals(SemanticLoopGuard.Decision.PROCEED, isolated.decision());

        SemanticLoopGuard.Result shared =
                SemanticLoopGuard.record(
                        saved.value(), "agent", "/root/train", PARAPHRASE, 12, 0.85, 2, true);
        assertEquals(SemanticLoopGuard.Decision.WARN, shared.decision());
        assertEquals("/root/weather", shared.matchedKey());
    }

    @Test
    void windowEvictsOldEntriesBeforeTheyCanMatchAgain() {
        SemanticLoopGuard.Result first =
                SemanticLoopGuard.record(
                        null,
                        "tool",
                        "query_weather",
                        "weather forecast for hangzhou tomorrow",
                        2,
                        0.85,
                        2,
                        false);
        SemanticLoopGuard.Result second =
                SemanticLoopGuard.record(
                        first.value(),
                        "tool",
                        "query_weather",
                        "train schedule from shanghai to hangzhou",
                        2,
                        0.85,
                        2,
                        false);
        SemanticLoopGuard.Result third =
                SemanticLoopGuard.record(
                        second.value(),
                        "tool",
                        "query_weather",
                        "museum tickets reservation for the weekend",
                        2,
                        0.85,
                        2,
                        false);
        assertEquals(SemanticLoopGuard.Decision.PROCEED, third.decision());

        SemanticLoopGuard.Result again =
                SemanticLoopGuard.record(
                        third.value(),
                        "tool",
                        "query_weather",
                        "weather forecast for hangzhou tomorrow",
                        2,
                        0.85,
                        2,
                        false);
        assertEquals(SemanticLoopGuard.Decision.PROCEED, again.decision());
    }

    @Test
    void shortTextDoesNotWriteACheckpointEntry() {
        SemanticLoopGuard.Result result =
                SemanticLoopGuard.record(null, "agent", "/root/weather", "杭州天气", 12, 0.85, 2, true);
        assertEquals(SemanticLoopGuard.Decision.PROCEED, result.decision());
        assertNull(result.value());
    }

    @Test
    void malformedCheckpointIsRejectedInsteadOfSilentlyIgnored() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        SemanticLoopGuard.record(
                                "[{", "agent", "/root/weather", BASE, 12, 0.85, 2, true));
        assertThrows(
                IllegalStateException.class,
                () ->
                        SemanticLoopGuard.record(
                                "[{\"scope\":\"agent\"}]",
                                "agent",
                                "/root/weather",
                                BASE,
                                12,
                                0.85,
                                2,
                                true));
    }

    private static SemanticLoopGuard.Result record(
            String saved, String text, boolean crossKey) {
        return SemanticLoopGuard.record(
                saved, "agent", "/root/weather", text, 12, 0.85, 2, crossKey);
    }
}
