package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;
import dev.garden.demo.supervisor.pojo.dto.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.util.JsonUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** 主子会话共用的模型—工具循环；会话生命周期归 RunControl，角色只决定提示词与工具。 */
@Component
public class Supervisor {
    private final Function<String, Model> models;
    private final Clock clock;
    private final LimitsDTO limits;
    private final TravelTools.DataSource data;

    /** 唯一构造器保存容器注入的模型、日期时钟、限额和示例数据源，不执行请求。 */
    public Supervisor(Function<String, Model> models, Clock clock, LimitsDTO limits, TravelTools.DataSource data) {
        this.models = Objects.requireNonNull(models);
        this.clock = Objects.requireNonNull(clock);
        this.limits = Objects.requireNonNull(limits);
        this.data = Objects.requireNonNull(data);
    }

    public static Supervisor offline(Clock clock) {
        return new Supervisor(OfflineScenarioModel::new, clock, LimitsDTO.defaults(), TravelTools.demoData());
    }

    public Mono<ResultDTO> run(RequestDTO request) {
        return Mono.defer(() -> run(request, RunControl.local(limits)));
    }

    /** 订阅时恢复会话并执行一轮。取消当前订阅不拥有已受理子会话的执行订阅。 */
    public Mono<ResultDTO> run(RequestDTO request, RunControl control) {
        return Mono.defer(() -> {
            control.bindRunner(this::run);
            RunContext context = new RunContext(request, limits, control);
            context.today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
            if (blank(request.sessionId()) || blank(request.question()) || request.question().length() > 2000) {
                return Mono.just(context.result(Status.FAILED, "请提供会话编号和不超过2000字的问题。"));
            }
            return Mono.defer(() -> {
                LoopConversation conversation = new LoopConversation(context);
                switch (control.agentRole()) {
                    case "weather_agent" -> context.agent("天气查询");
                    case "train_agent" -> context.agent("火车票查询");
                    case "itinerary_agent" -> context.agent("行程规划");
                    default -> { }
                }
                TravelAgentFactory factory = new TravelAgentFactory(context, models, data);
                Model model = context.budgeted(models.apply(control.agentRole()));
                return step(context, conversation, factory, model);
            }).timeout(control.remaining()).onErrorResume(error -> {
                context.rememberFailure(error);
                return Mono.just(context.result(context.failureStatus(), switch (context.failureStatus()) {
                    case BUDGET_EXHAUSTED -> "共享模型预算已耗尽，已完成结果保留。";
                    case CONTEXT_LIMIT -> "上下文压缩未完成，原始会话记录保留。";
                    case CANCELLED -> "当前轮次已中断。";
                    case TIMEOUT -> "当前轮次已超时。";
                    default -> "当前轮次未能完成，请查看执行记录。";
                }));
            });
        });
    }

    /** 配齐工具结果，在下一模型边界消费用户输入与邮箱；最终文本不被模板替换。 */
    private Mono<ResultDTO> step(RunContext context, LoopConversation conversation, TravelAgentFactory factory, Model model) {
        return Mono.defer(() -> {
            context.check();
            List<ToolUseBlock> pending = conversation.pendingCalls();
            if (conversation.replyStatus == Status.NEEDS_INPUT && !pending.isEmpty()) {
                List<String> answers = context.control.drainInputs();
                if (answers.isEmpty()) {
                    return Mono.just(context.result(Status.NEEDS_INPUT, conversation.replyText));
                }
                ToolUseBlock question = pending.get(0);
                conversation.replyStatus = null;
                conversation.replyText = null;
                appendResult(conversation, question, ToolResultBlock.text(json(userAnswers(question, answers))));
                return step(context, conversation, factory, model);
            }
            if (!pending.isEmpty()) {
                // 顺序工具独占；连续的只读查询可并发，结果写入会话时串行化。
                Map<String, AgentTool> registry = registry(context, conversation, factory);
                ToolUseBlock first = pending.get(0);
                if (parallel(first.getName())) {
                    List<ToolUseBlock> reads = pending.stream().takeWhile(call -> parallel(call.getName())).toList();
                    return Flux.fromIterable(reads).flatMap(call -> invoke(context, registry, call)
                            .doOnNext(result -> appendResult(conversation, call, result)), 4)
                            .then(Mono.defer(() -> step(context, conversation, factory, model)));
                }
                if (first.getName().equals("request_user_input") && valid(registry.get(first.getName()), first)) {
                    conversation.reply(Status.NEEDS_INPUT, questionText(first));
                    return Mono.just(context.result(Status.NEEDS_INPUT, conversation.replyText));
                }
                return invoke(context, registry, first).flatMap(result -> {
                    appendResult(conversation, first, result);
                    return step(context, conversation, factory, model);
                });
            }
            conversation.receiveInput();
            receiveMailbox(context, conversation);
            if (conversation.replyStatus != null) {
                return finish(context, conversation, factory, model);
            }
            Map<String, AgentTool> registry = registry(context, conversation, factory);
            List<ToolSchema> schemas = registry.values().stream().map(tool -> ToolSchema.builder()
                    .name(tool.getName()).description(tool.getDescription()).parameters(tool.getParameters()).build()).toList();
            String prompt = factory.prompt(context.control.agentRole());
            ContextCompactor compactor = new ContextCompactor(context, lazyCompactModel(context));
            return compactor.compactIfNeeded(prompt, conversation, schemas)
                    .then(Mono.defer(() -> sample(context, conversation, model, prompt, schemas)
                            .onErrorResume(error -> compactor.compactAfterOverflow(prompt, conversation, schemas, error)
                                    .then(sample(context, conversation, model, prompt, schemas)))))
                    .flatMap(reply -> {
                        validateReply(reply);
                        conversation.assistant(reply);
                        return step(context, conversation, factory, model);
                    });
        });
    }

    private Model lazyCompactModel(RunContext context) {
        return new Model() {
            public String getModelName() { return "compact"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> context.budgetedCompaction(models.apply("compact")).stream(messages, tools, options));
            }
        };
    }

    private Mono<Msg> sample(RunContext context, LoopConversation conversation, Model model, String prompt, List<ToolSchema> tools) {
        return Mono.defer(() -> {
            List<Msg> input = ContextBuilder.modelInput(prompt, context.request, context.today, conversation.messages, tools);
            GenerateOptions options = GenerateOptions.builder().maxTokens(limits.outputTokens())
                    .executionConfig(ExecutionConfig.builder().maxAttempts(1).build()).build();
            return model.stream(input, tools, options).collectList().map(responses -> {
                if (responses.isEmpty() || responses.stream().anyMatch(r -> "length".equals(r.getFinishReason()))) {
                    throw new IllegalStateException("模型响应未完整结束");
                }
                return Msg.builder().role(MsgRole.ASSISTANT).content(responses.stream()
                        .flatMap(response -> response.getContent().stream())
                        .filter(block -> block instanceof TextBlock || block instanceof ToolUseBlock).toList()).build();
            });
        });
    }

    private static void validateReply(Msg reply) {
        List<ToolUseBlock> calls = reply.getContentBlocks(ToolUseBlock.class);
        Set<String> ids = new HashSet<>();
        for (ToolUseBlock call : calls) {
            if (blank(call.getId()) || !ids.add(call.getId()) || blank(call.getName())) {
                throw new IllegalArgumentException("工具调用编号缺失或重复，无法配对结果");
            }
        }
        if (calls.isEmpty() && blank(reply.getTextContent())) {
            throw new IllegalArgumentException("模型未返回文本或工具请求");
        }
    }

    private Mono<ToolResultBlock> invoke(RunContext context, Map<String, AgentTool> registry, ToolUseBlock call) {
        return Mono.defer(() -> {
            context.check();
            AgentTool tool = registry.get(call.getName());
            if (tool == null) {
                context.correction(context.control.agentRole(), "TOOL_NOT_FOUND: " + call.getName());
                return Mono.just(ToolResultBlock.error("TOOL_NOT_FOUND：请选择当前注册工具。"));
            }
            if (!valid(tool, call)) {
                context.correction(context.control.agentRole(), "INVALID_SCHEMA: " + call.getName());
                return Mono.just(ToolResultBlock.error("INVALID_SCHEMA：请按工具定义修正。"));
            }
            return tool.callAsync(ToolCallParam.builder().input(call.getInput()).build());
        });
    }

    private static boolean valid(AgentTool tool, ToolUseBlock call) {
        return tool != null && ToolValidator.validateInput(json(call.getInput()), tool.getParameters()) == null;
    }

    private static boolean parallel(String name) {
        return Set.of("query_weather", "query_trains", "search_knowledge").contains(name);
    }

    private static void appendResult(LoopConversation conversation, ToolUseBlock call, ToolResultBlock result) {
        synchronized (conversation) {
            conversation.append(Msg.builder().role(MsgRole.TOOL)
                    .content(List.of(result.withIdAndName(call.getId(), call.getName()))).build());
        }
    }

    private Map<String, AgentTool> registry(RunContext context, LoopConversation conversation, TravelAgentFactory factory) {
        Map<String, AgentTool> result = new LinkedHashMap<>(factory.tools(context.control.agentRole()));
        result.putAll(new ChildAgentDispatcher(context, conversation).tools());
        result.put("request_user_input", questionTool());
        return result;
    }

    private static AgentTool questionTool() {
        return new AgentTool() {
            public String getName() { return "request_user_input"; }
            public String getDescription() { return "询问用户并等待真实回答，再继续当前会话。"; }
            public Map<String, Object> getParameters() {
                Map<String, Object> option = Map.of("type", "object", "properties", Map.of("label", Map.of("type", "string"), "description", Map.of("type", "string")), "required", List.of("label", "description"));
                Map<String, Object> item = Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string"), "header", Map.of("type", "string"), "question", Map.of("type", "string"), "options", Map.of("type", "array", "items", option)), "required", List.of("id", "header", "question"));
                return Map.of("type", "object", "properties", Map.of("questions", Map.of("type", "array", "items", item, "minItems", 1, "maxItems", 3)), "required", List.of("questions"), "additionalProperties", false);
            }
            public Mono<ToolResultBlock> callAsync(ToolCallParam input) { return Mono.error(new IllegalStateException("询问由会话等待边界处理")); }
        };
    }

    @SuppressWarnings("unchecked")
    private static String questionText(ToolUseBlock call) {
        return ((List<Map<String, Object>>) call.getInput().get("questions")).stream()
                .map(q -> Objects.toString(q.get("question"), "")).reduce((a,b) -> a + "\n" + b).orElse("");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userAnswers(ToolUseBlock call, List<String> answers) {
        // 多题可以提交标准 answers JSON；普通文本按本次提问的回答原样回传，不做语义猜测。
        if (answers.size() == 1) {
            try {
                Map<String, Object> parsed = JsonUtils.getJsonCodec().fromJson(answers.get(0), Map.class);
                if (parsed.get("answers") instanceof Map<?, ?>) {
                    return parsed;
                }
            } catch (RuntimeException ignored) { /* 普通文本不是 JSON。 */ }
        }
        List<Map<String, Object>> questions = (List<Map<String, Object>>) call.getInput().get("questions");
        if (questions.size() == 1) {
            return Map.of("answers", Map.of(questions.get(0).get("id"), Map.of("answers", answers)));
        }
        return Map.of("answers", Map.of(), "user_message", answers);
    }

    private boolean receiveMailbox(RunContext context, LoopConversation conversation) {
        List<AgentMessageDTO> messages = context.control.drainMailbox();
        for (AgentMessageDTO message : messages) {
            ResultDTO result = message.result();
            if (result != null) {
                if (Set.of(Status.FAILED, Status.TIMEOUT, Status.BUDGET_EXHAUSTED, Status.CONTEXT_LIMIT).contains(result.status())) {
                    context.childFailed(message.sender());
                } else {
                    context.childSucceeded(message.sender());
                }
                for (EvidenceDTO evidence : result.evidence()) {
                    if (!context.control.identity().allows(evidence.tool())) {
                        throw new RunControl.Cancelled("子结果权限已变化");
                    }
                    context.add(evidence);
                }
                result.agents().forEach(context::agent);
            }
            conversation.replyStatus = null;
            conversation.replyText = null;
            conversation.messages.add(Msg.builder().role(MsgRole.USER)
                    .textContent("<agent_message>" + json(message) + "</agent_message>")
                    .metadata(Map.of("agentMessage", true)).build());
        }
        if (!messages.isEmpty()) {
            // 整批消息与消费确认同时落盘，不能保存第一条就确认余下消息。
            conversation.save();
        }
        return !messages.isEmpty();
    }

    private Mono<ResultDTO> finish(RunContext context, LoopConversation conversation, TravelAgentFactory factory, Model model) {
        if (conversation.receiveInput() || receiveMailbox(context, conversation)) {
            return step(context, conversation, factory, model);
        }
        conversation.save();
        Status status = context.evidence().isEmpty() && context.failedCalls().isEmpty() ? Status.SUCCESS : context.outcome();
        return Mono.just(context.result(status, conversation.replyText));
    }
}
