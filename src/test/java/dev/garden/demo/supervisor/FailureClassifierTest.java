package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.exception.OpenAIException;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

class FailureClassifierTest {
    @Test
    void rateLimitAndQuotaHaveDifferentRetrySemantics() {
        OpenAIException limited =
                OpenAIException.create(429, "limited", "rate_limit_exceeded", "{}");
        OpenAIException quota =
                OpenAIException.create(429, "quota", "insufficient_quota", "{}");
        assertEquals(FailureClass.RATE_LIMITED, FailureClassifier.classify(limited));
        assertTrue(FailureClassifier.retryable(limited));
        assertEquals(FailureClass.QUOTA_EXHAUSTED, FailureClassifier.classify(quota));
        assertFalse(FailureClassifier.retryable(quota));
    }

    @Test
    void statusCodesMapToSharedCategories() {
        assertEquals(
                FailureClass.RETRYABLE,
                FailureClassifier.classify(OpenAIException.create(503, "x", "", "")));
        assertEquals(
                FailureClass.TIMEOUT,
                FailureClassifier.classify(OpenAIException.create(408, "x", "", "")));
        assertEquals(
                FailureClass.INPUT_INVALID,
                FailureClassifier.classify(OpenAIException.create(400, "x", "", "")));
        assertEquals(
                FailureClass.AUTH,
                FailureClassifier.classify(OpenAIException.create(401, "x", "", "")));
        assertEquals(
                FailureClass.NOT_FOUND,
                FailureClassifier.classify(OpenAIException.create(404, "x", "", "")));
    }

    @Test
    void nestedConnectionFailureRemainsRetryable() {
        Throwable error =
                new OpenAIException(
                        "connection",
                        new HttpTransportException("connect", new ConnectException("temporary")));
        assertEquals(FailureClass.RETRYABLE, FailureClassifier.classify(error));
        assertTrue(FailureClassifier.retryable(error));
    }

    @Test
    void runControlFailuresNeverRetry() {
        assertEquals(
                FailureClass.CANCELLED,
                FailureClassifier.classify(new RunControl.Cancelled("cancelled")));
        assertEquals(
                FailureClass.BUDGET,
                FailureClassifier.classify(new RunControl.BudgetExceeded("budget")));
        assertEquals(
                FailureClass.CONTEXT_LIMIT,
                FailureClassifier.classify(new RunControl.ContextLimit("context")));
        assertEquals(
                FailureClass.TIMEOUT,
                FailureClassifier.classify(new RunControl.DeadlineExceeded()));
        assertFalse(FailureClassifier.retryable(new RunControl.DeadlineExceeded()));
    }

    @Test
    void providerCodeIsParsedFromErrorObject() {
        assertEquals(
                "insufficient_quota",
                FailureClassifier.providerCode(
                        "{\"error\":{\"code\":\"insufficient_quota\",\"type\":\"quota\"}}"));
        assertEquals(
                "rate_limit",
                FailureClassifier.providerCode("{\"error\":{\"type\":\"rate_limit\"}}"));
        assertEquals("", FailureClassifier.providerCode("not json"));
        assertTrue(FailureClassifier.quotaCode("Insufficient_Quota"));
        assertFalse(FailureClassifier.quotaCode("rate_limit_exceeded"));
    }

    @Test
    void retryAfterHeaderWinsOverExponentialBackoff() {
        ToolExecutor.TemporaryFailure failure =
                new ToolExecutor.TemporaryFailure(
                        "limited",
                        Duration.ofSeconds(2),
                        FailureClass.RATE_LIMITED,
                        "rate_limit");
        assertEquals(Duration.ofSeconds(2), FailureClassifier.retryDelay(failure, 1, 2000));
        assertEquals(Duration.ofSeconds(2), ModelHttpTransport.retryAfter("2"));
    }

    @Test
    void exponentialBackoffIsCapped() {
        Duration delay = FailureClassifier.retryDelay(new IllegalStateException("x"), 10, 100);
        assertTrue(delay.compareTo(Duration.ofMillis(100)) >= 0);
        assertTrue(delay.compareTo(Duration.ofMillis(150)) < 0);
    }

    @Test
    void rfc1123RetryAfterIsParsed() {
        String header =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        Instant.now().plusSeconds(5).atZone(ZoneOffset.UTC));
        Duration delay = ModelHttpTransport.retryAfter(header);
        assertNotNull(delay);
        assertTrue(delay.compareTo(Duration.ofSeconds(3)) >= 0);
    }
}
