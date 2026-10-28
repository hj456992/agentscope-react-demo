package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.*;

import java.time.Duration;

class RuntimeHttpTest {
    private WebTestSupport web;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() {
        web = new WebTestSupport(null);
    }

    @AfterEach
    void stop() {
        web.close();
    }

    private WebTestSupport.Reply call(
            String method, String path, String body, String token, boolean async) {
        return web.exchange(method, path, body, token, async).block(Duration.ofSeconds(10));
    }

    @Test
    void missingIdentityCannotStartTask() {
        assertEquals(
                401,
                call(
                                "POST",
                                "/api/supervisor/chat",
                                "{\"sessionId\":\"a\",\"question\":\"杭州明天天气\"}",
                                null,
                                false)
                        .status());
    }

    @Test
    void requestReplayReturnsSameRunAndConflictCannotExecute() throws Exception {
        String body = "{\"sessionId\":\"a\",\"question\":\"杭州明天天气\",\"requestId\":\"request-1\"}";
        var first = call("POST", "/api/supervisor/chat", body, "test-token", false);
        assertEquals(200, first.status(), first.body());
        String id = json.readTree(first.body()).path("runId").asText();
        assertFalse(id.isBlank());
        assertEquals(
                id,
                json.readTree(
                                call("POST", "/api/supervisor/chat", body, "test-token", false)
                                        .body())
                        .path("runId")
                        .asText());
        assertEquals(
                409,
                call("POST", "/api/supervisor/chat", body.replace("杭州", "上海"), "test-token", false)
                        .status());
        assertEquals(
                404,
                call("GET", "/api/supervisor/runs/" + id, null, "other-token", false).status());
        assertEquals(
                200, call("GET", "/api/supervisor/runs/" + id, null, "test-token", false).status());
    }

    @Test
    void instructionEndpointTakesRunIdInBody() {
        String body =
                "{\"runId\":\"missing\",\"instructionId\":\"i-1\",\"expectedRevision\":1,"
                        + "\"action\":\"CANCEL\",\"message\":\"\"}";
        // 任务编号随 body 提交；未知任务走到业务层后返回 404。
        assertEquals(
                404,
                call("POST", "/api/supervisor/instructions", body, "test-token", false).status());
        // 旧路径已移除，不再有路由。
        assertEquals(
                404,
                call(
                                "POST",
                                "/api/supervisor/runs/missing/instructions",
                                body,
                                "test-token",
                                false)
                        .status());
        // 认证仍在过滤器统一处理。
        assertEquals(
                401,
                call("POST", "/api/supervisor/instructions", body, null, false).status());
    }

    @Test
    void memoryWriteRequiresPermissionAndVersion() throws Exception {
        String body = "{\"value\":\"杭州\",\"expectedVersion\":0}";
        assertEquals(
                403,
                call(
                                "PUT",
                                "/api/supervisor/memories/preferredDepartureCity",
                                body,
                                "other-token",
                                false)
                        .status());
        assertEquals(
                200,
                call(
                                "PUT",
                                "/api/supervisor/memories/preferredDepartureCity",
                                body,
                                "test-token",
                                false)
                        .status());
        assertEquals(
                409,
                call(
                                "PUT",
                                "/api/supervisor/memories/preferredDepartureCity",
                                body,
                                "test-token",
                                false)
                        .status());
        assertEquals(
                204,
                call(
                                "DELETE",
                                "/api/supervisor/memories/preferredDepartureCity?expectedVersion=1",
                                null,
                                "test-token",
                                false)
                        .status());
        assertFalse(
                call("GET", "/api/supervisor/memories", null, "test-token", false)
                        .body()
                        .contains("杭州"));
    }
}
