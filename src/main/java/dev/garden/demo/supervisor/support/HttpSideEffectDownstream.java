package dev.garden.demo.supervisor.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.pojo.dto.SideEffectOutcomeDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectOutcomeStatus;
import dev.garden.demo.supervisor.service.SideEffectLedger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写入类工具的本地 HTTP 下游适配器。
 *
 * <p>下游是独立进程，必须在服务端持久幂等键下保存订单，并提供按操作号查询。
 * 连接失败、超时和 5xx 一律返回 UNKNOWN，由账本只查询、不重放；4xx 视为权威失败。
 * 响应正文只截取前 2000 个字符进入账本，不记录请求头或任何凭据。
 */
public final class HttpSideEffectDownstream implements SideEffectLedger.Downstream {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE = 2_000;

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient client;

    /** 唯一构造器：地址和超时由配置绑定后传入。 */
    public HttpSideEffectDownstream(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public boolean supportsIdempotency() {
        return true;
    }

    @Override
    public SideEffectOutcomeDTO submit(String operationId, Map<String, Object> parameters)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("parameters", parameters);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/orders"))
                        .timeout(timeout)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Accept", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
        return send(request);
    }

    @Override
    public SideEffectOutcomeDTO lookup(String operationId) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        baseUrl
                                                + "/orders/"
                                                + java.net.URLEncoder.encode(
                                                        operationId, StandardCharsets.UTF_8)))
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        return send(request);
    }

    private SideEffectOutcomeDTO send(HttpRequest request) throws Exception {
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = truncate(response.body());
            if (status >= 200 && status < 300) {
                return new SideEffectOutcomeDTO(SideEffectOutcomeStatus.SUCCESS, body);
            }
            if (status == 404) {
                return new SideEffectOutcomeDTO(
                        SideEffectOutcomeStatus.CONFIRMED_NOT_EXECUTED, body);
            }
            if (status >= 400 && status < 500) {
                return new SideEffectOutcomeDTO(SideEffectOutcomeStatus.FAILED, body);
            }
            return new SideEffectOutcomeDTO(SideEffectOutcomeStatus.UNKNOWN, body);
        } catch (IOException error) {
            // 连接中断、超时或响应读取失败都无法判断下游是否已落盘。
            return new SideEffectOutcomeDTO(SideEffectOutcomeStatus.UNKNOWN, null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    /** 只保留响应正文，不返回请求地址或请求头。 */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_RESPONSE ? value : value.substring(0, MAX_RESPONSE) + "…";
    }

    /** 便于页面核对的订单摘要；不包含认证信息。 */
    public static Map<String, Object> summary(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationId", node.path("operationId").asText(""));
        result.put("status", node.path("status").asText(""));
        result.put("orderNumber", node.path("orderNumber").asText(""));
        return result;
    }
}
