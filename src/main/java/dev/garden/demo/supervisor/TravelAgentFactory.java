package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import dev.garden.demo.supervisor.pojo.dto.BookingOutcomeDTO;
import dev.garden.demo.supervisor.pojo.dto.PromptDefinitionDTO;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.tool.Toolkit;

import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

/** 每次创建独立 Agent，避免不同用户或不同任务复用对话记忆。 */
final class TravelAgentFactory {
    private final RunContext context;
    private final Function<String, Model> models;
    private final TravelTools.DataSource data;

    TravelAgentFactory(
            RunContext context, Function<String, Model> models, TravelTools.DataSource data) {
        this.context = context;
        this.models = models;
        this.data = data;
    }

    ReActAgent stage(String name, String prompt) {
        return build(name, prompt, new Toolkit(), 2);
    }

    /**
     * 角色只提供提示词和工具集合；所有角色使用同一个显式模型循环。
     *
     * <p>提示词文本由 {@code RunControl.prompt} 从提示词注册表读取；实际版本和哈希同时
     * 写入任务检查点，运行详情据此核对，避免展示的版本与实际注入内容不一致。
     */
    String prompt(String role) {
        PromptDefinitionDTO definition = context.control.prompt(role);
        // 把实际注入的版本、哈希和来源固定到任务检查点；注册表后续发布不影响在途任务。
        context.control.checkpoint(
                "prompt:v1:" + definition.role(),
                definition.version()
                        + "|"
                        + definition.hash()
                        + "|"
                        + definition.promptId()
                        + "|"
                        + definition.source());
        return definition.content();
    }

    Map<String, AgentTool> tools(String role) {
        Map<String, AgentTool> result = new LinkedHashMap<>();
        TravelTools queries = new TravelTools(context, data);
        if (!role.equals("train_agent") && context.control.identity().allows("query_weather")) {
            result.put("query_weather", queryTool("query_weather", queries));
        }
        if (!role.equals("weather_agent") && context.control.identity().allows("query_trains")) {
            result.put("query_trains", queryTool("query_trains", queries));
        }
        if ((role.equals("supervisor") || role.equals("itinerary_agent"))
                && context.control.identity().allows("search_knowledge")) {
            result.put("search_knowledge", new KnowledgeService().tool(context));
        }
        if (role.equals("supervisor") && context.control.identity().allows("book_ticket")) {
            result.put("book_ticket", bookingTool());
        }
        return result;
    }

    /**
     * 写入工具：先登记审批，批准后才通过账本调用本地独立下游。
     *
     * <p>工具本身不保存操作状态；审批、账本和下游幂等键是唯一事实来源。相同操作号
     * 配不同参数会被拒绝，UNKNOWN 只查询不重放。
     */
    private AgentTool bookingTool() {
        return new AgentTool() {
            public String getName() {
                return "book_ticket";
            }

            public String getDescription() {
                return "按城市、日期、车次和乘客姓名创建本地示例订单；需要人工审批，未批准时返回 PENDING_APPROVAL";
            }

            public Map<String, Object> getParameters() {
                Map<String, Object> properties = new LinkedHashMap<>();
                for (String field :
                        List.of("from", "to", "date", "trainNumber", "passenger")) {
                    properties.put(
                            field,
                            Map.of("type", "string", "minLength", 1, "maxLength", 100));
                }
                return Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        List.of("from", "to", "date", "trainNumber", "passenger"),
                        "additionalProperties",
                        false);
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                Map<String, Object> input = param.getInput();
                String invalid = ToolValidator.validateInput(json(input), getParameters());
                if (invalid != null) {
                    context.correction(CorrectionType.INVALID_SCHEMA, "book_ticket");
                    return Mono.just(ToolResultBlock.error("INVALID_SCHEMA"));
                }
                Map<String, Object> parameters = new LinkedHashMap<>(input);
                // 操作号由运行和业务参数共同派生：同一任务重复提交同一订单得到同一个
                // 审批与账本键，参数变化则得到新操作号，不会把不同订单误当重试。
                String operationId =
                        "book:"
                                + context.control.runId()
                                + ":"
                                + RunContext.fingerprint(new TreeMap<>(parameters))
                                        .substring(0, 16);
                try {
                    BookingOutcomeDTO outcome = context.control.book(operationId, parameters);
                    context.event("写入工具审批状态：" + outcome.status());
                    ToolResultBlock text = ToolResultBlock.text(json(outcome));
                    if ("PENDING_APPROVAL".equals(outcome.status())) {
                        // 审批登记后本轮不再请求模型：模型无法自行批准，重复调用只会耗尽预算。
                        // 结束标记只在本轮生效，审批仍由人工在治理页完成。
                        return Mono.just(
                                ToolResultBlock.of(
                                        text.getOutput(),
                                        Map.of(
                                                "endTurn", true,
                                                "turnReply",
                                                pendingReply(outcome))));
                    }
                    return Mono.just(text);
                } catch (UnsupportedOperationException error) {
                    return Mono.just(ToolResultBlock.error("APPROVAL_UNAVAILABLE"));
                }
            }
        };
    }

    /** 审批登记后的固定说明；不含模型猜测的审批结论。 */
    private static String pendingReply(BookingOutcomeDTO outcome) {
        return "订单已提交并登记人工审批，当前状态为 PENDING_APPROVAL。"
                + "审批编号："
                + outcome.approvalId()
                + "。请在“平台治理 → 审批”中批准或拒绝；批准后才会通过本地独立下游创建示例订单。";
    }

    private ReActAgent build(String name, String prompt, Toolkit toolkit, int iterations) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(context.budgeted(models.apply(name)))
                .toolkit(toolkit)
                .generateOptions(
                        GenerateOptions.builder()
                                .maxTokens(context.limits.outputTokens())
                                .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                                .build())
                .maxIters(iterations)
                .maxRetries(1)
                .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                .middleware(new ToolGuard(context, toolkit, name))
                .enableMetaTool(false)
                .enableTaskList(false)
                .build();
    }

    private AgentTool queryTool(String name, TravelTools queries) {
        return new AgentTool() {
            public String getName() {
                return name;
            }

            public String getDescription() {
                return "按明确城市和 yyyy-MM-dd 日期查询本地示例数据，无数据会说明";
            }

            public Map<String, Object> getParameters() {
                List<String> fields =
                        name.equals("query_weather")
                                ? List.of("city", "date")
                                : List.of("from", "to", "date");
                Map<String, Object> properties = new LinkedHashMap<>();
                fields.forEach(
                        field ->
                                properties.put(
                                        field,
                                        Map.of(
                                                "type",
                                                "string",
                                                "minLength",
                                                1,
                                                "maxLength",
                                                100)));
                return Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        fields,
                        "additionalProperties",
                        false);
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                Map<String, Object> input = param.getInput();
                String invalid = ToolValidator.validateInput(json(input), getParameters());
                if (invalid != null) {
                    context.correction(name, "INVALID_SCHEMA");
                    return Mono.just(ToolResultBlock.error("INVALID_SCHEMA"));
                }
                if (name.equals("query_weather")) {
                    return queries.weatherAsync(
                            (String) input.get("city"), (String) input.get("date"));
                }
                return queries.trainsAsync(
                        (String) input.get("from"),
                        (String) input.get("to"),
                        (String) input.get("date"));
            }
        };
    }
}
