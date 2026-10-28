package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import reactor.core.publisher.Mono;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 只读工具的实际执行入口：有界线程、持久尝试次数、有限重试、成功回放。 */
final class ToolExecutor {
    private static final ThreadPoolExecutor WORKERS =
            new ThreadPoolExecutor(
                    8,
                    8,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    runnable -> {
                        Thread thread = new Thread(runnable, "travel-tool");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    static {
        WORKERS.allowCoreThreadTimeOut(true);
    }

    private final RunContext context;

    ToolExecutor(RunContext context) {
        this.context = context;
    }

    Mono<Evidence> read(String tool, Map<String, Object> params, Supplier<Evidence> action) {
        return Mono.defer(
                () -> {
                    context.check();
                    if (!context.control.identity().allows(tool))
                        return Mono.error(new SecurityException("无工具权限"));
                    String key =
                            "read:" + tool + ":" + RunContext.fingerprint(new TreeMap<>(params));
                    String stored = context.control.checkpoint(key);
                    if (stored != null) {
                        Evidence result =
                                io.agentscope.core.util.JsonUtils.getJsonCodec()
                                        .fromJson(stored, Evidence.class);
                        context.add(result);
                        context.event("复用已完成工具：" + tool);
                        return Mono.just(result);
                    }
                    return context.share(
                            key,
                            () ->
                                    attempt(key, action)
                                            .doOnNext(
                                                    result -> {
                                                        context.check();
                                                        if (result.status() == Status.SUCCESS
                                                                || result.status()
                                                                        == Status.NO_DATA)
                                                            context.control.checkpoint(
                                                                    key, json(result));
                                                        context.add(result);
                                                    }));
                });
    }

    private Mono<Evidence> attempt(String key, Supplier<Evidence> action) {
        return Mono.defer(
                () -> {
                    context.check();
                    int attempt = context.increment("attempt:" + key, 3);
                    Duration timeout =
                            context.control.remaining().compareTo(Duration.ofSeconds(5)) < 0
                                    ? context.control.remaining()
                                    : Duration.ofSeconds(5);
                    return blocking(action)
                            .timeout(timeout)
                            .onErrorResume(
                                    ex -> {
                                        if (!retryable(ex) || attempt >= 3) return Mono.error(ex);
                                        Duration delay = retryDelay(ex, attempt);
                                        if (context.control
                                                        .remaining()
                                                        .compareTo(delay.plusMillis(1))
                                                <= 0) return Mono.error(ex);
                                        context.event("只重试失败工具，第" + (attempt + 1) + "次");
                                        return Mono.delay(delay).then(attempt(key, action));
                                    });
                });
    }

    static Duration retryDelay(Throwable error, int attempt) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof TemporaryFailure failure && failure.retryAfter != null)
                return failure.retryAfter;
        return Duration.ofMillis(
                Math.min(2000, 200L << (attempt - 1)) + ThreadLocalRandom.current().nextInt(50));
    }

    static boolean retryable(Throwable error) {
        for (int i = 0; error != null && i < 5; i++, error = error.getCause()) {
            if (error
                            instanceof
                            io.agentscope.extensions.model.openai.exception.OpenAIException provider
                    && "insufficient_quota".equals(provider.getErrorCode())) return false;
            if (error instanceof io.agentscope.core.model.ModelHttpException http
                    && http.getStatusCode() != null) return http.isRetryableHttpStatus();
            if (error instanceof io.agentscope.core.model.transport.HttpTransportException transport
                    && transport.getStatusCode() != null) return transport.isRetryable();
            if (error instanceof TemporaryFailure
                    || error instanceof ConnectException
                    || error instanceof SocketTimeoutException
                    || error instanceof java.util.concurrent.TimeoutException) return true;
            if (error instanceof SecurityException
                    || error instanceof IllegalArgumentException
                    || error instanceof RunControl.BudgetExceeded
                    || error instanceof RunControl.Cancelled
                    || error instanceof RunControl.ContextLimit) return false;
        }
        return false;
    }

    static final class TemporaryFailure extends RuntimeException {
        final Duration retryAfter;

        TemporaryFailure(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }
    }

    static <T> Mono<T> blocking(Supplier<T> action) {
        return Mono.create(
                sink -> {
                    FutureTask<Void> future =
                            new FutureTask<>(
                                    () -> {
                                        try {
                                            sink.success(action.get());
                                        } catch (Throwable error) {
                                            sink.error(error);
                                        }
                                        return null;
                                    });
                    sink.onCancel(
                            () -> {
                                future.cancel(true);
                                WORKERS.remove(future);
                            });
                    try {
                        WORKERS.execute(future);
                    } catch (RejectedExecutionException ex) {
                        sink.error(ex);
                    }
                    // 不合作的阻塞调用仍占用这 8 个物理线程，取消不会凭空增加工作线程。
                });
    }
}
