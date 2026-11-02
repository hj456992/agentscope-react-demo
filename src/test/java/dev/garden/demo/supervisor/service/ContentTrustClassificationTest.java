package dev.garden.demo.supervisor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.dto.Intent;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;
import dev.garden.demo.supervisor.pojo.dto.Status;

import org.junit.jupiter.api.Test;

/** 运行详情按消息内容推导 SYSTEM/USER/TOOL/RETRIEVAL/CHILD，不依赖额外上下文元数据。 */
class ContentTrustClassificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void systemAssistantAndUserMessagesKeepTheirOrigin() throws Exception {
        assertEquals(
                "SYSTEM",
                RunDetailService.trustLevel(
                        JSON.readTree("{\"role\":\"SYSTEM\",\"content\":[{\"type\":\"text\",\"text\":\"规则\"}]}")));
        assertEquals(
                "USER",
                RunDetailService.trustLevel(
                        JSON.readTree(
                                "{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"帮我查天气\"}]}")));
        assertEquals(
                "RETRIEVAL",
                RunDetailService.trustLevel(
                        JSON.readTree(
                                "{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"上一话题已归档：偏好上海\"}]}")));
        assertEquals(
                "RETRIEVAL",
                RunDetailService.trustLevel(
                        JSON.readTree(
                                "{\"role\":\"ASSISTANT\",\"content\":[{\"type\":\"text\",\"text\":\"旧回答\"}]}")));
    }

    @Test
    void toolResultsDistinguishBusinessRetrievalAndChildContent() throws Exception {
        assertEquals(
                "TOOL",
                RunDetailService.trustLevel(tool("query_weather")));
        assertEquals(
                "RETRIEVAL",
                RunDetailService.trustLevel(tool("search_knowledge")));
        assertEquals(
                "CHILD",
                RunDetailService.trustLevel(tool("spawn_agent")));
        assertEquals(
                "CHILD",
                RunDetailService.trustLevel(
                        JSON.readTree(
                                "{\"role\":\"USER\",\"content\":[{\"type\":\"text\",\"text\":\"<agent_message sender=\\\"weather_agent\\\">{}</agent_message>\"}]}")));
    }

    @Test
    void childOutputsSurviveEvenWhenParentHistoryWasCompacted() {
        TaskDO parent = task("parent", null);
        TaskDO answered = task("child-1", "杭州明天多云。");
        TaskDO empty = task("child-2", " ");
        assertEquals(1, RunDetailService.childOutputs(parent, List.of(parent, answered, empty)));
        assertEquals(0, RunDetailService.childOutputs(parent, List.of(parent)));
    }

    private static TaskDO task(String runId, String answer) {
        TaskDO task = new TaskDO();
        task.runId = runId;
        if (answer != null) {
            task.result =
                    new ResultDTO(
                            Status.SUCCESS,
                            "问题",
                            "问题",
                            Intent.WEATHER,
                            answer,
                            List.of(),
                            List.of(),
                            List.of());
        }
        return task;
    }

    private static JsonNode tool(String name) throws Exception {
        return JSON.readTree(
                "{\"role\":\"TOOL\",\"content\":[{\"type\":\"tool_result\",\"name\":\""
                        + name
                        + "\",\"output\":[{\"type\":\"text\",\"text\":\"{}\"}]}]}");
    }
}
