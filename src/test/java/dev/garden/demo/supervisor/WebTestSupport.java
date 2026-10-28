package dev.garden.demo.supervisor;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.GenericReactiveWebApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/** 完整 Spring HTTP 处理链的内存测试入口；不创建或替代真实监听端口。 */
final class WebTestSupport implements AutoCloseable {
    final ConfigurableApplicationContext context;
    private final HttpHandler handler;

    record Reply(int status, String body) {}

    WebTestSupport(Class<?> additionalConfiguration, String... options) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("supervisor.mode", "offline");
        // 通用 HTTP 用例共用容器；额度行为由 TaskRuntimeTest 的独立数据库验证。
        settings.put("supervisor.model-tokens-per-minute", "10000000");
        settings.put("supervisor.model-calls-per-minute", "10000");
        settings.put("supervisor.tasks-per-user-minute", "1000");
        settings.put(
                "supervisor.auth-tokens",
                "test-token=tenant:user:query_weather,query_trains,search_knowledge,memory:write;other-token=other:reader:query_weather");
        settings.put(
                "supervisor.database-url",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        settings.put("spring.main.banner-mode", "off");
        settings.put("logging.level.root", "WARN");
        for (String option : options) {
            String[] pair = option.substring(2).split("=", 2);
            settings.put(pair[0], pair[1]);
        }
        String[] args =
                settings.entrySet().stream()
                        .map(e -> "--" + e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new);
        SpringApplicationBuilder builder =
                new SpringApplicationBuilder(SupervisorApplication.class)
                        .contextFactory(type -> new GenericReactiveWebApplicationContext());
        if (additionalConfiguration != null) builder.sources(additionalConfiguration);
        context = builder.run(args);
        handler = WebHttpHandlerBuilder.applicationContext(context).build();
    }

    Mono<Reply> exchange(String body) {
        return exchange("POST", "/api/supervisor/chat", body, "test-token", false);
    }

    Mono<Reply> exchange(String method, String path, String body, String token, boolean async) {
        return exchange(method, path, body, token, async ? "respond-async" : null);
    }

    Mono<Reply> exchange(String method, String path, String body, String token, String prefer) {
        return Mono.defer(
                () -> {
                    var builder =
                            MockServerHttpRequest.method(
                                            org.springframework.http.HttpMethod.valueOf(method),
                                            path)
                                    .contentType(MediaType.APPLICATION_JSON);
                    if (token != null) builder.header("Authorization", "Bearer " + token);
                    if (prefer != null) builder.header("Prefer", prefer);
                    var request = builder.body(body == null ? "" : body);
                    var response = new MockServerHttpResponse();
                    return handler.handle(request, response)
                            .then(Mono.defer(() -> response.getBodyAsString().defaultIfEmpty("")))
                            .map(
                                    text ->
                                            new Reply(
                                                    response.getStatusCode() == null
                                                            ? 200
                                                            : response.getStatusCode().value(),
                                                    text));
                });
    }

    Reply post(String body) {
        return exchange(body).block(Duration.ofSeconds(10));
    }

    @Override
    public void close() {
        context.close();
    }
}
