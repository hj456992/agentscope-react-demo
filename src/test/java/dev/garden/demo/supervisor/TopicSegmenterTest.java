package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.service.TopicSegmenter;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 确定性话题切分：显式切换优先，承接词和同意图不切换，关闭时恒不切换。 */
class TopicSegmenterTest {
    private static final SupervisorPolicy.TopicPolicy POLICY =
            new SupervisorPolicy.TopicPolicy(true, 6, 0.6);
    private final TopicSegmenter segmenter = new TopicSegmenter();

    @Test
    void sameIntentFollowUpStaysInTheCurrentTopic() {
        TopicSegmenter.Segment segment =
                segmenter.segment(List.of("杭州明天天气"), "上海后天天气", POLICY);
        assertFalse(segment.switched());
        assertEquals(0, segment.startIndex());
        assertEquals("SAME_TOPIC", segment.reason());
    }

    @Test
    void intentChangeWithoutContinuationStartsANewTopic() {
        TopicSegmenter.Segment segment =
                segmenter.segment(List.of("杭州明天天气"), "查一下去上海的火车票", POLICY);
        assertTrue(segment.switched());
        assertEquals(1, segment.startIndex());
        assertEquals("INTENT_CHANGED", segment.reason());
    }

    @Test
    void explicitSwitchPhraseAlwaysStartsANewTopic() {
        TopicSegmenter.Segment segment =
                segmenter.segment(List.of("杭州明天天气"), "换个问题，帮我规划行程", POLICY);
        assertTrue(segment.switched());
        assertEquals(1, segment.startIndex());
        assertEquals("EXPLICIT_SWITCH", segment.reason());
    }

    @Test
    void continuationWordKeepsTheCurrentTopic() {
        TopicSegmenter.Segment segment =
                segmenter.segment(List.of("杭州明天天气"), "那后天呢", POLICY);
        assertFalse(segment.switched());
        assertEquals("CONTINUATION", segment.reason());
    }

    @Test
    void disabledPolicyNeverSwitches() {
        TopicSegmenter.Segment segment =
                segmenter.segment(
                        List.of("杭州明天天气"),
                        "换个问题，帮我规划行程",
                        SupervisorPolicy.TopicPolicy.disabled());
        assertFalse(segment.switched());
        assertEquals(0, segment.startIndex());
        assertEquals("DISABLED", segment.reason());
    }

    @Test
    void topicKeyIsStableForTheSameTopicAndDiffersAcrossTopics() {
        TopicSegmenter.Segment first =
                segmenter.segment(List.of("杭州明天天气"), "杭州后天天气", POLICY);
        TopicSegmenter.Segment again =
                segmenter.segment(List.of("杭州明天天气"), "杭州后天天气", POLICY);
        TopicSegmenter.Segment switched =
                segmenter.segment(List.of("杭州明天天气"), "查火车票", POLICY);
        assertEquals(first.topicKey(), again.topicKey());
        assertNotEquals(first.topicKey(), switched.topicKey());
    }

    @Test
    void recentTurnWindowIgnoresOlderTopics() {
        TopicSegmenter.Segment segment =
                segmenter.segment(
                        List.of("杭州明天天气", "嗯", "好的"),
                        "查去上海的车票",
                        new SupervisorPolicy.TopicPolicy(true, 2, 0.6));
        assertFalse(segment.switched());
        assertEquals("UNCERTAIN", segment.reason());
    }
}
