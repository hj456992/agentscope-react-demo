package dev.garden.demo.supervisor.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.FailureClass;
import dev.garden.demo.supervisor.ToolExecutor;
import dev.garden.demo.supervisor.TravelTools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过真实 HTTP 调用本地独立下游的只读数据源。
 *
 * <p>下游地址、超时和可选的固定延迟来自实例配置；失败时抛出带状态分类的
 * {@link ToolExecutor.TemporaryFailure}，由既有工具重试与失败分类链路处理。
 * 本类不记录 URL 查询串、响应正文或任何凭据。
 */
public final class HttpTravelDataSource implements TravelTools.DataSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(HttpTravelDataSource.class);

    private final String baseUrl;
    private final Duration timeout;
    private final long delayMs;
    private final HttpClient client;

    /**
     * 唯一构造器：地址和超时由配置校验后传入，客户端按连接超时创建一次。
     */
    public HttpTravelDataSource(String baseUrl, Duration timeout, long delayMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.delayMs = Math.max(0, delayMs);
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public String version() {
        return "downstream-v1";
    }

    @Override
    public Map<String, Object> weather(String city, String date) {
        JsonNode node =
                get(
                        "/weather?city="
                                + encode(city)
                                + "&date="
                                + encode(date));
        Map<String, Object> result = new LinkedHashMap<>();
        if (node.has("temperature")) {
            result.put("temperature", node.path("temperature").asInt());
        }
        if (node.has("condition")) {
            result.put("condition", node.path("condition").asText());
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> trains(String from, String to, String date) {
        JsonNode node =
                get(
                        "/trains?from="
                                + encode(from)
                                + "&to="
                                + encode(to)
                                + "&date="
                                + encode(date));
        List<Map<String, Object>> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            Map<String, Object> row = new LinkedHashMap<>();
            item.fields()
                    .forEachRemaining(
                            entry -> {
                                JsonNode value = entry.getValue();
                                if (value.isInt() || value.isLong()) {
                                    row.put(entry.getKey(), value.asLong());
                                } else {
                                    row.put(entry.getKey(), value.asText());
                                }
                            });
            result.add(row);
        }
        return result;
    }

    private JsonNode get(String path) {
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return JSON.readTree(response.body());
            }
            Duration retryAfter = retryAfter(response);
            FailureClass failureClass = FailureClass.fromStatus(status);
            throw new ToolExecutor.TemporaryFailure(
                    "下游返回状态 " + status,
                    retryAfter,
                    failureClass,
                    "downstream_http_" + status);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolExecutor.TemporaryFailure(
                    "下游调用被中断", null, FailureClass.CANCELLED, "downstream_interrupted");
        } catch (java.net.http.HttpTimeoutException error) {
            throw new ToolExecutor.TemporaryFailure(
                    "下游响应超时", null, FailureClass.TIMEOUT, "downstream_timeout");
        } catch (java.net.ConnectException error) {
            throw new ToolExecutor.TemporaryFailure(
                    "下游连接失败", null, FailureClass.RETRYABLE, "downstream_connect");
        } catch (IOException error) {
            LOG.debug("下游读取失败：{}", error.toString());
            throw new ToolExecutor.TemporaryFailure(
                    "下游读取失败", null, FailureClass.UNKNOWN, "downstream_io");
        } catch (RuntimeException error) {
            throw error;
        }
    }

    private static Duration retryAfter(HttpResponse<String> response) {
        return response.headers()
                .firstValue("Retry-After")
                .map(value -> parseRetryAfter(value))
                .orElse(null);
    }

    private static Duration parseRetryAfter(String value) {
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
