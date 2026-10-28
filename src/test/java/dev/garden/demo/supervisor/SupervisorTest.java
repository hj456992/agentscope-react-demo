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

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

/** 只替换远程模型，编排、子 Agent 和查询工具都真实运行。 */
class SupervisorTest {
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private ResultDTO run(String question) {
        return Supervisor.offline(clock).run(new RequestDTO("会话甲", question, List.of())).block();
    }

    @Test
    void weatherUsesActualToolResult() {
        ResultDTO result = run("杭州明天的天气");
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(List.of("天气查询"), result.agents());
        assertEquals("杭州", result.evidence().get(0).data().get("city"));
        assertEquals("2026-10-29", result.evidence().get(0).data().get("date"));
        assertTrue(result.answer().contains("示例"));
        assertTrue(result.answer().contains("18"));
    }

    @Test
    void trainsUseTheRequestedRoute() {
        ResultDTO result = run("明天从杭州到上海的火车票");
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(List.of("火车票查询"), result.agents());
        assertEquals("杭州", result.evidence().get(0).data().get("from"));
        assertEquals("上海", result.evidence().get(0).data().get("to"));
        assertTrue(result.answer().contains("G-DEMO"));
    }

    @Test
    void itineraryActuallyDelegatesToBothAgents() {
        ResultDTO result = run("明天从杭州到上海玩一天，看看天气和火车票，规划行程");
        assertEquals(Status.SUCCESS, result.status());
        assertEquals(List.of("行程规划", "天气查询", "火车票查询"), result.agents());
        assertEquals(3, result.evidence().size());
        assertTrue(result.answer().contains("G-DEMO"));
        assertTrue(result.answer().contains("22"));
    }

    @Test
    void missingCityDoesNotExecuteTools() {
        ResultDTO result = run("明天天气怎么样");
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.agents().isEmpty());
    }

    @Test
    void followupUsesOnlyItsOwnHistory() {
        Supervisor supervisor = Supervisor.offline(clock);
        ResultDTO first =
                supervisor
                        .run(new RequestDTO("会话甲", "那后天呢", List.of(new TurnDTO("杭州明天的天气", "示例天气"))))
                        .block();
        ResultDTO other = supervisor.run(new RequestDTO("会话乙", "那后天呢", List.of())).block();
        assertEquals(Status.SUCCESS, first.status());
        assertEquals("杭州", first.evidence().get(0).data().get("city"));
        assertEquals("2026-10-30", first.evidence().get(0).data().get("date"));
        assertEquals(Status.NEEDS_INPUT, other.status());
    }

    @Test
    void unknownIntentDoesNotChooseAnAgent() {
        ResultDTO result = run("帮我写一首诗");
        assertEquals(Status.UNKNOWN_INTENT, result.status());
        assertTrue(result.agents().isEmpty());
    }

    @Test
    void absentFixtureIsNotReportedAsRealWorldUnavailability() {
        ResultDTO result = run("成都明天的天气");
        assertEquals(Status.NO_DATA, result.status());
        assertTrue(result.answer().contains("示例"));
        assertFalse(result.evidence().isEmpty());
    }

    @Test
    void blankInputFailsBeforeModelCalls() {
        ResultDTO result = run("  ");
        assertEquals(Status.FAILED, result.status());
        assertTrue(result.agents().isEmpty());
    }

    @Test
    void missingDepartureAndInvalidDateAskForClarification() {
        ResultDTO missing = run("明天到上海的火车票");
        assertEquals(Status.NEEDS_INPUT, missing.status());
        assertTrue(missing.answer().contains("出发城市"));
        ResultDTO result = run("杭州2026-02-30的天气");
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void concurrentRequestsKeepTheirOwnCityAndHistory() {
        Supervisor supervisor = Supervisor.offline(clock);
        var requests =
                List.of(
                        new RequestDTO("甲", "那后天呢", List.of(new TurnDTO("杭州明天的天气", "示例天气"))),
                        new RequestDTO("乙", "那后天呢", List.of(new TurnDTO("上海明天的天气", "示例天气"))));
        var results =
                reactor.core.publisher.Flux.fromIterable(requests)
                        .flatMap(
                                request ->
                                        supervisor
                                                .run(request)
                                                .subscribeOn(
                                                        reactor.core.scheduler.Schedulers
                                                                .parallel()))
                        .collectList()
                        .block();
        assertNotNull(results);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.status() == Status.SUCCESS));
        assertEquals(
                java.util.Set.of("杭州", "上海"),
                results.stream()
                        .map(result -> result.evidence().get(0).data().get("city"))
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void currentCityOverridesHistoricalCity() {
        ResultDTO result =
                Supervisor.offline(clock)
                        .run(new RequestDTO("甲", "那上海后天呢", List.of(new TurnDTO("杭州明天的天气", "示例天气"))))
                        .block();
        assertEquals(Status.SUCCESS, result.status());
        assertEquals("上海", result.evidence().get(0).data().get("city"));
        assertEquals("2026-10-30", result.evidence().get(0).data().get("date"));
    }

    @Test
    void unsupportedOfflineCorrectionAsksInsteadOfDiscardingConstraints() {
        ResultDTO result =
                Supervisor.offline(clock)
                        .run(
                                new RequestDTO(
                                        "甲",
                                        "那不要上海后天去北京呢",
                                        List.of(new TurnDTO("明天从杭州到上海玩一天", "示例安排"))))
                        .block();
        assertEquals(Status.NEEDS_INPUT, result.status());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void multiDayItineraryPreservesDuration() {
        ResultDTO result = run("明天从杭州到上海玩3天，看看天气和火车票，规划行程");
        assertEquals(Status.SUCCESS, result.status());
        assertTrue(result.answer().contains("第3天"));
        assertTrue(result.answer().contains("仅查询了出发日"));
    }

    @Test
    void trainFixtureContainsDurationSeatAndPrice() {
        ResultDTO result = run("明天从杭州到上海的火车票");
        assertEquals(Status.SUCCESS, result.status());
        assertTrue(result.answer().contains("60分钟"));
        assertTrue(result.answer().contains("二等座"));
        assertTrue(result.answer().contains("80"));
    }
}
