package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 多 Agent 模式：OFF 不变，ADVISORY 只记录，ENFORCE 执行深度与数量硬上限。 */
class MultiAgentRuntimeLimitTest {
    private static final LimitsDTO LIMITS = LimitsDTO.defaults();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void offModeKeepsExistingSpawnBehaviorAndWritesNoDecision() {
        AtomicInteger spawns = new AtomicInteger();
        ResultDTO result =
                run(
                        SupervisorPolicy.MultiAgentPolicy.off(),
                        "/root/a/b/c",
                        List.of(),
                        spawns);
        assertEquals(1, spawns.get());
        assertTrue(result.events().stream().noneMatch(event -> event.contains("多Agent决策")));
    }

    @Test
    void advisoryModeRecordsTheDecisionButStillCreatesTheChild() {
        AtomicInteger spawns = new AtomicInteger();
        ResultDTO result =
                run(
                        new SupervisorPolicy.MultiAgentPolicy(
                                SupervisorPolicy.MultiAgentPolicy.Mode.ADVISORY,
                                2,
                                4,
                                1,
                                2.0,
                                500,
                                false,
                                8),
                        "/root/a/b",
                        List.of(),
                        spawns);
        assertEquals(1, spawns.get());
        assertTrue(
                result.events().stream()
                        .anyMatch(
                                event ->
                                        event.contains("多Agent决策")
                                                && event.contains("DEPTH_LIMIT_EXCEEDED")));
    }

    @Test
    void enforceModeBlocksSpawnBeyondDepthLimitWithoutCreatingAChild() {
        AtomicInteger spawns = new AtomicInteger();
        AtomicReference<String> toolError = new AtomicReference<>("");
        ResultDTO result =
                run(
                        enforceable(1, 4),
                        "/root/a/b",
                        List.of(),
                        spawns,
                        toolError);
        assertEquals(0, spawns.get());
        assertTrue(toolError.get().contains("MULTI_AGENT_LIMIT"));
        assertTrue(result.events().stream().anyMatch(event -> event.contains("DEPTH_LIMIT_EXCEEDED")));
    }

    @Test
    void enforceModeBlocksSpawnWhenActiveChildrenReachAgentLimit() {
        AtomicInteger spawns = new AtomicInteger();
        AtomicReference<String> toolError = new AtomicReference<>("");
        List<AgentSnapshotDTO> agents =
                List.of(
                        new AgentSnapshotDTO(
                                "root", "/root", "supervisor", "running", null, null, Set.of()),
                        new AgentSnapshotDTO(
                                "child", "/root/weather_a", "weather_agent", "running", null, null,
                                Set.of()));
        ResultDTO result =
                run(enforceable(2, 1), "/root", agents, spawns, toolError);
        assertEquals(0, spawns.get());
        assertTrue(toolError.get().contains("MULTI_AGENT_LIMIT"));
        assertTrue(result.events().stream().anyMatch(event -> event.contains("AGENT_LIMIT_EXCEEDED")));
    }

    private static SupervisorPolicy.MultiAgentPolicy enforceable(int maxDepth, int maxAgents) {
        return new SupervisorPolicy.MultiAgentPolicy(
                SupervisorPolicy.MultiAgentPolicy.Mode.ENFORCE,
                2,
                maxAgents,
                maxDepth,
                2.0,
                500,
                false,
                8);
    }

    private static ResultDTO run(
            SupervisorPolicy.MultiAgentPolicy policy,
            String path,
            List<AgentSnapshotDTO> agents,
            AtomicInteger spawns) {
        return run(policy, path, agents, spawns, new AtomicReference<>(""));
    }

    private static ResultDTO run(
            SupervisorPolicy.MultiAgentPolicy policy,
            String path,
            List<AgentSnapshotDTO> agents,
            AtomicInteger spawns,
            AtomicReference<String> toolError) {
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role -> model(role, turns, toolError), CLOCK, LIMITS, TravelTools.demoData());
        RunControl base = RunControl.local(LIMITS);
        RunControl control =
                new SupervisorLoopTest.DelegatingControl(base) {
                    public SupervisorPolicy policy() {
                        return SupervisorPolicy.defaults().withMultiAgent(policy);
                    }

                    public String agentPath() {
                        return path;
                    }

                    public List<AgentSnapshotDTO> listAgents(String prefix) {
                        return agents;
                    }

                    public reactor.core.publisher.Mono<AgentSnapshotDTO> spawnAgent(
                            String operationId,
                            String taskName,
                            String message,
                            String role,
                            String fork,
                            Map<String, String> inherited) {
                        spawns.incrementAndGet();
                        return reactor.core.publisher.Mono.just(
                                new AgentSnapshotDTO(
                                        "child-1",
                                        "/root/weather",
                                        "weather_agent",
                                        "running",
                                        null,
                                        null,
                                        Set.of()));
                    }
                };
        return supervisor
                .run(new RequestDTO("multi-agent", "杭州天气", List.of()), control)
                .block();
    }

    private static Model model(
            String role, AtomicInteger turns, AtomicReference<String> toolError) {
        return new Model() {
            public String getModelName() {
                return "multi-agent-script";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(
                        () -> {
                            if (turns.getAndIncrement() == 0) {
                                return Flux.just(
                                        tool(
                                                "spawn_agent",
                                                Map.of(
                                                        "task_name",
                                                        "weather",
                                                        "message",
                                                        "杭州天气",
                                                        "agent_type",
                                                        "weather_agent",
                                                        "fork_turns",
                                                        "none")));
                            }
                            for (Msg message : messages) {
                                for (ToolResultBlock result :
                                        message.getContentBlocks(ToolResultBlock.class)) {
                                    if (result.getName().equals("spawn_agent")) {
                                        toolError.set(
                                                result.getOutput().stream()
                                                        .filter(TextBlock.class::isInstance)
                                                        .map(TextBlock.class::cast)
                                                        .map(TextBlock::getText)
                                                        .reduce("", String::concat));
                                    }
                                }
                            }
                            return Flux.just(text("完成"));
                        });
            }
        };
    }

    private static ChatResponse tool(String name, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call-1")
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
