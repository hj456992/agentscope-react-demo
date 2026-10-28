package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.service.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.config.*;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

class ModelTransportTest {
    @Test
    void realHttpRetryHonorsRetryAfterAndEachSendConsumesBudget() throws Exception {
        exercise(429, true);
    }

    @Test
    void realHttpAuthenticationErrorDoesNotRetry() throws Exception {
        exercise(401, false);
    }

    @Test
    void realHttpQuotaExhaustionDoesNotRetry() throws Exception {
        exercise(429, false, true);
    }

    private void exercise(int initialStatus, boolean succeeds) throws Exception {
        exercise(initialStatus, succeeds, false);
    }

    private void exercise(int initialStatus, boolean succeeds, boolean quota) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    int attempt = requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    int status = attempt == 1 ? initialStatus : 200;
                    String body =
                            status == 200
                                    ? "{\"id\":\"test\",\"model\":\"local\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"
                                    : "{\"error\":{\"message\":\"test"
                                          + " failure\",\"code\":\"test_error\",\"type\":\"test\"}}";
                    if (quota) {
                        body = "{\"error\":{\"code\":\"insufficient_quota\"}}";
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Retry-After", "1");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try (ModelHttpTransport transport = new ModelHttpTransport()) {
            Model model =
                    OpenAIChatModel.builder()
                            .apiKey("local-test-only")
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .modelName("local")
                            .httpTransport(transport)
                            .stream(false)
                            .build();
            RunContext context =
                    new RunContext(
                            new RequestDTO("transport", "test", List.of()),
                            LimitsDTO.defaults(),
                            RunControl.local(LimitsDTO.defaults()));
            var response =
                    context.budgeted(model).stream(
                            List.of(Msg.builder().role(MsgRole.USER).textContent("test").build()),
                            List.of(),
                            GenerateOptions.builder()
                                    .executionConfig(
                                            ExecutionConfig.builder().maxAttempts(1).build())
                                    .build());
            long start = System.nanoTime();
            if (succeeds) {
                assertNotNull(response.blockLast(Duration.ofSeconds(5)));
                assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(900));
            } else {
                assertThrows(
                        RuntimeException.class, () -> response.blockLast(Duration.ofSeconds(5)));
            }
            assertEquals(succeeds ? 2 : 1, requests.get());
            assertEquals(requests.get(), context.control.modelCalls());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sdkWrapperWithoutStatusStillRetriesUnderlyingConnectionFailure() {
        AtomicInteger calls = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "wrapped-connection";
                    }

                    public reactor.core.publisher.Flux<ChatResponse> stream(
                            List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                        if (calls.incrementAndGet() == 1) {
                            return reactor.core.publisher.Flux.error(
                                    new io.agentscope.extensions.model.openai.exception
                                            .OpenAIException(
                                            "connection",
                                            new io.agentscope.core.model.transport
                                                    .HttpTransportException(
                                                    "connect",
                                                    new java.net.ConnectException("temporary"))));
                        }
                        return reactor.core.publisher.Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("ok").build()))
                                        .finishReason("stop")
                                        .build());
                    }
                };
        RunContext context =
                new RunContext(
                        new RequestDTO("wrapped", "test", List.of()),
                        LimitsDTO.defaults(),
                        RunControl.local(LimitsDTO.defaults()));
        assertNotNull(
                context.budgeted(model).stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("test")
                                                .build()),
                                List.of(),
                                GenerateOptions.builder().build())
                        .blockLast());
        assertEquals(2, calls.get());
        assertEquals(2, context.control.modelCalls());
    }

    @Test
    void fingerprintIsCanonicalAcrossMapOrderAndFreshJvms() throws Exception {
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("z", 3);
        reversed.put("a", Map.of("y", 2, "x", 1));
        String expected = RunContext.fingerprint(reversed);
        for (int i = 0; i < 3; i++) {
            Process process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    FingerprintProcess.class.getName())
                            .redirectErrorStream(true)
                            .start();
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
                assertEquals(
                        expected,
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                                .strip());
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}
