package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.*;
import io.agentscope.core.tool.*;

import reactor.core.publisher.Flux;

import java.util.function.Function;

/** 模型选错名称或 schema 时，先记纠错额度，再交 SDK 把错误作为工具结果反馈。 */
final class ToolGuard implements MiddlewareBase {
    private final RunContext context;
    private final Toolkit toolkit;
    private final String role;

    ToolGuard(RunContext context, Toolkit toolkit, String role) {
        this.context = context;
        this.toolkit = toolkit;
        this.role = role;
    }

    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext runtime,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    context.check();
                    context.check();
                    for (var call : input.toolCalls()) {
                        if (call.getName().equals("generate_response") && role.equals("answer"))
                            continue;
                        AgentTool tool = toolkit.getTool(call.getName());
                        if (tool == null)
                            context.correction(role, "TOOL_NOT_FOUND: " + call.getName());
                        else if (ToolValidator.validateInput(
                                        json(call.getInput()), tool.getParameters())
                                != null)
                            context.correction(role, "INVALID_SCHEMA: " + call.getName());
                    }
                    return next.apply(input);
                });
    }
}
