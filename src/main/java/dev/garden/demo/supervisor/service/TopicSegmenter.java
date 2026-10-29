package dev.garden.demo.supervisor.service;

import dev.garden.demo.supervisor.SupervisorPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 确定性话题切分：只根据用户问题和策略判断是否进入新话题。
 *
 * <p>显式切换词优先；没有承接词时，天气、车票、行程三类意图发生变化即视为切换。
 * 返回值只影响服务端历史装配与摘要范围，不删除会话行，也不改变显式 {@code sessionId} 边界。
 * 话题键由意图和当前话题首个问题生成，同一输入始终得到同一结果。
 */
public final class TopicSegmenter {
    /** 话题切分结果；{@code startIndex} 指向当前话题在历史问题列表中的起点。 */
    public record Segment(
            boolean switched, int startIndex, String topicKey, double confidence, String reason) {
        public Segment {
            if (startIndex < 0) {
                throw new IllegalArgumentException("话题起点不能为负");
            }
            Objects.requireNonNull(topicKey, "topicKey");
            Objects.requireNonNull(reason, "reason");
            if (confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("话题置信度必须在 0 到 1 之间");
            }
        }
    }

    private static final List<String> EXPLICIT_SWITCHES =
            List.of("换个问题", "另外问", "新问题", "重新开始", "不聊这个", "说点别的");
    private static final List<String> CONTINUATIONS =
            List.of("那", "改", "再", "继续", "还是", "然后", "呢");
    private static final List<String> WEATHER = List.of("天气", "气温", "温度", "下雨", "降雨", "晴天", "多云", "阴天");
    private static final List<String> TRAIN = List.of("车票", "火车", "高铁", "动车", "车次", "余票", "改签");
    private static final List<String> ITINERARY =
            List.of("行程", "规划", "安排", "路线", "攻略", "景点", "住宿", "游玩", "旅游");

    /**
     * 判断当前问题是否开启新话题。
     *
     * <p>关闭策略、无历史或无法确定意图时返回未切换；只有显式切换词或确定性的意图变化
     * 且置信度达到 {@code minConfidence} 才返回切换。
     */
    public Segment segment(
            List<String> questions, String currentQuestion, SupervisorPolicy.TopicPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        List<String> history = questions == null ? List.of() : List.copyOf(questions);
        String current = currentQuestion == null ? "" : currentQuestion;
        if (!policy.enabled()) {
            return new Segment(false, 0, "", 1.0, "DISABLED");
        }
        if (history.isEmpty()) {
            return unchanged(history, current, 1.0, "FIRST_TOPIC");
        }
        if (containsAny(current, EXPLICIT_SWITCHES)) {
            return switched(history, history.size(), current, 1.0, "EXPLICIT_SWITCH");
        }
        String currentIntent = intent(current);
        String previousIntent = previousIntent(history, policy.recentTurns());
        if (containsAny(current, CONTINUATIONS)) {
            return unchanged(history, current, 0.9, "CONTINUATION");
        }
        if (currentIntent.equals("UNKNOWN") || previousIntent.equals("UNKNOWN")) {
            return unchanged(history, current, 0.3, "UNCERTAIN");
        }
        if (currentIntent.equals(previousIntent)) {
            return unchanged(history, current, 0.9, "SAME_TOPIC");
        }
        Segment change =
                switched(history, startIndex(history, currentIntent), current, 0.8, "INTENT_CHANGED");
        if (change.confidence() < policy.minConfidence()) {
            return unchanged(history, current, change.confidence(), "LOW_CONFIDENCE");
        }
        return change;
    }

    /** 未切换时话题从历史第一条开始，键保持稳定。 */
    private static Segment unchanged(
            List<String> history, String current, double confidence, String reason) {
        String first = history.isEmpty() ? current : history.get(0);
        return new Segment(false, 0, key(first), confidence, reason);
    }

    /** 切换时话题从 {@code startIndex} 开始；起点等于历史长度表示当前问题单独成为新话题。 */
    private static Segment switched(
            List<String> history, int startIndex, String current, double confidence, String reason) {
        String first = startIndex < history.size() ? history.get(startIndex) : current;
        return new Segment(true, startIndex, key(first), confidence, reason);
    }

    /** 在考察窗口内从新到旧找到最近一个可识别意图；全部未知时返回 {@code UNKNOWN}。 */
    private static String previousIntent(List<String> history, int recentTurns) {
        int start = Math.max(0, history.size() - recentTurns);
        for (int index = history.size() - 1; index >= start; index--) {
            String value = intent(history.get(index));
            if (!value.equals("UNKNOWN")) {
                return value;
            }
        }
        return "UNKNOWN";
    }

    /** 从末尾向前连续统计同意图问题，返回当前意图连续段的起点。 */
    private static int startIndex(List<String> history, String currentIntent) {
        int start = history.size();
        for (int index = history.size() - 1; index >= 0; index--) {
            if (!intent(history.get(index)).equals(currentIntent)) {
                break;
            }
            start = index;
        }
        return start;
    }

    /** 话题键：意图 + 首个问题的规范化摘要，使用 SHA-256 前 16 个十六进制字符。 */
    private static String key(String firstQuestion) {
        String summary =
                normalize(firstQuestion)
                        .codePoints()
                        .limit(32)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();
        String intent = intent(firstQuestion);
        return intent + ":" + fingerprint(intent + "|" + summary);
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "")
                .replaceAll("[，。！？、；：,.!?;:（）()\\[\\]【】]", "");
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String intent(String text) {
        if (containsAny(text, WEATHER)) {
            return "WEATHER";
        }
        if (containsAny(text, TRAIN)) {
            return "TRAIN";
        }
        if (containsAny(text, ITINERARY)) {
            return "ITINERARY";
        }
        return "UNKNOWN";
    }
}
