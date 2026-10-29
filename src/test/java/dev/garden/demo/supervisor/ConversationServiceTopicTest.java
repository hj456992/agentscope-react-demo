package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.service.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

/** 话题切换只改变历史装配与权限范围，不删除已有会话行。 */
class ConversationServiceTopicTest {
    private final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private final TestRepository repository = new TestRepository(url);
    private final ConversationRepository conversationRepository =
            new ConversationRepository(repository.repository().database(), repository.repository().codec());
    private final IdentityDTO alice =
            new IdentityDTO("tenant", "alice", Set.of("query_weather", "query_trains"));

    @AfterEach
    void close() {
        repository.close();
    }

    @Test
    void switchingTopicArchivesOldQuestionsAndDropsOldPermissions() {
        ConversationService service = service(new SupervisorPolicy.TopicPolicy(true, 6, 0.6));
        complete(service, "杭州明天天气", "晴", "run-1");
        complete(service, "上海明天天气", "多云", "run-2");
        RequestDTO current = request("查一下去上海的火车票");
        ConversationContextDTO context = service.enrichWithPermissions(alice, current);
        String history = context.request().history().toString();
        assertTrue(history.contains("上一话题已归档；仅保留用户问题，不提供当前条件"));
        assertTrue(history.contains("杭州明天天气"));
        assertTrue(history.contains("上海明天天气"));
        assertFalse(history.contains("[服务端历史用户原文"), "旧话题不再提供详细历史");
        assertFalse(history.contains("晴"), "归档只保留用户问题，不保留旧回答");
        assertTrue(context.requiredPermissions().isEmpty(), "旧话题的权限不进入当前话题");
        assertEquals(
                2,
                conversationRepository.turns("tenant", "alice", "session").size(),
                "切换话题不删除会话行");
    }

    @Test
    void continuingSameTopicKeepsExistingHistoryAndPermissions() {
        ConversationService service = service(new SupervisorPolicy.TopicPolicy(true, 6, 0.6));
        complete(service, "杭州明天天气", "晴", "run-1");
        complete(service, "上海明天天气", "多云", "run-2");
        RequestDTO current = request("杭州后天天气");
        ConversationContextDTO context = service.enrichWithPermissions(alice, current);
        String history = context.request().history().toString();
        assertFalse(history.contains("上一话题已归档"));
        assertEquals(2, count(history, "[服务端历史用户原文"));
        assertEquals(Set.of("query_weather"), context.requiredPermissions());
    }

    @Test
    void disabledPolicyKeepsExistingHistoryAssembly() {
        ConversationService service = service(SupervisorPolicy.defaults().topic());
        complete(service, "杭州明天天气", "晴", "run-1");
        RequestDTO current = request("查一下去上海的火车票");
        ConversationContextDTO context = service.enrichWithPermissions(alice, current);
        String history = context.request().history().toString();
        assertFalse(history.contains("上一话题已归档"));
        assertEquals(Set.of("query_weather"), context.requiredPermissions());
    }

    private ConversationService service(SupervisorPolicy.TopicPolicy policy) {
        return new ConversationService(
                conversationRepository, Duration.ofDays(30), Duration.ofDays(90), policy);
    }

    private void complete(
            ConversationService service, String question, String answer, String runId) {
        service.complete(alice, request(question), result(answer), runId, Set.of("query_weather"));
    }

    private static RequestDTO request(String question) {
        return new RequestDTO("session", question, List.of(), UUID.randomUUID().toString());
    }

    private static ResultDTO result(String answer) {
        return new ResultDTO(
                Status.SUCCESS,
                "question",
                "rewritten",
                Intent.WEATHER,
                answer,
                List.of(),
                List.of(),
                List.of());
    }

    private static int count(String text, String part) {
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }
}
