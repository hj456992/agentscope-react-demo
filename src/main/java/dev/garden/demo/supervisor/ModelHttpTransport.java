package dev.garden.demo.supervisor;

import io.agentscope.core.model.transport.*;

import okhttp3.OkHttpClient;

import reactor.core.publisher.Flux;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 禁止底层隐式重发，保留服务端 Retry-After，重试统一回到共享预算入口。
 *
 * <p>分两层看：
 *
 * <ul>
 *   <li>{@link #execute} 是一次阻塞的请求/响应（SDK 内部用它发非流式请求）。
 *       这里只在特定状态码上抛出 {@link ToolExecutor.TemporaryFailure}，把“可重试”的决定权
 *       交给上层的 Reactor 重试逻辑；OkHttp 自身的 {@code retryOnConnectionFailure} 已关闭，
 *       避免同一请求在代理层被悄悄重发、绕过额度计数。
 *   <li>{@link #stream} 直接把 SDK 的 SSE 流原样转出（{@code Flux<String>}）：这里不做缓冲、
 *       不 collect，保持真正的流式语义，谁消费谁决定要不要聚合。
 * </ul>
 */
final class ModelHttpTransport implements HttpTransport, AutoCloseable {
    private final HttpTransport delegate;

    ModelHttpTransport() {
        this(
                OkHttpTransport.builder()
                        .client(
                                new OkHttpClient.Builder()
                                        .retryOnConnectionFailure(false)
                                        .followRedirects(false)
                                        .followSslRedirects(false)
                                        .connectTimeout(5, TimeUnit.SECONDS)
                                        .callTimeout(20, TimeUnit.SECONDS)
                                        .readTimeout(20, TimeUnit.SECONDS)
                                        .writeTimeout(20, TimeUnit.SECONDS)
                                        .build())
                        .build());
    }

    ModelHttpTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    public HttpResponse execute(HttpRequest request) {
        HttpResponse response = delegate.execute(request);
        int status = response.getStatusCode();
        if (status == 429
                && java.util.Objects.toString(response.getBody(), "")
                        .contains("insufficient_quota"))
            throw new IllegalStateException("模型账户额度不足，本次不重试");
        if (status == 429 || status >= 500 && status < 600) {
            String header =
                    response.getHeaders().entrySet().stream()
                            .filter(e -> e.getKey().equalsIgnoreCase("Retry-After"))
                            .map(java.util.Map.Entry::getValue)
                            .findFirst()
                            .orElse(null);
            throw new ToolExecutor.TemporaryFailure("模型服务暂时不可用，HTTP " + status, retryAfter(header));
        }
        return response;
    }

    static Duration retryAfter(String value) {
        if (value == null) return null;
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
        } catch (RuntimeException ignored) {
            try {
                Duration delay =
                        Duration.between(
                                Instant.now(),
                                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                        .toInstant());
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    public Flux<String> stream(HttpRequest request) {
        // 流式读取（SSE）：SDK 会按事件逐段推送。这里只透传，不缓存整段内容，
        // 因此下游必须自己处理“增量拼接”。当前示例用非流式模型，方法保留给流式接入。
        return delegate.stream(request);
    }

    public void close() {
        delegate.close();
    }
}
