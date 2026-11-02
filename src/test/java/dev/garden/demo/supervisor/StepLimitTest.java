package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;

/** 总步数上限必须按回合计数，并在超过后返回预算终态。 */
class StepLimitTest {
    @Test
    void stepBudgetStopsAfterConfiguredNumberOfBoundaries() {
        SupervisorPolicy policy =
                SupervisorPolicy.defaults()
                        .withExecution(new SupervisorPolicy.ExecutionPolicy(
                                true,
                                2,
                                SupervisorPolicy.ExecutionPolicy.DEFAULT_CONTEXT_INPUT_LIMIT));
        LimitsDTO limits = LimitsDTO.defaults();
        RunControl control = RunControl.local(limits, policy);
        RunContext context =
                new RunContext(new RequestDTO("steps", "查询", List.of()), limits, control);
        context.beginStep();
        context.beginStep();
        assertThrows(RunControl.BudgetExceeded.class, context::beginStep);
        assertEquals("2", control.checkpoint("step:v1:" + control.revision()));
    }

    @Test
    void disabledStepBudgetDoesNotWriteCheckpoints() {
        LimitsDTO limits = LimitsDTO.defaults();
        RunControl control = RunControl.local(limits);
        RunContext context =
                new RunContext(new RequestDTO("steps", "查询", List.of()), limits, control);
        for (int index = 0; index < 100; index++) {
            context.beginStep();
        }
        assertNull(control.checkpoint("step:v1:" + control.revision()));
    }

    @Test
    void supervisorStopsRunWhenStepBudgetIsExhausted() {
        SupervisorPolicy policy =
                SupervisorPolicy.defaults()
                        .withExecution(new SupervisorPolicy.ExecutionPolicy(
                                true,
                                1,
                                SupervisorPolicy.ExecutionPolicy.DEFAULT_CONTEXT_INPUT_LIMIT));
        LimitsDTO limits = LimitsDTO.defaults();
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "step-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (calls.getAndIncrement() == 0) {
                            return Flux.just(
                                    tool(
                                            "query_weather",
                                            Map.of("city", "杭州", "date", "2026-10-29")));
                        }
                        return Flux.just(text("杭州今天多云，18℃。"));
                    }
                };
        Supervisor supervisor =
                new Supervisor(
                        role -> model,
                        Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC),
                        limits,
                        TravelTools.demoData());
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("steps", "杭州天气", List.of()),
                                RunControl.local(limits, policy))
                        .block();
        assertEquals(Status.BUDGET_EXHAUSTED, result.status());
        assertTrue(
                result.events().stream().anyMatch(event -> event.contains("预算停止")));
    }

    private static ChatResponse tool(String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(UUID.randomUUID().toString())
                                        .name(name)
                                        .input(input)
                                        .content(json(input))
                                        .build()))
                .finishReason("tool_calls")
                .build();
    }

    private static ChatResponse text(String value) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(value).build()))
                .finishReason("stop")
                .build();
    }
}
