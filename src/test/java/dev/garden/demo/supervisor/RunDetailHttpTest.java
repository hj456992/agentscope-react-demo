package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.pojo.dto.RequestDTO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

/** 经过 Spring 路由、鉴权过滤器和实际 Agent 链，核对运行详情与配置证据。 */
class RunDetailHttpTest {
    private static WebTestSupport web;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() {
        web = new WebTestSupport(null);
    }

    @AfterAll
    static void stop() {
        if (web != null) {
            web.close();
        }
    }

    private JsonNode run(String question) throws Exception {
        var reply =
                web.post(
                        json(
                                new RequestDTO(
                                        java.util.UUID.randomUUID().toString(),
                                        question,
                                        List.of())));
        assertEquals(200, reply.status(), reply.body());
        return mapper.readTree(reply.body());
    }

    private JsonNode detail(String runId, String token) throws Exception {
        var reply =
                web.exchange(
                                "GET",
                                "/api/supervisor/runs/" + runId + "/detail",
                                null,
                                token,
                                false)
                        .block(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertEquals(200, reply.status(), reply.body());
        return mapper.readTree(reply.body());
    }

    @Test
    void detailExposesPlanChildrenToolsDelegationsAndUsage() throws Exception {
        JsonNode run = run("杭州明天的天气");
        String runId = run.path("runId").asText();
        assertEquals("SUCCESS", run.path("state").asText());

        JsonNode detail = detail(runId, "test-token");
        assertEquals(runId, detail.path("runId").asText());
        assertEquals("SUCCESS", detail.path("state").asText());
        assertEquals("1", detail.path("agentVersion").asText());
        assertFalse(detail.path("children").isEmpty(), detail.toString());
        assertFalse(detail.path("toolCalls").isEmpty(), detail.toString());
        assertFalse(detail.path("delegations").isEmpty(), detail.toString());
        assertFalse(detail.path("usage").path("agents").isEmpty(), detail.toString());
        assertFalse(detail.path("spans").isEmpty(), detail.toString());
        assertEquals(2, detail.path("promptVersions").size());
        assertTrue(
                detail.path("permissions").path("evidenceRecheckPassed").asBoolean(),
                detail.toString());
        assertEquals("NORMAL", detail.path("triggerMode").asText());
        assertTrue(detail.path("firstEventAt").asLong() > 0, detail.toString());
        assertFalse(detail.path("timeline").isEmpty(), detail.toString());
        assertTrue(detail.path("routing").path("candidates").isArray(), detail.toString());
        assertFalse(detail.path("routing").path("decision").asText().isBlank(), detail.toString());
        assertTrue(detail.path("permissions").path("directoryTools").isArray(), detail.toString());
        assertTrue(detail.path("permissions").path("filteredReasons").isObject(), detail.toString());
        JsonNode firstTool = detail.path("toolCalls").get(0);
        assertTrue(firstTool.path("paired").asBoolean(), detail.toString());
        assertEquals(firstTool.path("callId").asText(), firstTool.path("operationId").asText());
        assertTrue(
                detail.path("spans").findValues("kind").stream()
                        .anyMatch(node -> "RUN".equals(node.asText())),
                detail.toString());
        JsonNode firstChild = detail.path("children").get(0);
        assertTrue(firstChild.path("taskBrief").isObject(), detail.toString());
        assertFalse(firstChild.path("taskBrief").path("objective").asText().isBlank());
        assertFalse(firstChild.path("spawnOperationId").asText().isBlank(), detail.toString());
    }

    @Test
    void detailNeverReturnsModelConversationRecallTextOrTokens() throws Exception {
        JsonNode run = run("杭州明天的天气");
        JsonNode detail = detail(run.path("runId").asText(), "test-token");
        String body = detail.toString();
        assertFalse(body.contains("loop:v3.3"));
        assertFalse(body.contains("context-recall:v1:"));
        assertFalse(body.contains("test-token"));
        assertFalse(detail.path("checkpoints").has("loop:v3.3"));
    }

    @Test
    void detailUsesTheSameOwnershipAndPermissionGate() throws Exception {
        JsonNode run = run("杭州明天的天气");
        var reply =
                web.exchange(
                                "GET",
                                "/api/supervisor/runs/" + run.path("runId").asText() + "/detail",
                                null,
                                "other-token",
                                false)
                        .block(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertEquals(404, reply.status(), reply.body());
    }

    @Test
    void technicalDocumentAliasReturnsTheSameDetail() throws Exception {
        JsonNode run = run("杭州明天的天气");
        var reply =
                web.exchange(
                                "GET",
                                "/api/supervisor/runs/detail/" + run.path("runId").asText(),
                                null,
                                "test-token",
                                false)
                        .block(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertEquals(200, reply.status(), reply.body());
        assertEquals(
                run.path("runId").asText(), mapper.readTree(reply.body()).path("runId").asText());
    }

    @Test
    void configReportsActualBudgetsAndRedactsTokens() throws Exception {
        var reply =
                web.exchange("GET", "/api/supervisor/config", null, "test-token", false)
                        .block(Duration.ofSeconds(10));
        assertNotNull(reply);
        assertEquals(200, reply.status(), reply.body());
        JsonNode config = mapper.readTree(reply.body());
        assertEquals("offline", config.path("mode").asText());
        assertEquals(60, config.path("timeoutSeconds").asLong());
        assertEquals(16, config.path("maxModelCalls").asInt());
        assertEquals(64, config.path("maxSteps").asInt());
        assertEquals("h2", config.path("databaseType").asText());
        assertTrue(
                config.path("authIdentities").toString().contains("tenant:user:"),
                reply.body());
        assertFalse(reply.body().contains("test-token"));
        assertFalse(reply.body().contains("other-token"));
    }
}
