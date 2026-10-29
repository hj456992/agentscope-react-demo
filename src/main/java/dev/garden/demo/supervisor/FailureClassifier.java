package dev.garden.demo.supervisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.exception.OpenAIException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * 供应商异常到失败类别的唯一映射。
 *
 * <p>状态码优先、供应商错误码补充：429 默认限流，只有明确的配额错误才视为不可重试。
 * 响应体只解析 {@code error.code} / {@code error.type}，不记录原文。
 */
final class FailureClassifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> QUOTA_CODES =
            Set.of("insufficient_quota", "quota_exceeded", "billing_hard_limit_reached");

    private FailureClassifier() {}

    static FailureClass classify(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof RunControl.Cancelled) {
                return FailureClass.CANCELLED;
            }
            if (error instanceof RunControl.BudgetExceeded) {
                return FailureClass.BUDGET;
            }
            if (error instanceof RunControl.ContextLimit) {
                return FailureClass.CONTEXT_LIMIT;
            }
            if (error instanceof RunControl.DeadlineExceeded) {
                return FailureClass.TIMEOUT;
            }
            if (error instanceof ToolExecutor.TemporaryFailure temporary) {
                return temporary.failureClass;
            }
            if (error instanceof OpenAIException provider) {
                if (quotaCode(provider.getErrorCode())) {
                    return FailureClass.QUOTA_EXHAUSTED;
                }
                if (provider.getStatusCode() != null) {
                    return fromStatus(provider.getStatusCode());
                }
                continue;
            }
            if (error instanceof ModelHttpException http && http.getStatusCode() != null) {
                return fromStatus(http.getStatusCode());
            }
            if (error instanceof HttpTransportException transport) {
                if (transport.getStatusCode() != null) {
                    return fromStatus(transport.getStatusCode());
                }
                // 旧实现把没有状态码的传输异常视为暂时连接失败。
                return FailureClass.RETRYABLE;
            }
            if (error instanceof SocketTimeoutException || error instanceof TimeoutException) {
                return FailureClass.TIMEOUT;
            }
            if (error instanceof ConnectException) {
                return FailureClass.RETRYABLE;
            }
            if (error instanceof SecurityException) {
                return FailureClass.AUTH;
            }
            if (error instanceof IllegalArgumentException) {
                return FailureClass.INPUT_INVALID;
            }
        }
        return FailureClass.UNKNOWN;
    }

    static FailureClass fromStatus(int status) {
        if (status == 408) {
            return FailureClass.TIMEOUT;
        }
        if (status == 429) {
            return FailureClass.RATE_LIMITED;
        }
        if (status == 401 || status == 403) {
            return FailureClass.AUTH;
        }
        if (status == 400 || status == 422) {
            return FailureClass.INPUT_INVALID;
        }
        if (status == 404) {
            return FailureClass.NOT_FOUND;
        }
        if (status >= 500 && status < 600) {
            return FailureClass.RETRYABLE;
        }
        return FailureClass.UNKNOWN;
    }

    static boolean retryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RunControl.Cancelled
                    || cause instanceof RunControl.BudgetExceeded
                    || cause instanceof RunControl.ContextLimit
                    || cause instanceof RunControl.DeadlineExceeded) {
                return false;
            }
        }
        return classify(error).retryable();
    }

    static Duration retryDelay(Throwable error, int attempt, long capMs) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ToolExecutor.TemporaryFailure failure
                    && failure.retryAfter != null) {
                return failure.retryAfter;
            }
        }
        int exponent = Math.min(20, Math.max(0, attempt - 1));
        long base = Math.min(capMs, 200L << exponent);
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextInt(50));
    }

    static String providerCode(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof OpenAIException provider) {
                String code = provider.getErrorCode();
                if (code != null && !code.isBlank()) {
                    return code;
                }
                String parsed = providerCode(provider.getResponseBody());
                if (!parsed.isBlank()) {
                    return parsed;
                }
            }
            if (error instanceof HttpTransportException transport) {
                String parsed = providerCode(transport.getResponseBody());
                if (!parsed.isBlank()) {
                    return parsed;
                }
            }
            if (error instanceof ToolExecutor.TemporaryFailure temporary) {
                if (temporary.providerCode != null && !temporary.providerCode.isBlank()) {
                    return temporary.providerCode;
                }
            }
        }
        return "";
    }

    /** 从 JSON 响应体读取 error.code / error.type；无法解析时返回空串，不保留原文。 */
    static String providerCode(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode error = root.path("error");
            String code = error.path("code").asText("");
            if (!code.isBlank()) {
                return code;
            }
            code = error.path("type").asText("");
            if (!code.isBlank()) {
                return code;
            }
            return root.path("code").asText("");
        } catch (RuntimeException | java.io.IOException ignored) {
            return "";
        }
    }

    static boolean quotaCode(String code) {
        return code != null && QUOTA_CODES.contains(code.toLowerCase(Locale.ROOT));
    }
}
