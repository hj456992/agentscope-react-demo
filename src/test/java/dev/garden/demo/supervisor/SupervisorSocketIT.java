package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

/** 使用真实 Spring 容器、真实监听端口和 HTTP 客户端，完整执行三个 Agent。 */
class SupervisorSocketIT {
    private static ReactiveWebServerApplicationContext app;
    private static URI endpoint;
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        app =
                (ReactiveWebServerApplicationContext)
                        new SpringApplicationBuilder(SupervisorApplication.class)
                                .run(
                                        "--server.port=0",
                                        "--supervisor.mode=offline",
                                        "--spring.main.banner-mode=off",
                                        "--supervisor.auth-tokens=test-token=tenant:user:query_weather,query_trains,search_knowledge,memory:write",
                                        "--supervisor.database-url=jdbc:h2:mem:socket",
                                        "--supervisor.model-tokens-per-minute=10000000",
                                        "--supervisor.tasks-per-user-minute=1000");
        endpoint =
                URI.create(
                        "http://127.0.0.1:"
                                + app.getWebServer().getPort()
                                + "/api/supervisor/chat");
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer test-token")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ask(String question) throws Exception {
        HttpResponse<String> response =
                post(
                        json(
                                new ChatInput(
                                        java.util.UUID.randomUUID().toString(),
                                        question,
                                        List.of())));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    @Test
    void weatherTrainsAndItineraryRunThroughHttp() throws Exception {
        assertEquals("SUCCESS", ask("杭州明天的天气").path("status").asText());
        assertTrue(ask("明天从杭州到上海的火车票").path("answer").asText().contains("G-DEMO"));
        JsonNode result = ask("明天从杭州到上海玩一天，看看天气和火车票");
        assertEquals("SUCCESS", result.path("status").asText());
        assertEquals(3, result.path("agents").size());
        assertEquals(3, result.path("evidence").size());
    }

    @Test
    void businessBranchesAreNormalResponses() throws Exception {
        assertEquals("NEEDS_INPUT", ask("明天天气怎么样").path("status").asText());
        assertEquals("UNKNOWN_INTENT", ask("帮我写诗").path("status").asText());
        assertEquals("NO_DATA", ask("成都明天的天气").path("status").asText());
    }

    @Test
    void malformedAndMissingFieldsReturnBadRequest() throws Exception {
        for (String input :
                List.of(
                        "{",
                        "{}",
                        "{\"sessionId\":\"甲\",\"question\":\" \"}",
                        "{\"sessionId\":\"甲\",\"question\":\"天气\",\"history\":[{}]}")) {
            HttpResponse<String> response = post(input);
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("INVALID_REQUEST", json.readTree(response.body()).path("code").asText());
        }
    }

    @Test
    void oversizedInputReturnsPayloadTooLarge() throws Exception {
        assertEquals(413, post(json(new ChatInput("甲", "字".repeat(2001), List.of()))).statusCode());
        HttpResponse<String> response = post(json(new ChatInput("甲", "x".repeat(70_000), List.of())));
        assertEquals(413, response.statusCode(), response.body());
    }

    @Test
    void sharedBeanKeepsConcurrentHistoriesSeparate() {
        Supervisor supervisor = app.getBean(Supervisor.class);
        assertSame(supervisor, app.getBean(Supervisor.class));
        List<String> cities = List.of("杭州", "上海");
        var replies =
                cities.stream()
                        .map(
                                city -> {
                                    String body =
                                            json(
                                                    new ChatInput(
                                                            city,
                                                            "那后天呢",
                                                            List.of(
                                                                    new Turn(
                                                                            city + "明天的天气",
                                                                            "示例天气"))));
                                    return client.sendAsync(
                                            HttpRequest.newBuilder(endpoint)
                                                    .header("Content-Type", "application/json")
                                                    .header("Authorization", "Bearer test-token")
                                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString());
                                })
                        .toList();
        for (int i = 0; i < replies.size(); i++) {
            HttpResponse<String> response = replies.get(i).join();
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains(cities.get(i)));
            assertFalse(response.body().contains(cities.get(1 - i)));
        }
    }
}
