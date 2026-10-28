package dev.garden.demo.supervisor;

import io.agentscope.core.util.JsonUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 本示例使用的数据。先看 Request、Result，再看每次工具调用的条件和实际证据。 */
public final class SupervisorContracts {
    private SupervisorContracts() {}

    public enum Intent {
        WEATHER,
        TRAIN,
        ITINERARY,
        UNKNOWN
    }

    public enum Status {
        SUCCESS,
        NEEDS_INPUT,
        UNKNOWN_INTENT,
        NO_DATA,
        PARTIAL,
        INVALID_INPUT,
        FAILED,
        TIMEOUT,
        RUNNING,
        CANCELLED,
        REPLACED,
        BUDGET_EXHAUSTED,
        CONTEXT_LIMIT,
        RESULT_UNKNOWN
    }

    public record Turn(String question, String answer) {}

    /** HTTP 历史由服务端补充；调用方历史只作为待核实资料。 */
    public record Request(String sessionId, String question, List<Turn> history, String requestId) {
        public Request(String sessionId, String question, List<Turn> history) {
            this(sessionId, question, history, null);
        }

        public Request {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    /** 不适用的字符串字段返回空串；days 是行程天数。 */
    public record Decision(
            Intent intent, String city, String from, String to, String date, int days) {}

    /** 这是实际查询记录，不依赖模型声称“已查询”。 */
    public record Evidence(String tool, Status status, String source, Map<String, Object> data) {
        public Evidence {
            data = Map.copyOf(data);
        }
    }

    public record Result(
            Status status,
            String originalQuery,
            String rewrittenQuery,
            Intent intent,
            String answer,
            List<String> agents,
            List<Evidence> evidence,
            List<String> events) {}

    /** inputBytes 是单次模型输入的字节预算；真实模型需要比离线脚本更大的窗口。 */
    public record Limits(Duration timeout, int modelCalls, int outputTokens, int inputBytes) {
        static final int DEFAULT_INPUT_BYTES = 48000;

        public Limits(Duration timeout, int modelCalls, int outputTokens) {
            this(timeout, modelCalls, outputTokens, DEFAULT_INPUT_BYTES);
        }

        public Limits {
            if (timeout == null
                    || timeout.isNegative()
                    || timeout.isZero()
                    || modelCalls < 1
                    || outputTokens < 1
                    || inputBytes < 1024) {
                throw new IllegalArgumentException("运行预算必须为正数");
            }
        }

        public static Limits defaults() {
            return new Limits(Duration.ofSeconds(60), 16, 1024, DEFAULT_INPUT_BYTES);
        }
    }

    static String json(Object value) {
        return JsonUtils.getJsonCodec().toJson(value);
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
