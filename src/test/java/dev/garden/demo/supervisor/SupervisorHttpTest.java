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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/** 经过 Spring 路由、编解码、控制器及实际 Agent 链；网络测试另见 SupervisorSocketIT。 */
class SupervisorHttpTest {
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

    private JsonNode ask(String question) throws Exception {
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

    @Test
    void weatherTrainsAndItineraryRunThroughHttpHandler() throws Exception {
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
        assertEquals("SUCCESS", ask("帮我写诗").path("status").asText());
        assertEquals("NO_DATA", ask("成都明天的天气").path("status").asText());
    }

    @Test
    void malformedAndMissingFieldsReturnBadRequest() throws Exception {
        for (String input :
                List.of(
                        "{",
                        "{}",
                        "{\"sessionId\":\"甲\",\"question\":\" \"}",
                        "{\"sessionId\":\"甲\",\"question\":123}",
                        "{\"sessionId\":\"甲\",\"question\":\"天气\",\"history\":[{}]}")) {
            var reply = web.post(input);
            assertEquals(400, reply.status(), reply.body());
            assertEquals("INVALID_REQUEST", mapper.readTree(reply.body()).path("code").asText());
        }
    }

    @Test
    void longQuestionAndLargeBodyHaveDifferentErrors() {
        assertEquals(400, web.post(json(new RequestDTO("甲", "字".repeat(2001), List.of()))).status());
        var reply = web.post(json(new RequestDTO("甲", "x".repeat(70_000), List.of())));
        assertEquals(413, reply.status(), reply.body());
    }

    @Test
    void singletonSupervisorKeepsConcurrentHistoriesSeparate() {
        assertSame(web.context.getBean(Supervisor.class), web.context.getBean(Supervisor.class));
        var replies =
                Flux.fromIterable(List.of("杭州", "上海"))
                        .flatMap(
                                city -> {
                                    String input =
                                            json(
                                                    new RequestDTO(
                                                            city,
                                                            "那后天呢",
                                                            List.of(
                                                                    new TurnDTO(
                                                                            city + "明天的天气",
                                                                            "示例天气"))));
                                    return web.exchange(input)
                                            .subscribeOn(Schedulers.parallel())
                                            .doOnNext(
                                                    reply -> {
                                                        assertEquals(200, reply.status());
                                                        assertTrue(
                                                                reply.body().contains(city),
                                                                reply.body());
                                                        assertFalse(
                                                                reply.body()
                                                                        .contains(
                                                                                city.equals("杭州")
                                                                                        ? "上海"
                                                                                        : "杭州"));
                                                    });
                                })
                        .collectList()
                        .block();
        assertEquals(2, replies.size());
    }
}
