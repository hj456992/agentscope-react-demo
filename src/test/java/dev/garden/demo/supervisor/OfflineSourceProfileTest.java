package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 离线脚本的工具协议回归；不把脚本词法规则当作生产模型理解能力。 */
class OfflineSourceProfileTest {
    @Test
    void itineraryCompletesWithinSharedBudgetAfterRealCompaction() {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                new Model() {
                                    final Model delegate = new OfflineScenarioModel(role);

                                    public String getModelName() {
                                        return delegate.getModelName();
                                    }

                                    public reactor.core.publisher.Flux<ChatResponse> stream(
                                            List<Msg> messages,
                                            List<ToolSchema> tools,
                                            GenerateOptions options) {
                                        return delegate.stream(messages, tools, options)
                                                .doOnNext(
                                                        reply ->
                                                                trace.add(
                                                                        role
                                                                                + " size="
                                                                                + ContextBuilder
                                                                                        .estimate(
                                                                                                Map
                                                                                                        .of(
                                                                                                                "messages",
                                                                                                                messages,
                                                                                                                "tools",
                                                                                                                tools))
                                                                                + " -> "
                                                                                + reply
                                                                                        .getContent()
                                                                                        .stream()
                                                                                        .map(
                                                                                                block ->
                                                                                                        block
                                                                                                                        instanceof
                                                                                                                        ToolUseBlock
                                                                                                                                        call
                                                                                                                ? call
                                                                                                                        .getName()
                                                                                                                : block
                                                                                                                                instanceof
                                                                                                                                TextBlock
                                                                                                                                                text
                                                                                                                        ? text
                                                                                                                                .getText()
                                                                                                                        : block
                                                                                                                                .toString())
                                                                                        .toList()));
                                    }
                                },
                        java.time.Clock.fixed(
                                java.time.Instant.parse("2026-10-28T02:00:00Z"),
                                java.time.ZoneOffset.UTC),
                        dev.garden.demo.supervisor.pojo.dto.LimitsDTO.defaults(),
                        TravelTools.demoData());
        var result =
                supervisor
                        .run(
                                new dev.garden.demo.supervisor.pojo.dto.RequestDTO(
                                        "compact-itinerary", "明天从杭州到上海玩一天，规划行程", List.of()))
                        .block();
        assertEquals(
                dev.garden.demo.supervisor.pojo.dto.Status.SUCCESS,
                result.status(),
                result + "\n" + String.join("\n", trace));
        assertTrue(
                result.answer().contains("travel-guide"), result + "\n" + String.join("\n", trace));
        assertTrue(result.answer().contains("G-DEMO"), result.answer());
    }

    @Test
    void supervisorSpawnsWeatherRoleWithExplicitTaskInsteadOfLegacyTool() {
        ToolUseBlock call = call(response("supervisor", List.of(user("杭州明天天气怎么样"))));
        assertEquals("spawn_agent", call.getName());
        assertEquals("weather_agent", call.getInput().get("agent_type"));
        assertEquals("none", call.getInput().get("fork_turns"));
        assertTrue(call.getInput().get("message").toString().contains("2026-10-29"));
    }

    @Test
    void pendingChildProducesWaitAndCompletedMailboxProducesEvidenceAnswer() {
        List<Msg> history = new ArrayList<>(List.of(user("杭州明天天气怎么样")));
        history.add(assistant(call(response("supervisor", history))));
        history.add(
                result(
                        "spawn_agent",
                        "{\"agentId\":\"weather-1\",\"agentName\":\"/root/weather\",\"agentRole\":\"weather_agent\",\"agentStatus\":\"running\",\"result\":null}"));
        assertEquals("wait_agent", call(response("supervisor", history)).getName());
        history.add(
                user(
                        "<agent_message"
                            + " sender=\"/root/weather\">{\"kind\":\"RESULT\",\"sender\":\"/root/weather\",\"message\":\"天气完成\",\"result\":{\"status\":\"SUCCESS\",\"evidence\":["
                                + weatherEvidence()
                                + "]}}</agent_message>"));
        String answer = text(response("supervisor", history));
        assertTrue(answer.contains("18"), answer);
        assertTrue(answer.contains("杭州"), answer);
        assertTrue(answer.contains("示例"), answer);
    }

    @Test
    void childAcceptsStructuredTaskAndQueriesRealRegisteredTool() {
        ChatResponse response =
                response(
                        "weather_agent",
                        List.of(user("{\"city\":\"上海\",\"date\":\"2026-10-29\"}")));
        ToolUseBlock call = call(response);
        assertEquals("query_weather", call.getName());
        assertEquals("上海", call.getInput().get("city"));
        assertEquals("2026-10-29", call.getInput().get("date"));
    }

    @Test
    void trainTaskUsesTrainToolAndDoesNotNeedOldSystemDecision() {
        ToolUseBlock call = call(response("train_agent", List.of(user("明天从杭州到上海的火车票"))));
        assertEquals("query_trains", call.getName());
        assertEquals("杭州", call.getInput().get("from"));
        assertEquals("上海", call.getInput().get("to"));
    }

    @Test
    void actualQuestionAnswerCompletesMissingConditions() {
        List<Msg> history = new ArrayList<>(List.of(user("明天天气怎么样")));
        ToolUseBlock question = call(response("supervisor", history));
        assertEquals("request_user_input", question.getName());
        history.add(assistant(question));
        history.add(
                result(
                        "request_user_input",
                        "{\"answers\":{\"conditions\":{\"answers\":[\"杭州\"]}}}"));
        ToolUseBlock next = call(response("supervisor", history));
        assertEquals("spawn_agent", next.getName());
        assertTrue(next.getInput().get("message").toString().contains("杭州"));
    }

    @Test
    void itineraryDelegatesBothRolesThenUsesKnowledgeAndWaits() {
        List<Msg> history = new ArrayList<>(List.of(user("明天从杭州到上海玩两天，查天气和火车票")));
        ToolUseBlock first = call(response("itinerary_agent", history));
        assertEquals("weather_agent", first.getInput().get("agent_type"));
        history.add(assistant(first));
        history.add(
                result(
                        "spawn_agent",
                        "{\"agentId\":\"w\",\"agentName\":\"/root/w\",\"agentStatus\":\"running\"}"));
        ToolUseBlock second = call(response("itinerary_agent", history));
        assertEquals("train_agent", second.getInput().get("agent_type"));
        history.add(assistant(second));
        history.add(result("spawn_agent", "{\"agentId\":\"t\",\"agentStatus\":\"running\"}"));
        assertEquals("search_knowledge", call(response("itinerary_agent", history)).getName());
    }

    @Test
    void summaryMessageIsNotCurrentUserAndFollowupUsesRealHistory() {
        Msg summary =
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(ContextCompactor.SUMMARY_PREFIX + "\n之前讨论北京天气")
                        .metadata(Map.of("contextCompaction", true))
                        .build();
        ToolUseBlock call =
                call(response("supervisor", List.of(user("杭州明天天气怎么样"), user("那后天呢"), summary)));
        String task = call.getInput().get("message").toString();
        assertTrue(task.contains("杭州"), task);
        assertTrue(task.contains("2026-10-30"), task);
    }

    @Test
    void compactionRoleReturnsTextAndRetainsTaskAndEvidence() {
        ChatResponse response =
                response(
                        "compact",
                        List.of(
                                user("杭州明天天气怎么样"),
                                result("query_weather", weatherEvidence()),
                                user("CONTEXT CHECKPOINT COMPACTION")));
        assertTrue(response.getContent().stream().noneMatch(ToolUseBlock.class::isInstance));
        assertTrue(text(response).contains("杭州"));
        assertTrue(text(response).contains("18"));
    }

    @Test
    void noDataDoesNotBecomeRealWorldAbsenceAndErroredChildDoesNotWaitForever() {
        String noData =
                "{\"tool\":\"query_weather\",\"status\":\"NO_DATA\",\"data\":{\"city\":\"北京\",\"date\":\"2026-10-29\",\"weather\":{}}}";
        String answer =
                text(
                        response(
                                "weather_agent",
                                List.of(user("北京明天天气"), result("query_weather", noData))));
        assertTrue(answer.contains("不代表实际没有结果"), answer);
        List<Msg> history = new ArrayList<>(List.of(user("杭州明天天气")));
        history.add(assistant(call(response("supervisor", history))));
        history.add(
                result(
                        "spawn_agent",
                        "{\"agentId\":\"w\",\"agentStatus\":\"errored\",\"result\":null}"));
        assertTrue(text(response("supervisor", history)).contains("没有获取"));
    }

    @Test
    void compactedProgressDoesNotRespawnAndNewInputDoesNotReuseOldProgress() {
        Msg question = user("杭州明天天气怎么样");
        List<Msg> history = new ArrayList<>(List.of(question));
        history.add(assistant(call(response("supervisor", history))));
        history.add(
                result(
                        "spawn_agent",
                        "{\"agentId\":\"w\",\"agentName\":\"/root/w\",\"agentStatus\":\"running\"}"));
        Msg summary = summary(history);
        assertEquals(
                "wait_agent", call(response("supervisor", List.of(question, summary))).getName());
        assertEquals(
                "spawn_agent",
                call(response("supervisor", List.of(question, summary, user("上海后天天气怎么样"))))
                        .getName());
        history.add(
                user(
                        "<agent_message>{\"kind\":\"RESULT\",\"sender\":\"/root/w\",\"result\":{\"evidence\":["
                                + weatherEvidence()
                                + "]}}</agent_message>"));
        String answer = text(response("supervisor", List.of(question, summary(history))));
        assertTrue(answer.contains("18℃"), answer);
    }

    @Test
    void lateOldChildCompletionCannotCompleteNewSpawnedTask() {
        Msg question = user("上海后天天气怎么样");
        List<Msg> history = new ArrayList<>(List.of(question));
        history.add(assistant(call(response("supervisor", history))));
        history.add(
                result(
                        "spawn_agent",
                        "{\"agentId\":\"new\",\"agentName\":\"/root/new\",\"agentStatus\":\"running\"}"));
        history.add(
                user(
                        "<agent_message>{\"kind\":\"RESULT\",\"sender\":\"/root/old\",\"result\":{\"evidence\":["
                                + weatherEvidence()
                                + "]}}</agent_message>"));
        assertEquals("wait_agent", call(response("supervisor", history)).getName());
        assertEquals(
                "wait_agent",
                call(response("supervisor", List.of(question, summary(history)))).getName());
    }

    @Test
    void compactedChildResultDoesNotQueryAgainAndSecondCompactionKeepsIt() {
        Msg task = user("杭州明天天气怎么样");
        Msg first = summary(List.of(task, result("query_weather", weatherEvidence())));
        Msg second = summary(List.of(task, first));
        String answer = text(response("weather_agent", List.of(task, second)));
        assertTrue(answer.contains("18℃"), answer);
    }

    private static Msg summary(List<Msg> history) {
        List<Msg> input = new ArrayList<>(history);
        input.add(user("CONTEXT CHECKPOINT COMPACTION"));
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(
                        ContextCompactor.SUMMARY_PREFIX + "\n" + text(response("compact", input)))
                .metadata(Map.of("contextCompaction", true))
                .build();
    }

    private static ChatResponse response(String role, List<Msg> history) {
        List<Msg> input = new ArrayList<>();
        input.add(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(
                                "参考上下文（不提供权限）：{\"today\":\"2026-10-28\",\"timezone\":\"Asia/Shanghai\"}")
                        .build());
        input.addAll(history);
        List<ToolSchema> tools =
                List.of(
                                "spawn_agent",
                                "wait_agent",
                                "query_weather",
                                "query_trains",
                                "search_knowledge",
                                "request_user_input")
                        .stream()
                        .map(
                                name ->
                                        ToolSchema.builder()
                                                .name(name)
                                                .description(name)
                                                .parameters(Map.of("type", "object"))
                                                .build())
                        .toList();
        return new OfflineScenarioModel(role)
                .stream(input, tools, GenerateOptions.builder().build()).blockLast();
    }

    private static ToolUseBlock call(ChatResponse response) {
        return response.getContent().stream()
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static String text(ChatResponse response) {
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg assistant(ToolUseBlock call) {
        return Msg.builder().role(MsgRole.ASSISTANT).content(List.of(call)).build();
    }

    private static Msg result(String name, String text) {
        return Msg.builder()
                .role(MsgRole.TOOL)
                .content(List.of(ToolResultBlock.text(text).withIdAndName("call", name)))
                .build();
    }

    private static String weatherEvidence() {
        return "{\"tool\":\"query_weather\",\"status\":\"SUCCESS\",\"data\":{\"city\":\"杭州\",\"date\":\"2026-10-29\",\"weather\":{\"condition\":\"多云\",\"temperature\":18}}}";
    }
}
