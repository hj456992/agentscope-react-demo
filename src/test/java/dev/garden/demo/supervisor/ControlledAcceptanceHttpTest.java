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

/** 开启受控提示的实例：分支真实执行并在详情中标注 triggerMode。 */
class ControlledAcceptanceHttpTest {
    private static WebTestSupport web;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() {
        web = new WebTestSupport(null, "--supervisor.acceptance-hints-enabled=true");
    }

    @AfterAll
    static void stop() {
        if (web != null) {
            web.close();
        }
    }

    private JsonNode detail(String runId) throws Exception {
        String body =
                web.exchange(
                                "GET",
                                "/api/supervisor/runs/" + runId + "/detail",
                                null,
                                "test-token",
                                false)
                        .block(Duration.ofSeconds(10))
                        .body();
        return mapper.readTree(body);
    }

    @Test
    void spawnHintRunsTheRealChildPathAndIsMarkedControlled() throws Exception {
        var reply =
                web.post(
                        json(
                                new RequestDTO(
                                        "controlled-session",
                                        "杭州明天的天气",
                                        List.of(),
                                        "controlled-request",
                                        "SPAWN_CHILD")));
        assertEquals(200, reply.status(), reply.body());
        String runId = mapper.readTree(reply.body()).path("runId").asText();
        assertFalse(runId.isBlank());

        JsonNode detail = detail(runId);
        assertEquals("CONTROLLED", detail.path("triggerMode").asText());
        assertEquals("SPAWN_CHILD", detail.path("acceptanceHint").asText());
        assertFalse(detail.path("children").isEmpty(), detail.toString());
        assertTrue(
                detail.path("events").toString().contains("受控触发"), detail.path("events").toString());
        JsonNode child = detail.path("children").get(0);
        assertTrue(child.path("agentPath").asText().contains("acceptance_child"));
        assertFalse(child.path("taskBrief").path("objective").asText().isBlank());
        assertTrue(
                detail.path("delegations").toString().contains(child.path("runId").asText()),
                detail.path("delegations").toString());
    }

    @Test
    void abortedToolCallIsPersistedAsTerminalEvidence() throws Exception {
        var reply =
                web.post(
                        json(
                                new RequestDTO(
                                        "aborted-session",
                                        "杭州明天的天气",
                                        List.of(),
                                        "aborted-request",
                                        "UNKNOWN_TOOL_3")));
        assertTrue(reply.status() == 200 || reply.status() == 502, reply.body());
        String runId = mapper.readTree(reply.body()).path("runId").asText();

        JsonNode detail = detail(runId);
        JsonNode calls = detail.path("toolCalls");
        assertFalse(calls.isEmpty(), detail.toString());
        for (JsonNode call : calls) {
            assertNotEquals("RUNNING", call.path("status").asText(), detail.toString());
        }
        JsonNode aborted = calls.get(calls.size() - 1);
        assertEquals("ABORTED", aborted.path("status").asText(), detail.toString());
        assertFalse(aborted.path("paired").asBoolean(), detail.toString());
    }

    @Test
    void hintOutsideTheAllowlistIsRejectedBeforeCreatingATask() {
        var reply =
                web.post(
                        json(
                                new RequestDTO(
                                        "invalid-session",
                                        "杭州明天的天气",
                                        List.of(),
                                        "invalid-request",
                                        "FREE_TEXT")));
        assertEquals(400, reply.status(), reply.body());
        assertTrue(reply.body().contains("\"code\""), reply.body());
    }
}
