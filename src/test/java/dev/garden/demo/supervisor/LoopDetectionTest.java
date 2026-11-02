package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.support.AgentCallGraph;
import dev.garden.demo.supervisor.support.ToolCallLoopGuard;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.ToolCallParam;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 重复工具调用和协作回边的终止路径。 */
class LoopDetectionTest {
    private static final SupervisorPolicy LOOP_POLICY =
            SupervisorPolicy.defaults()
                    .withLoop(new SupervisorPolicy.LoopPolicy(true, 3, true, false, 12, 0.85, 2));

    @Test
    void repeatedToolCallWarnsThenTerminates() {
        RunContext context = context(LOOP_POLICY);
        Map<String, Object> input = Map.of("city", "杭州", "date", "2026-10-29");
        for (int index = 1; index <= 3; index++) {
            assertEquals(
                    ToolCallLoopGuard.Decision.PROCEED,
                    context.checkToolLoop("query_weather", input));
        }
        assertEquals(
                ToolCallLoopGuard.Decision.WARN,
                context.checkToolLoop("query_weather", input));
        assertEquals(
                ToolCallLoopGuard.Decision.FAIL,
                context.checkToolLoop("query_weather", input));
    }

    @Test
    void differentArgumentsAndExemptToolsAreCountedSeparately() {
        RunContext context = context(LOOP_POLICY);
        Map<String, Object> first = Map.of("city", "杭州", "date", "2026-10-29");
        Map<String, Object> second = Map.of("city", "上海", "date", "2026-10-29");
        for (int index = 0; index < 3; index++) {
            assertEquals(
                    ToolCallLoopGuard.Decision.PROCEED,
                    context.checkToolLoop("query_weather", first));
        }
        assertEquals(
                ToolCallLoopGuard.Decision.PROCEED,
                context.checkToolLoop("query_weather", second));
        for (int index = 0; index < 10; index++) {
            assertEquals(
                    ToolCallLoopGuard.Decision.PROCEED,
                    context.checkToolLoop("spawn_agent", Map.of("task_name", "weather")));
        }
    }

    @Test
    void disabledPolicyDoesNotWriteCounters() {
        RunContext context = context(SupervisorPolicy.defaults());
        Map<String, Object> input = Map.of("city", "杭州", "date", "2026-10-29");
        for (int index = 0; index < 10; index++) {
            assertEquals(
                    ToolCallLoopGuard.Decision.PROCEED,
                    context.checkToolLoop("query_weather", input));
        }
        assertNull(context.control.checkpoint(ToolCallLoopGuard.KEY));
    }

    @Test
    void modelReceivesLoopErrorAndRunStopsOnNextRepeat() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "loop-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        return Flux.just(
                                tool(
                                        "query_weather",
                                        Map.of("city", "杭州", "date", "2026-10-29")));
                    }
                };
        LimitsDTO limits = LimitsDTO.defaults();
        Model compact =
                new Model() {
                    public String getModelName() {
                        return "compact-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(text("已查询天气，等待下一步。"));
                    }
                };
        Supervisor supervisor =
                new Supervisor(
                        role -> role.equals("compact") ? compact : model,
                        Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC),
                        limits,
                        new TravelTools.DataSource() {
                            public Map<String, Object> weather(String city, String date) {
                                reads.incrementAndGet();
                                return Map.of("temperature", 18, "condition", "多云");
                            }

                            public List<Map<String, Object>> trains(
                                    String from, String to, String date) {
                                return List.of();
                            }
                        });
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("loop", "查询杭州天气", List.of()),
                                RunControl.local(limits, LOOP_POLICY))
                        .block();
        assertEquals(Status.FAILED, result.status());
        // 相同参数的成功结果会复用既有证据，数据源只读一次；重复调用仍被计数并最终终止。
        assertEquals(1, reads.get());
        assertEquals(5, calls.get());
        assertTrue(
                result.events().stream().anyMatch(event -> event.contains("循环检测")));
        assertTrue(
                result.events().stream().anyMatch(event -> event.contains("复用已完成工具")));
    }

    @Test
    void followupThatClosesAnAgentCycleIsRejectedBeforeDelivery() {
        RunControl control = RunControl.local(LimitsDTO.defaults(), LOOP_POLICY);
        // 已存在 /root/weather -> /root 的边；再从根会话 followup 回去就会闭合成环。
        control.checkpoint(
                AgentCallGraph.KEY, json(Map.of("/root/weather", List.of("/root"))));
        RunContext context =
                new RunContext(
                        new RequestDTO("cycle", "查询", List.of()), LimitsDTO.defaults(), control);
        LoopConversation conversation = new LoopConversation(context);
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("followup_task")
                        .input(Map.of("target", "weather", "message", "再查一次"))
                        .build();
        ToolResultBlock result =
                new ChildAgentDispatcher(context, conversation).invoke(call).block();
        assertNotNull(result);
        assertTrue(output(result).contains("LOOP_DETECTED"));
        assertTrue(context.result(Status.RUNNING, "").events().stream()
                .anyMatch(event -> event.contains("LOOP_DETECTED")));
    }

    private static RunContext context(SupervisorPolicy policy) {
        LimitsDTO limits = LimitsDTO.defaults();
        return new RunContext(
                new RequestDTO("loop", "查询", List.of()),
                limits,
                RunControl.local(limits, policy));
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

    private static String output(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}
