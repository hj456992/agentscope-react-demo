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
                                "/api/supervisor/memories/preferredDepartureCity/1",
                                null,
                                "test-token",
                                false)
                        .status());
        assertFalse(
                call("GET", "/api/supervisor/memories", null, "test-token", false)
                        .body()
                        .contains("杭州"));
    }

    @Test
    void memoryPaginationUsesCursorPath() throws Exception {
        call(
                "PUT",
                "/api/supervisor/memories/preferredDepartureCity",
                "{\"value\":\"杭州\",\"expectedVersion\":0}",
                "test-token",
                false);
        call(
                "PUT",
                "/api/supervisor/memories/preferredSeat",
                "{\"value\":\"二等座\",\"expectedVersion\":0}",
                "test-token",
                false);
        var page = call("GET", "/api/supervisor/memories", null, "test-token", false);
        assertEquals(200, page.status());
        assertEquals(2, json.readTree(page.body()).path("items").size());
        assertTrue(json.readTree(page.body()).path("nextCursor").isNull());
        // 游标通过路径传入；非法游标在服务层被拒绝。
        assertEquals(
                400,
                call("GET", "/api/supervisor/memories/not-a-cursor", null, "test-token", false)
                        .status());
    }
}
