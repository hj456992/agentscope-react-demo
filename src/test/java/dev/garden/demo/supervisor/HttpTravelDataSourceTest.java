package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.garden.demo.supervisor.support.HttpTravelDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 本地独立下游的真实 HTTP 失败映射；不依赖真实供应商。 */
class HttpTravelDataSourceTest {
    private HttpServer server;
    private HttpTravelDataSource source;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/weather", this::weather);
        server.start();
        source =
                new HttpTravelDataSource(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        Duration.ofMillis(300),
                        0);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void weather(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String city = query.getOrDefault("city", "");
        try {
            if ("RATE".equals(city)) {
                exchange.getResponseHeaders().add("Retry-After", "3");
                reply(exchange, 429, "{}");
                return;
            }
            if ("SERVER".equals(city)) {
                reply(exchange, 500, "{}");
                return;
            }
            if ("BAD".equals(city)) {
                reply(exchange, 400, "{}");
                return;
            }
            if ("MISSING".equals(city)) {
                reply(exchange, 404, "{}");
                return;
            }
            if ("TIMEOUT".equals(city)) {
                Thread.sleep(800);
                reply(exchange, 200, "{}");
                return;
            }
            if ("RESET".equals(city)) {
                exchange.close();
                return;
            }
            reply(exchange, 200, "{\"temperature\":18,\"condition\":\"晴\"}");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int index = pair.indexOf('=');
            if (index < 0) {
                continue;
            }
            values.put(
                    URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private ToolExecutor.TemporaryFailure failure(String city) {
        return assertThrows(
                ToolExecutor.TemporaryFailure.class, () -> source.weather(city, "2026-11-02"));
    }

    @Test
    void successResponseIsParsedIntoTheExistingWeatherShape() {
        Map<String, Object> result = source.weather("杭州", "2026-11-02");
        assertEquals(18, result.get("temperature"));
        assertEquals("晴", result.get("condition"));
        assertEquals("downstream-v1", source.version());
    }

    @Test
    void rateLimitKeepsRetryAfterAndProviderCode() {
        ToolExecutor.TemporaryFailure error = failure("RATE");
        assertEquals(FailureClass.RATE_LIMITED, error.failureClass);
        assertEquals(Duration.ofSeconds(3), error.retryAfter);
        assertEquals("downstream_http_429", error.providerCode);
    }

    @Test
    void serverErrorIsRetryableAndClientErrorsAreNot() {
        assertEquals(FailureClass.RETRYABLE, failure("SERVER").failureClass);
        assertEquals("downstream_http_500", failure("SERVER").providerCode);
        assertEquals(FailureClass.INPUT_INVALID, failure("BAD").failureClass);
        assertEquals(FailureClass.NOT_FOUND, failure("MISSING").failureClass);
        assertNull(failure("BAD").retryAfter);
    }

    @Test
    void responseTimeoutAndConnectionFailureUseDistinctCodes() {
        ToolExecutor.TemporaryFailure timeout = failure("TIMEOUT");
        assertEquals(FailureClass.TIMEOUT, timeout.failureClass);
        assertEquals("downstream_timeout", timeout.providerCode);

        server.stop(0);
        server = null;
        ToolExecutor.TemporaryFailure connect = failure("杭州");
        assertEquals(FailureClass.RETRYABLE, connect.failureClass);
        assertEquals("downstream_connect", connect.providerCode);
    }
}
