package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 计划器只校验并保存计划，不创建子会话；非法计划按固定原因拒绝。 */
class MultiAgentPlannerTest {
    private static final LimitsDTO LIMITS = LimitsDTO.defaults();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void independentTasksBecomeOneParallelBatchAndSaveThePlan() {
        RunContext context = context(policy(true, 2, 8, 2, 500));
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context)
                        .plan(
                                plan(
                                        task("weather", "weather_agent", "查询杭州明天天气"),
                                        task("train", "train_agent", "查询上海到杭州火车"),
                                        task("itinerary", "itinerary_agent", "安排三日行程")));
        assertEquals("MULTI_AGENT", plan.decision());
        assertEquals(List.of(List.of("weather", "train", "itinerary")), plan.batches());
        assertEquals(3, plan.parallelism());
        assertEquals(800, plan.latencySavingMs());
        assertEquals(0, plan.steps().get(0).get("batch"));
        assertTrue(plan.estimatedTokens() > 0);
        String saved = context.control.checkpoint(MultiAgentPlanner.PLAN_KEY);
        assertNotNull(saved);
        assertTrue(saved.contains("\"MULTI_AGENT\""));
        assertTrue(plan.persisted());
        assertEquals(MultiAgentPlanner.PLAN_KEY, plan.toMap().get("persistedKey"));
    }

    @Test
    void planToolContractStatesPersistedAndDoesNotDispatch() {
        RunContext context = context(policy(true, 2, 8, 2, 500));
        var tool = MultiAgentPlanner.tool(context);
        assertTrue(tool.getDescription().contains("写入任务检查点"));
        assertTrue(tool.getDescription().contains("不创建或派发子 Agent"));
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context)
                        .plan(
                                plan(
                                        task("weather", "weather_agent", "查询杭州天气"),
                                        task("train", "train_agent", "查询杭州火车"),
                                        task("itinerary", "itinerary_agent", "安排行程")));
        assertEquals("MULTI_AGENT", plan.decision());
        assertEquals(true, plan.toMap().get("persisted"));
        assertEquals(true, plan.toolResult().get("persisted"));
        assertFalse(plan.toolResult().toString().contains("objective"));
        assertEquals(
                "计划已写入检查点 plan:v1:current；按 batches 顺序调用 spawn_agent，同一批次的子任务可以并行。",
                plan.advice());
    }

    @Test
    void dependenciesSplitTasksIntoOrderedBatches() {
        RunContext context = context(policy(true, 1, 4, 2, 0));
        Map<String, Object> first = task("weather", "weather_agent", "查询杭州明天天气");
        Map<String, Object> second = task("itinerary", "itinerary_agent", "根据天气安排行程");
        second.put("depends_on", List.of("weather"));
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context).plan(plan(first, second));
        assertEquals("MULTI_AGENT", plan.decision());
        assertEquals(List.of(List.of("weather"), List.of("itinerary")), plan.batches());
        assertEquals(0, plan.steps().get(0).get("batch"));
        assertEquals(1, plan.steps().get(1).get("batch"));
    }

    @Test
    void duplicateOrUnknownTaskIsRejected() {
        RunContext context = context(policy(true, 2, 8, 2, 500));
        MultiAgentPlanner planner = new MultiAgentPlanner(context);
        MultiAgentPlanner.Plan duplicate =
                planner.plan(
                        plan(
                                task("weather", "weather_agent", "查询杭州天气"),
                                task("weather", "train_agent", "查询火车")));
        assertEquals("REJECTED", duplicate.decision());
        assertEquals("INVALID_PLAN", duplicate.reason());

        MultiAgentPlanner.Plan unknown =
                planner.plan(plan(task("hotel", "hotel_agent", "查询酒店")));
        assertEquals("REJECTED", unknown.decision());
        assertEquals("INVALID_PLAN", unknown.reason());
    }

    @Test
    void dependencyCycleIsRejected() {
        RunContext context = context(policy(true, 2, 8, 2, 500));
        Map<String, Object> weather = task("weather", "weather_agent", "查询杭州天气");
        Map<String, Object> train = task("train", "train_agent", "查询杭州火车");
        weather.put("depends_on", List.of("train"));
        train.put("depends_on", List.of("weather"));
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context).plan(plan(weather, train));
        assertEquals("REJECTED", plan.decision());
        assertEquals("DEPENDENCY_CYCLE", plan.reason());
    }

    @Test
    void planLargerThanConfiguredLimitIsRejected() {
        RunContext context = context(policy(true, 2, 8, 2, 500, 1));
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context)
                        .plan(
                                plan(
                                        task("weather", "weather_agent", "查询杭州天气"),
                                        task("train", "train_agent", "查询火车")));
        assertEquals("REJECTED", plan.decision());
        assertEquals("PLAN_TOO_LARGE", plan.reason());
    }

    @Test
    void missingSubtasksFallsBackToSingleAgent() {
        RunContext context = context(policy(true, 2, 8, 2, 500));
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context)
                        .plan(Map.of("goal", "查询杭州天气", "tasks", List.of()));
        assertEquals("SINGLE_AGENT", plan.decision());
        assertEquals("NO_SUBTASKS", plan.reason());
    }

    @Test
    void agentLimitExceededFallsBackToSingleAgent() {
        RunContext context = context(policy(true, 2, 2, 2, 500));
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context)
                        .plan(
                                plan(
                                        task("weather", "weather_agent", "查询杭州天气"),
                                        task("train", "train_agent", "查询火车"),
                                        task("itinerary", "itinerary_agent", "安排行程")));
        assertEquals("SINGLE_AGENT", plan.decision());
        assertEquals("AGENT_LIMIT_EXCEEDED", plan.reason());
    }

    @Test
    void identityWithoutRequiredToolCannotPlanThatAgent() {
        RunControl base = RunControl.local(LIMITS, policy(true, 2, 8, 2, 500));
        RunControl restricted =
                new SupervisorLoopTest.DelegatingControl(base) {
                    public IdentityDTO identity() {
                        return new IdentityDTO("local", "cli", Set.of("query_weather"));
                    }
                };
        RunContext context = new RunContext(request(), LIMITS, restricted);
        MultiAgentPlanner.Plan plan =
                new MultiAgentPlanner(context)
                        .plan(plan(task("train", "train_agent", "查询上海到杭州火车")));
        assertEquals("REJECTED", plan.decision());
        assertEquals("PERMISSION_DENIED", plan.reason());
    }

    @Test
    void planToolIsOnlyRegisteredWhenTheSwitchIsOn() {
        assertTrue(toolNames(policy(true, 2, 4, 2, 500)).contains("plan_agents"));
        assertFalse(toolNames(policy(false, 2, 4, 2, 500)).contains("plan_agents"));
    }

    private static Set<String> toolNames(SupervisorPolicy policy) {
        AtomicReference<Set<String>> seen = new AtomicReference<>(Set.of());
        Model model =
                new Model() {
                    public String getModelName() {
                        return "planner-tool-list";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages,
                            List<ToolSchema> tools,
                            GenerateOptions options) {
                        seen.set(
                                tools.stream()
                                        .map(ToolSchema::getName)
                                        .collect(java.util.stream.Collectors.toSet()));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("完成")
                                                                .build()))
                                        .finishReason("stop")
                                        .build());
                    }
                };
        Supervisor supervisor =
                new Supervisor(role -> model, CLOCK, LIMITS, TravelTools.demoData());
        supervisor
                .run(request(), RunControl.local(LIMITS, policy))
                .block();
        return seen.get();
    }

    private static RunContext context(SupervisorPolicy policy) {
        return new RunContext(request(), LIMITS, RunControl.local(LIMITS, policy));
    }

    private static RequestDTO request() {
        return new RequestDTO("planner", "规划杭州行程", List.of());
    }

    private static SupervisorPolicy policy(
            boolean planner,
            int minParallelism,
            int maxAgents,
            int maxDepth,
            long minLatencySavingMs) {
        return policy(
                planner,
                minParallelism,
                maxAgents,
                maxDepth,
                minLatencySavingMs,
                8);
    }

    private static SupervisorPolicy policy(
            boolean planner,
            int minParallelism,
            int maxAgents,
            int maxDepth,
            long minLatencySavingMs,
            int maxPlanTasks) {
        return SupervisorPolicy.defaults()
                .withMultiAgent(
                        new SupervisorPolicy.MultiAgentPolicy(
                                SupervisorPolicy.MultiAgentPolicy.Mode.ENFORCE,
                                minParallelism,
                                maxAgents,
                                maxDepth,
                                2.0,
                                minLatencySavingMs,
                                planner,
                                maxPlanTasks));
    }

    private static Map<String, Object> plan(Map<String, Object>... tasks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goal", "规划杭州三日行程");
        result.put("tasks", List.of(tasks));
        return result;
    }

    private static Map<String, Object> task(
            String name, String agentType, String objective) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_name", name);
        result.put("agent_type", agentType);
        result.put("objective", objective);
        result.put("depends_on", List.of());
        return result;
    }
}
