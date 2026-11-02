package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import reactor.core.publisher.Mono;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 只读工具的实际执行入口：有界线程、持久尝试次数、有限重试、成功回放。 */
public final class ToolExecutor {
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

    Mono<EvidenceDTO> read(String tool, Map<String, Object> params, Supplier<EvidenceDTO> action) {
        return Mono.defer(
                () -> {
                    context.check();
                    if (!context.control.identity().allows(tool)) {
                        return Mono.error(new SecurityException("无工具权限"));
                    }
                    String key =
                            "read:" + tool + ":" + RunContext.fingerprint(new TreeMap<>(params));
                    String stored = context.control.checkpoint(key);
                    if (stored != null) {
                        EvidenceDTO cached =
                                io.agentscope.core.util.JsonUtils.getJsonCodec()
                                        .fromJson(stored, EvidenceDTO.class);
                        Map<String, Object> data = new LinkedHashMap<>(cached.data());
                        data.put("reused", true);
                        EvidenceDTO result =
                                new EvidenceDTO(cached.tool(), cached.status(), cached.source(), data);
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
                                                                        == Status.NO_DATA) {
                                                            context.control.checkpoint(
                                                                    key, json(result));
                                                        }
                                                        context.add(result);
                                                    }));
                });
    }

    private Mono<EvidenceDTO> attempt(String key, Supplier<EvidenceDTO> action) {
        return Mono.defer(
                () -> {
                    context.check();
                    SupervisorPolicy.FailurePolicy failure = context.control.policy().failure();
                    int attempt = context.increment("attempt:" + key, failure.maxAttempts());
                    Duration timeout =
                            context.control.remaining().compareTo(Duration.ofSeconds(5)) < 0
                                    ? context.control.remaining()
                                    : Duration.ofSeconds(5);
                    return blocking(action)
                            .timeout(timeout)
                            .onErrorResume(
                                    ex -> {
                                        if (!retryable(ex) || attempt >= failure.maxAttempts()) {
                                            return Mono.error(ex);
                                        }
                                        Duration delay =
                                                retryDelay(ex, attempt, failure.backoffCapMs());
                                        if (context.control
                                                        .remaining()
                                                        .compareTo(delay.plusMillis(1))
                                                <= 0) {
                                            return Mono.error(ex);
                                        }
                                        String reason;
                                        if (ex instanceof TemporaryFailure temporary) {
                                            reason =
                                                    "："
                                                            + temporary.failureClass
                                                            + (temporary.providerCode == null
                                                                            || temporary.providerCode
                                                                                    .isBlank()
                                                                    ? ""
                                                                    : "/" + temporary.providerCode);
                                        } else {
                                            reason = "：" + FailureClassifier.classify(ex);
                                        }
                                        context.event(
                                                "只重试失败工具，第"
                                                        + (attempt + 1)
                                                        + "次"
                                                        + reason);
                                        return Mono.delay(delay).then(attempt(key, action));
                                    });
                });
    }

    static Duration retryDelay(Throwable error, int attempt) {
        return FailureClassifier.retryDelay(error, attempt, 2000);
    }

    static Duration retryDelay(Throwable error, int attempt, long capMs) {
        return FailureClassifier.retryDelay(error, attempt, capMs);
    }

    static boolean retryable(Throwable error) {
        return FailureClassifier.retryable(error);
    }

    /** 传输层抛出的暂时失败；携带分类与供应商错误码，供统一重试判定使用。 */
    public static final class TemporaryFailure extends RuntimeException {
        public final Duration retryAfter;
        public final FailureClass failureClass;
        public final String providerCode;

        public TemporaryFailure(String message, Duration retryAfter) {
            this(message, retryAfter, FailureClass.RETRYABLE, "");
        }

        public TemporaryFailure(
                String message,
                Duration retryAfter,
                FailureClass failureClass,
                String providerCode) {
            super(message);
            this.retryAfter = retryAfter;
            this.failureClass = failureClass;
            this.providerCode = providerCode;
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
