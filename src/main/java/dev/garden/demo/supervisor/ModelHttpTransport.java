package dev.garden.demo.supervisor;

import io.agentscope.core.model.transport.*;

import okhttp3.OkHttpClient;

import reactor.core.publisher.Flux;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/** 禁止底层隐式重发，保留服务端 Retry-After，重试统一回到共享预算入口。 */
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
        return delegate.stream(request);
    }

    public void close() {
        delegate.close();
    }
}
