package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 运行期工具快照固定，未知工具兜底不暴露内部目录。 */
class ToolSnapshotAndNotFoundTest {
    @Test
    void theToolSnapshotIsFixedAtTheStartOfTheTurn() {
        RunContext context =
                new RunContext(
                        new RequestDTO("snapshot", "查询", List.of()),
                        LimitsDTO.defaults(),
                        RunControl.local(LimitsDTO.defaults()));
        context.today = LocalDate.of(2026, 10, 29);
        context.freezeTools(Map.of("query_weather", tool("query_weather")));
        context.freezeTools(Map.of("query_trains", tool("query_trains")));
        assertEquals(Set.of("query_weather"), context.toolSnapshot());
        assertEquals(Set.of("query_weather"), context.frozenTools().keySet());
    }

    @Test
    void unknownToolFallbackDoesNotRevealTheInternalCatalogue() {
        SupervisorPolicy policy =
                SupervisorPolicy.defaults()
                        .withCorrection(new SupervisorPolicy.CorrectionPolicy(true, 2, 2, 2, 6));
        Model model =
                new Model() {
                    public String getModelName() {
                        return "unknown-tool-script";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.defer(
                                () ->
                                        Flux.just(
                                                tool(
                                                        "list_every_tool",
                                                        Map.of("query", "全部工具"))));
                    }
                };
        LimitsDTO limits = LimitsDTO.defaults();
        Supervisor supervisor =
                new Supervisor(
                        role -> model,
                        Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"), ZoneOffset.UTC),
                        limits,
                        TravelTools.demoData());
        ResultDTO result =
                supervisor
                        .run(
                                new RequestDTO("catalogue", "查询", List.of()),
                                RunControl.local(limits, policy))
                        .block();
        assertEquals(Status.FAILED, result.status());
        String combined = result.answer() + String.join("|", result.events());
        assertFalse(combined.contains("query_trains"));
        assertFalse(combined.contains("query_weather"));
        assertFalse(combined.contains("search_knowledge"));
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            public String getName() {
                return name;
            }

            public String getDescription() {
                return name;
            }

            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam input) {
                return Mono.just(ToolResultBlock.text("ok"));
            }
        };
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
}
