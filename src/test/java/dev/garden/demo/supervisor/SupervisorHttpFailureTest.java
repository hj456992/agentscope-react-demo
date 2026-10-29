package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.config.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.service.*;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

class SupervisorHttpFailureTest {
    private static WebTestSupport web;
    private static volatile CountDownLatch started;
    private static volatile CountDownLatch cancelled;
    private static final AtomicBoolean breakFactory = new AtomicBoolean();
    private static volatile String failureMode = "normal";

    @BeforeAll
    static void start() {
        web =
                new WebTestSupport(
                        ModelOverrides.class,
                        "--supervisor.timeout=2s",
                        "--supervisor.max-concurrent-requests=1");
    }

    @AfterAll
    static void stop() {
        if (web != null) {
            web.close();
        }
    }

    @BeforeEach
    void reset() {
        started = new CountDownLatch(1);
        cancelled = new CountDownLatch(1);
        breakFactory.set(false);
        failureMode = "normal";
    }

    private String input(String question) {
        return json(new RequestDTO(java.util.UUID.randomUUID().toString(), question, List.of()));
    }

    @Test
    void modelFailureIs502AndPartialResultIs200() {
        failureMode = "fail";
        var failed = web.post(input("杭州明天天气 模拟失败"));
        assertEquals(502, failed.status(), failed.body());
        assertTrue(failed.body().contains("FAILED"));
        assertFalse(failed.body().contains("PRIVATE_PROVIDER_DETAILS"));
        var partial = web.post(input("明天从杭州到上海玩一天，看看天气和火车票 模拟失败"));
        assertEquals(200, partial.status());
        assertTrue(partial.body().contains("PARTIAL"));
        assertTrue(partial.body().contains("G-DEMO"));
    }

    @Test
    void timeoutReturns504AndReleasesCapacity() throws Exception {
        failureMode = "wait";
        var result = web.post(input("杭州明天天气 模拟等待"));
        assertEquals(504, result.status(), result.body());
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        failureMode = "normal";
        assertEquals(200, web.post(input("杭州明天天气")).status());
    }

    @Test
    void overloadIs429AndHttpDisconnectKeepsOwnedTaskRunning() throws Exception {
        failureMode = "wait";
        var first = web.exchange(input("杭州明天天气 模拟等待")).subscribe();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(429, web.post(input("上海明天天气")).status());
            first.dispose();
            assertFalse(cancelled.await(100, TimeUnit.MILLISECONDS));
            assertEquals(429, web.post(input("上海明天天气")).status());
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            failureMode = "normal";
            // 下游取消通知先于整个运行的 finally；等待真实任务名额释放。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            WebTestSupport.Reply after;
            do {
                after = web.post(input("上海明天天气"));
                if (after.status() != 429) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertEquals(200, after.status(), after.body());
        } finally {
            first.dispose();
        }
    }

    @Test
    void ownedTaskSetupFailureIs502AndDoesNotLeakDetails() {
        breakFactory.set(true);
        var result = web.post(input("杭州明天天气"));
        assertEquals(502, result.status(), result.body());
        assertTrue(result.body().contains("FAILED"));
        assertFalse(result.body().contains("PRIVATE_PROVIDER_DETAILS"));
        assertEquals(200, web.post(input("杭州明天天气")).status());
    }

    // 显式交给测试容器的配置类，不带 @Configuration，避免被其他测试的包扫描加载。
    static class ModelOverrides {
        @Bean
        @Primary
        Function<String, Model> faultModels() {
            return role -> {
                if (breakFactory.getAndSet(false)) {
                    throw new IllegalStateException("PRIVATE_PROVIDER_DETAILS");
                }
                Model normal = new OfflineScenarioModel(role);
                return new Model() {
                    @Override
                    public String getModelName() {
                        return "故障测试模型";
                    }

                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        String text =
                                messages.stream()
                                        .map(Msg::getTextContent)
                                        .reduce("", String::concat);
                        if (role.equals("weather_agent") && failureMode.equals("wait")) {
                            return Flux.<ChatResponse>never()
                                    .doOnSubscribe(ignored -> started.countDown())
                                    .doOnCancel(() -> cancelled.countDown());
                        }
                        if (role.equals("weather_agent") && failureMode.equals("fail")) {
                            return Flux.error(
                                    new IllegalStateException("PRIVATE_PROVIDER_DETAILS"));
                        }
                        return normal.stream(messages, tools, options);
                    }
                };
            };
        }
    }
}
