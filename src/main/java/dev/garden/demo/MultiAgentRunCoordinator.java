package dev.garden.demo;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

/**
 * 多 Agent 编排的独立示例：只回答两个问题，核心语义按 Codex / Claude 公开源码实现。
 *
 * <h2>这个类不是 supervisor 包的一部分</h2>
 * 它独立放在 {@code dev.garden.demo}，不修改、不依赖 {@code dev.garden.demo.supervisor}。
 * 当前 Boot 应用的扫描范围只有 {@code dev.garden.demo.supervisor}，所以本类默认不会被装配；
 * 需要接入时，在启动类上加 {@code @ComponentScan("dev.garden.demo")} 或 {@code @Import} 本类，
 * 并提供一个 {@code Function<String, Model>} Bean（按角色返回模型）。
 *
 * <h2>问题一：子 Agent 执行中挂了</h2>
 * 上游依据（Codex rust-v0.165.0，提交 360319d8）：
 * <ul>
 *   <li>{@code codex-rs/protocol/src/error.rs retry_delay(retry_count)}：可重试错误优先使用
 *       服务端建议时间，否则指数退避 + 抖动；{@code TurnAborted}、{@code Interrupted}、
 *       {@code QuotaExceeded}、{@code ContextWindowExceeded}、{@code InvalidRequest}、
 *       {@code Sandbox} 等不可重试。</li>
 *   <li>{@code codex-rs/core/src/agent/control.rs}：子 Agent 是独立 thread/task，
 *       父任务取消不会自动撤销已受理的子任务；完成结果通过通知回到父线程。</li>
 * </ul>
 * 本类的实现边界：
 * <ol>
 *   <li>重试发生在<b>单次模型请求</b>层（{@link RetryingModel}），不是把整个子 Agent 重跑一遍；
 *       这是 Codex 的语义。已经收到部分响应流的请求不重放。</li>
 *   <li>子任务最终失败时只返回结构化结果，不中断其他子任务；由上层决定是否重做。
 *       本类没有实现"自动重跑整个子 Agent"，因为上游没有这个行为。</li>
 *   <li>本 Demo 的子调用是所属 turn 的内联调用，显式取消会传播到它们；Codex 的独立子 Agent
 *       平台不会。这个差异在注释和结果里明确标出，不伪装成上游行为。</li>
 * </ol>
 *
 * <h2>问题二：执行中用户来了新指令</h2>
 * 上游依据：
 * <ul>
 *   <li>Codex {@code session/turn_input.rs start_or_steer / steer / steer_input}：
 *       有活动轮次就把输入追加到同一轮次，没有才新建；显式 steer 校验预期轮次号。</li>
 *   <li>Codex {@code session/input_queue.rs}：输入进入 {@code pending_input} 队列，
 *       在下一个步骤边界整体取出；存在用户输入时取消当前 sampling 的 interrupt。</li>
 *   <li>Claude Agent SDK {@code client.py / _internal/query.py}：{@code interrupt()} 发送
 *       {@code {"subtype":"interrupt"}} 的 control_request，并等待 control_response（默认 60 秒）。
 *       本 Demo 不实现这套 JSON 控制协议，而是调用 AgentScope 的
 *       {@code ReActAgent.interrupt(RuntimeContext)}；语义相同——请求中断，agent 在下一个
 *       检查点停下。这是框架适配，不是 Claude 协议本身。</li>
 * </ul>
 * 本类的实现边界：
 * <ol>
 *   <li>新输入先入队，返回 {@code STEERED}，不新建 turn；在下一个子任务边界消费。</li>
 *   <li>如果当前有子任务在跑，打断当前 sampling，并让该子任务在边界重做一次，
 *       把新输入写进下一次提示词。Codex 是把输入追加到同一轮次的后续模型步骤；
 *       这里是"重做当前子任务"的业务适配。</li>
 *   <li>取消是协作式：先失效当前代次并落终态，再中断正在跑的 agent；
 *       最多等待 {@link #gracefulInterruptMs} 毫秒，超时才释放订阅（硬取消）。</li>
 * </ol>
 *
 * <h2>哪些不是上游语义</h2>
 * 固定任务清单由调用方提供、turn 状态名、结果摘要措辞、子任务边界重做、取消传播到内联子调用，
 * 都是本 Demo 的业务适配。这里没有熔断、换新会话重派、独立验收 Agent、关键词意图分类，
 * 因为它们没有对应的 Claude/Codex 实现。
 *
 * <h2>运行</h2>
 * <pre>
 * mvn -q package
 * java -Dfile.encoding=UTF-8 -cp target/agentscope-react-demo.jar dev.garden.demo.MultiAgentRunCoordinator
 * </pre>
 */
@Service
public class MultiAgentRunCoordinator {

    private static final Pattern ROLE = Pattern.compile("\\[role:([^\\]]+)]");
    private static final Duration DEFAULT_AWAIT = Duration.ofSeconds(20);

    /** 单个模型请求最多几次尝试（含首次）。这是业务配置，不是上游默认值。 */
    @Value("${demo.coordinator.model-attempts:3}")
    private int modelAttempts = 3;

    /** 整个 turn 的模型调用总预算，对应 Codex 根任务共享预算的简化版。 */
    @Value("${demo.coordinator.max-model-calls:16}")
    private int maxModelCalls = 16;

    /** 取消后的优雅等待时间；Codex 的 handle_task_abort 用 100 ms。 */
    @Value("${demo.coordinator.graceful-interrupt-ms:100}")
    private long gracefulInterruptMs = 100;

    /** turn 的总时限。 */
    @Value("${demo.coordinator.turn-timeout-ms:60000}")
    private long turnTimeoutMs = 60_000;

    /** 同时跑几个子任务。 */
    @Value("${demo.coordinator.max-parallel-children:2}")
    private int maxParallelChildren = 2;

    /** 同一个子任务因为新输入最多重做几次，防止用户连续插话形成死循环。 */
    @Value("${demo.coordinator.max-steer-replans:3}")
    private int maxSteerReplans = 3;

    private final Function<String, Model> models;
    private final Map<String, Turn> turns = new ConcurrentHashMap<>();

    public MultiAgentRunCoordinator(Function<String, Model> models) {
        this.models = Objects.requireNonNull(models, "models");
    }

    // ==================================================================================
    // 1. 对外入口：启动、追加输入、取消、读结果
    // ==================================================================================

    /**
     * 启动一个 turn。任务清单由调用方（planner）提供——本类不假装自己是 planner，
     * 上游也没有一个叫"自动拆任务"的函数。
     *
     * <p>返回后执行已经在后台开始，调用方通过 {@link #await} 或后续 HTTP 轮询拿结果。
     */
    public TurnView start(String userId, String question, List<ChildTask> tasks) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(tasks, "tasks");
        Turn turn = new Turn(
                UUID.randomUUID().toString(),
                userId,
                question,
                List.copyOf(tasks),
                maxModelCalls,
                turnTimeoutMs);
        turns.put(turn.turnId, turn);

        Mono<TurnResult> execution =
                execute(turn)
                        .doOnNext(result -> turn.result = result)
                        .doFinally(
                                signal -> {
                                    // 强制取消时可能没有正常结果，兜底生成一份，保证调用方能读到状态。
                                    if (turn.result == null) turn.result = finish(turn);
                                    turn.done.complete(null);
                                });
        turn.subscription =
                execution.subscribe(null, error -> turn.event("执行异常：" + summarize(error)));
        return view(turn);
    }

    /**
     * 用户执行中追加输入。
     *
     * <p>对照 Codex {@code turn_input.rs start_or_steer}：有活动 turn 就追加，不新建；
     * 期望轮次号不匹配时拒绝。本方法只排队并打断当前 sampling，真正的消费发生在
     * {@link #boundary}。
     */
    public InstructionReply steer(String turnId, String input, int expectedRevision) {
        Turn turn = turns.get(turnId);
        if (turn == null) {
            return InstructionReply.rejected(turnId, 0, "未找到 turn");
        }
        if (input == null || input.isBlank()) {
            return InstructionReply.rejected(turnId, turn.revision.get(), "补充输入不能为空");
        }

        boolean inFlight;
        synchronized (turn) {
            if (turn.state != TurnState.RUNNING) {
                return InstructionReply.rejected(turnId, turn.revision.get(), "turn 已结束");
            }
            if (turn.revision.get() != expectedRevision) {
                return InstructionReply.rejected(
                        turnId, turn.revision.get(), "版本已变化，请读取最新状态后重试");
            }
            turn.pendingInputs.addLast(input);
            turn.revision.incrementAndGet();
            // 有输入待处理时，取消当前 sampling 的 interrupt；这是 Codex watch_user_input 的行为。
            turn.interruptReason = InterruptReason.STEER;
            inFlight = !turn.inFlight.isEmpty();
        }

        if (inFlight) {
            turn.signal(TurnSignal.STEER);
            for (InFlight flight : turn.inFlight) {
                // SDK 的协作中断：agent 在下一个检查点停下，不会掐断正在飞的 HTTP 请求。
                flight.agent.interrupt(flight.context);
            }
        }
        return new InstructionReply(
                turnId,
                turn.revision.get(),
                turn.state.name(),
                "STEERED",
                "新输入已排队，将在下一个步骤边界处理");
    }

    /**
     * 显式取消。
     *
     * <p>对照 Codex {@code tasks/mod.rs abort_all_tasks / handle_task_abort}：
     * 先失效旧代次并落终态，再发取消信号；最多等待 {@code GRACEFULL_INTERRUPTION_TIMEOUT_MS}
     * （100 ms），超时才强制释放订阅。已经完成的子任务结果保留。
     */
    public CancelReply cancel(String turnId, int expectedRevision) {
        Turn turn = turns.get(turnId);
        if (turn == null) {
            return CancelReply.rejected(turnId, 0, "未找到 turn");
        }

        List<InFlight> inFlight;
        synchronized (turn) {
            if (turn.revision.get() != expectedRevision) {
                return CancelReply.rejected(turnId, turn.revision.get(), "版本已变化");
            }
            if (turn.state != TurnState.RUNNING) {
                return new CancelReply(
                        turnId, turn.revision.get(), turn.state.name(), true, false, completed(turn), "turn 已结束");
            }
            turn.state = TurnState.CANCELLED;
            turn.revision.incrementAndGet();
            turn.generation.incrementAndGet();
            turn.interruptReason = InterruptReason.CANCEL;
            inFlight = List.copyOf(turn.inFlight);
        }

        turn.signal(TurnSignal.CANCEL);
        for (InFlight flight : inFlight) {
            flight.agent.interrupt(flight.context);
        }

        boolean acknowledged;
        try {
            turn.done.get(gracefulInterruptMs, TimeUnit.MILLISECONDS);
            acknowledged = true;
        } catch (TimeoutException timeout) {
            acknowledged = false;
        } catch (Exception interrupted) {
            Thread.currentThread().interrupt();
            acknowledged = false;
        }
        if (!acknowledged && turn.subscription != null) {
            // 兜底硬取消。底层阻塞 I/O 是否真的停下不在本类保证范围内。
            turn.subscription.dispose();
        }
        return new CancelReply(
                turnId,
                turn.revision.get(),
                "CANCELLED",
                acknowledged,
                !acknowledged,
                completed(turn),
                acknowledged ? "已在宽限期内停止" : "宽限期已过，已释放订阅");
    }

    public TurnView view(String turnId) {
        Turn turn = turns.get(turnId);
        if (turn == null) throw new IllegalArgumentException("未找到 turn：" + turnId);
        return view(turn);
    }

    public Optional<TurnResult> await(String turnId, Duration timeout) {
        Turn turn = turns.get(turnId);
        if (turn == null) return Optional.empty();
        try {
            turn.done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.ofNullable(turn.result);
    }

    public Optional<TurnResult> await(String turnId) {
        return await(turnId, DEFAULT_AWAIT);
    }

    // ==================================================================================
    // 2. 执行与步骤边界
    // ==================================================================================

    private Mono<TurnResult> execute(Turn turn) {
        return Mono.defer(
                () -> {
                    turn.event("turn 开始，共 " + turn.tasks.size() + " 个子任务");
                    return Flux.fromIterable(turn.tasks)
                            .flatMap(
                                    task ->
                                            runChildWithSteer(turn, task)
                                                    .doOnNext(turn::record),
                                    Math.max(1, maxParallelChildren))
                            .then(Mono.fromSupplier(() -> finish(turn)))
                            .timeout(Duration.ofMillis(turnTimeoutMs))
                            .onErrorResume(error -> Mono.just(finishWithError(turn, error)));
                });
    }

    /**
     * 一个子任务的执行入口，额外处理"被新输入打断后重做"。
     *
     * <p>注意：重试整个子任务只发生在新输入打断这一种情况；模型瞬时故障的重试在
     * {@link RetryingModel} 里，按 Codex {@code retry_delay} 的分类执行。
     */
    private Mono<ChildOutcome> runChildWithSteer(Turn turn, ChildTask task) {
        return Mono.defer(
                () -> {
                    if (turn.state != TurnState.RUNNING) {
                        return Mono.just(ChildOutcome.skipped(task, "turn 已结束"));
                    }
                    boundary(turn);
                    int replans = turn.replans.getOrDefault(task.taskId(), 0);
                    return runChild(turn, task)
                            .onErrorResume(
                                    SteeredSignal.class,
                                    signal -> {
                                        if (replans >= maxSteerReplans) {
                                            return Mono.just(
                                                    ChildOutcome.failed(
                                                            task,
                                                            "STEER_LIMIT",
                                                            "新输入导致的重做次数超过上限",
                                                            0));
                                        }
                                        turn.replans.put(task.taskId(), replans + 1);
                                        turn.event("新输入打断子任务 " + task.taskId() + "，重做一次");
                                        return runChildWithSteer(turn, task);
                                    });
                });
    }

    private Mono<ChildOutcome> runChild(Turn turn, ChildTask task) {
        return Mono.defer(
                () -> {
                    RetryingModel retrying =
                            new RetryingModel(models.apply(task.role()), turn, modelAttempts);
                    ReActAgent agent =
                            ReActAgent.builder()
                                    .name("child-" + task.taskId())
                                    .sysPrompt(childSystemPrompt(task))
                                    .model(retrying)
                                    .toolkit(new Toolkit())
                                    .maxIters(4)
                                    // 1 = 关闭 SDK 内部重试。重试必须显式计数，
                                    // 否则预算和"是否已收到部分响应"都说不清。
                                    .maxRetries(1)
                                    .enablePendingToolRecovery(false)
                                    .build();
                    RuntimeContext context =
                            RuntimeContext.builder()
                                    .userId(turn.userId)
                                    // 每次代次一个独立会话，避免取消后的旧 agent 复用上下文。
                                    .sessionId(
                                            turn.turnId
                                                    + "/"
                                                    + task.taskId()
                                                    + "/"
                                                    + turn.generation.get())
                                    .build();
                    InFlight flight = new InFlight(task, agent, context);
                    turn.inFlight.add(flight);

                    Msg input =
                            Msg.builder()
                                    .name("orchestrator")
                                    .role(MsgRole.USER)
                                    .textContent(buildChildPrompt(turn, task))
                                    .build();

                    return agent.call(List.of(input), context)
                            // 订阅被取消时打断 agent，避免留下继续烧 token 的孤儿调用。
                            .doFinally(
                                    signal -> {
                                        if (signal == SignalType.CANCEL) {
                                            agent.interrupt(context);
                                        }
                                    })
                            // sampling 被新输入或取消打断时，SDK 可能正常结束但没有消息。
                            // 这里必须把"空结果"翻译成同一种信号，否则子任务会被静默跳过。
                            .switchIfEmpty(
                                    Mono.defer(
                                            () -> {
                                                if (turn.interruptReason
                                                        == InterruptReason.STEER) {
                                                    return Mono.error(new SteeredSignal());
                                                }
                                                if (turn.state == TurnState.CANCELLED) {
                                                    return Mono.error(new TurnCancelled());
                                                }
                                                return Mono.error(
                                                        new IllegalStateException(
                                                                "子 agent 没有返回消息"));
                                            }))
                            .map(
                                    msg -> {
                                        if (turn.interruptReason == InterruptReason.STEER) {
                                            throw new SteeredSignal();
                                        }
                                        if (turn.state == TurnState.CANCELLED
                                                || msg.getGenerateReason()
                                                        == GenerateReason.INTERRUPTED) {
                                            throw new TurnCancelled();
                                        }
                                        return ChildOutcome.succeeded(
                                                task,
                                                msg.getTextContent(),
                                                retrying.attempts());
                                    })
                            .onErrorResume(
                                    error -> {
                                        if (error instanceof SteeredSignal
                                                || error instanceof TurnCancelled
                                                || error instanceof TurnDeadline) {
                                            return Mono.error(error);
                                        }
                                        if (turn.state == TurnState.CANCELLED) {
                                            return Mono.error(new TurnCancelled());
                                        }
                                        if (turn.interruptReason == InterruptReason.STEER) {
                                            return Mono.error(new SteeredSignal());
                                        }
                                        // 子任务最终失败只影响自己，不取消其他并行子任务。
                                        return Mono.just(
                                                ChildOutcome.failed(
                                                        task,
                                                        classify(error),
                                                        summarize(error),
                                                        retrying.attempts()));
                                    })
                            .doFinally(signal -> turn.inFlight.remove(flight));
                });
    }

    /**
     * 步骤边界。对照 Codex {@code input_queue.rs get_pending_input}：一次把队列全部取走，
     * 然后继续；取走之前先检查取消和截止时间。
     */
    private void boundary(Turn turn) {
        turn.checkActive();
        List<String> consumed = new ArrayList<>();
        synchronized (turn) {
            while (!turn.pendingInputs.isEmpty()) {
                consumed.add(turn.pendingInputs.removeFirst());
            }
            if (!consumed.isEmpty()) {
                turn.steerNotes.addAll(consumed);
                turn.interruptReason = InterruptReason.NONE;
                turn.event("步骤边界消费新输入：" + String.join(" | ", consumed));
            }
        }
    }

    // ==================================================================================
    // 3. 模型请求重试（对照 Codex retry_delay）
    // ==================================================================================

    /**
     * 只包住单次模型请求的重试装饰器。
     *
     * <p>三条上游规则：
     * <ol>
     *   <li>可重试错误才有下一次；不可重试错误立即失败（{@link #retryable}）。</li>
     *   <li>服务端建议时间优先，没有才用指数退避 + 抖动（{@link #retryDelay}）。</li>
     *   <li>已经输出过部分响应流的请求不重放，避免重复内容。</li>
     * </ol>
     */
    private static final class RetryingModel implements Model {
        private final Model delegate;
        private final Turn turn;
        private final int maxAttempts;
        private final AtomicInteger attempts = new AtomicInteger();

        RetryingModel(Model delegate, Turn turn, int maxAttempts) {
            this.delegate = delegate;
            this.turn = turn;
            this.maxAttempts = maxAttempts;
        }

        int attempts() {
            return attempts.get();
        }

        @Override
        public String getModelName() {
            return delegate.getModelName();
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        turn.checkActive();
                        // 新输入在退避期间到达时，下一次模型请求不再继续旧内容。
                        if (turn.interruptReason == InterruptReason.STEER) {
                            throw new SteeredSignal();
                        }
                        int attempt = attempts.incrementAndGet();
                        turn.acquireModelCall();

                        AtomicBoolean emitted = new AtomicBoolean();
                        return delegate.stream(messages, tools, options)
                                .doOnNext(chunk -> emitted.set(true))
                                // 用户输入或取消会取消当前 sampling；这是 Codex 的 watch_user_input
                                // 和 Claude interrupt 在 Java 侧的对应写法。
                                .takeUntilOther(turn.interruptMono())
                                .onErrorResume(
                                        error -> {
                                            Throwable root = Exceptions.unwrap(error);
                                            if (root instanceof TurnCancelled
                                                    || root instanceof TurnDeadline) {
                                                return Flux.error(root);
                                            }
                                            if (emitted.get() || !retryable(root)) {
                                                return Flux.error(error);
                                            }
                                            if (attempt >= maxAttempts) {
                                                return Flux.error(new RetryExhausted(error));
                                            }
                                            Duration delay = retryDelay(root, attempt);
                                            if (turn.remaining().compareTo(delay) <= 0) {
                                                return Flux.error(new TurnDeadline());
                                            }
                                            turn.event(
                                                    "模型请求失败（第 "
                                                            + attempt
                                                            + " 次，可重试），"
                                                            + delay.toMillis()
                                                            + "ms 后重试："
                                                            + summarize(root));
                                            return Mono.delay(delay)
                                                    .thenMany(stream(messages, tools, options));
                                        });
                    });
        }
    }

    /**
     * 对照 Codex {@code protocol/src/error.rs retry_delay} 的不可重试集合。
     * 默认返回 false：未知异常不猜测为瞬时故障。
     */
    static boolean retryable(Throwable error) {
        Throwable current = Exceptions.unwrap(error);
        for (int i = 0; current != null && i < 5; i++, current = current.getCause()) {
            if (current instanceof OpenAIException provider
                    && "insufficient_quota".equals(provider.getErrorCode())) {
                return false;
            }
            if (current instanceof ModelHttpException http
                    && http.getStatusCode() != null) {
                return http.isRetryableHttpStatus();
            }
            if (current instanceof HttpTransportException transport
                    && transport.getStatusCode() != null) {
                return transport.isRetryable();
            }
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof java.io.IOException) {
                return true;
            }
            if (current instanceof SecurityException
                    || current instanceof IllegalArgumentException
                    || current instanceof TurnCancelled
                    || current instanceof TurnDeadline
                    || current instanceof BudgetExhausted) {
                return false;
            }
        }
        return false;
    }

    /**
     * 服务端建议时间优先，否则 200ms 起指数退避 + 50ms 内抖动，上限 2 秒。
     *
     * <p>本机 AgentScope 2.0.1 的 {@link ModelHttpException} 没有暴露 Retry-After，
     * 所以这里保留 {@link #serverRetryDelay} 钩子但默认返回空；上游有
     * {@code server_retry_delay()}，这是 SDK 能力差异，不是语义差异。
     */
    static Duration retryDelay(Throwable error, int attempt) {
        Optional<Duration> advice = serverRetryDelay(error);
        if (advice.isPresent()) return advice.get();
        long base = Math.min(2_000L, 200L << Math.min(attempt - 1, 4));
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextInt(50));
    }

    static Optional<Duration> serverRetryDelay(Throwable error) {
        return Optional.empty();
    }

    // ==================================================================================
    // 4. 结果收尾
    // ==================================================================================

    private TurnResult finish(Turn turn) {
        TurnState state = turn.state;
        if (state == TurnState.RUNNING) {
            long failed = turn.outcomes().stream().filter(o -> o.state() == ChildState.FAILED).count();
            long succeeded =
                    turn.outcomes().stream().filter(o -> o.state() == ChildState.SUCCEEDED).count();
            if (failed == 0) state = TurnState.SUCCESS;
            else if (succeeded == 0) state = TurnState.FAILED;
            else state = TurnState.PARTIAL;
        }
        turn.state = state;
        turn.event("turn 结束：" + state);
        return build(turn, state);
    }

    private TurnResult finishWithError(Turn turn, Throwable error) {
        Throwable root = Exceptions.unwrap(error);
        TurnState state;
        if (root instanceof TurnCancelled) state = TurnState.CANCELLED;
        else if (root instanceof TurnDeadline || root instanceof TimeoutException)
            state = TurnState.TIMEOUT;
        else state = TurnState.FAILED;
        if (turn.state == TurnState.CANCELLED) state = TurnState.CANCELLED;
        turn.state = state;
        turn.event("turn 结束：" + state + " / " + summarize(root));
        return build(turn, state);
    }

    private TurnResult build(Turn turn, TurnState state) {
        Map<String, ChildOutcome> byId = new LinkedHashMap<>();
        for (ChildOutcome outcome : turn.outcomes()) {
            byId.put(outcome.taskId(), outcome);
        }
        List<ChildOutcome> ordered = new ArrayList<>();
        for (ChildTask task : turn.tasks) {
            ChildOutcome outcome = byId.get(task.taskId());
            ordered.add(
                    outcome != null
                            ? outcome
                            : state == TurnState.CANCELLED
                                    ? ChildOutcome.cancelled(task, "已取消")
                                    : ChildOutcome.skipped(task, "未执行"));
        }

        StringBuilder summary = new StringBuilder();
        switch (state) {
            case SUCCESS -> summary.append("全部子任务完成。");
            case PARTIAL -> summary.append("部分子任务失败，成功的结果已保留。");
            case FAILED -> summary.append("子任务全部失败。");
            case CANCELLED -> summary.append("用户取消，已完成的子任务结果已保留。");
            case TIMEOUT -> summary.append("turn 超过截止时间，已完成的结果已保留。");
            default -> summary.append("turn 结束。");
        }
        List<String> failedIds =
                ordered.stream()
                        .filter(o -> o.state() == ChildState.FAILED)
                        .map(ChildOutcome::taskId)
                        .toList();
        if (!failedIds.isEmpty()) {
            summary.append("失败：").append(String.join("、", failedIds)).append("。");
        }
        if (!turn.steerNotes.isEmpty()) {
            summary.append("本次执行中收到 ").append(turn.steerNotes.size()).append(" 条补充指令。");
        }
        return new TurnResult(
                turn.turnId,
                state,
                summary.toString(),
                List.copyOf(ordered),
                List.copyOf(turn.steerNotes),
                turn.modelCalls.get(),
                List.copyOf(turn.events));
    }

    private static List<ChildOutcome> completed(Turn turn) {
        return turn.outcomes().stream()
                .filter(o -> o.state() == ChildState.SUCCEEDED)
                .toList();
    }

    // ==================================================================================
    // 5. 提示词
    // ==================================================================================

    private static String childSystemPrompt(ChildTask task) {
        return """
                你是被编排器派发的子 agent，只负责一个具体任务。
                完成后只输出结论文本，不要输出 JSON，也不要自评"已完成"。
                如果任务无法完成，直接说明原因，不要编造结果。
                角色：%s
                """
                .formatted(task.role());
    }

    private static String buildChildPrompt(Turn turn, ChildTask task) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("[role:").append(task.role()).append("]\n");
        prompt.append("[task:").append(task.taskId()).append("]\n");
        prompt.append("任务：").append(task.prompt()).append("\n");
        synchronized (turn) {
            if (!turn.steerNotes.isEmpty()) {
                prompt.append("用户在执行中补充的指令（必须遵守）：\n");
                for (String note : turn.steerNotes) {
                    prompt.append("- ").append(note).append('\n');
                }
            }
        }
        return prompt.toString();
    }

    private TurnView view(Turn turn) {
        return new TurnView(
                turn.turnId,
                turn.revision.get(),
                turn.state.name(),
                turn.pendingInputs.size(),
                turn.tasks.size());
    }

    // ==================================================================================
    // 6. 数据结构
    // ==================================================================================

    public enum TurnState {
        RUNNING,
        SUCCESS,
        PARTIAL,
        FAILED,
        CANCELLED,
        TIMEOUT
    }

    public enum ChildState {
        SUCCEEDED,
        FAILED,
        SKIPPED,
        CANCELLED
    }

    private enum InterruptReason {
        NONE,
        STEER,
        CANCEL
    }

    private enum TurnSignal {
        STEER,
        CANCEL
    }

    public record ChildTask(String taskId, String role, String prompt) {
        public ChildTask {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(prompt, "prompt");
        }
    }

    /** 只回结构化结果，不回灌子 agent 的原始 transcript。 */
    public record ChildOutcome(
            String taskId,
            String role,
            ChildState state,
            int modelAttempts,
            String output,
            String errorClass,
            String detail) {

        static ChildOutcome succeeded(ChildTask task, String output, int attempts) {
            return new ChildOutcome(
                    task.taskId(), task.role(), ChildState.SUCCEEDED, attempts, output, null, null);
        }

        static ChildOutcome failed(ChildTask task, String errorClass, String detail, int attempts) {
            return new ChildOutcome(
                    task.taskId(), task.role(), ChildState.FAILED, attempts, null, errorClass, detail);
        }

        static ChildOutcome skipped(ChildTask task, String detail) {
            return new ChildOutcome(
                    task.taskId(), task.role(), ChildState.SKIPPED, 0, null, null, detail);
        }

        static ChildOutcome cancelled(ChildTask task, String detail) {
            return new ChildOutcome(
                    task.taskId(), task.role(), ChildState.CANCELLED, 0, null, null, detail);
        }
    }

    public record TurnResult(
            String turnId,
            TurnState state,
            String summary,
            List<ChildOutcome> children,
            List<String> steerNotes,
            int modelCalls,
            List<String> events) {
    }

    public record TurnView(
            String turnId, int revision, String state, int pendingInputs, int childCount) {
    }

    public record InstructionReply(
            String turnId, int revision, String state, String outcome, String message) {

        static InstructionReply rejected(String turnId, int revision, String message) {
            return new InstructionReply(turnId, revision, "UNKNOWN", "REJECTED", message);
        }
    }

    public record CancelReply(
            String turnId,
            int revision,
            String state,
            boolean acknowledged,
            boolean forced,
            List<ChildOutcome> completed,
            String message) {

        static CancelReply rejected(String turnId, int revision, String message) {
            return new CancelReply(
                    turnId, revision, "UNKNOWN", false, false, List.of(), message);
        }
    }

    // ==================================================================================
    // 7. 内部状态
    // ==================================================================================

    private record InFlight(ChildTask task, ReActAgent agent, RuntimeContext context) {
    }

    private static final class Turn {
        final String turnId;
        final String userId;
        final List<ChildTask> tasks;
        final List<String> steerNotes = Collections.synchronizedList(new ArrayList<>());
        final Deque<String> pendingInputs = new ArrayDeque<>();
        final Set<InFlight> inFlight = ConcurrentHashMap.newKeySet();
        final List<ChildOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Integer> replans = new ConcurrentHashMap<>();
        final AtomicInteger revision = new AtomicInteger(1);
        final AtomicInteger generation = new AtomicInteger(1);
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicReference<Sinks.One<TurnSignal>> interruptLatch =
                new AtomicReference<>(Sinks.one());
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final Instant deadline;
        final int maxModelCalls;

        volatile TurnState state = TurnState.RUNNING;
        volatile InterruptReason interruptReason = InterruptReason.NONE;
        volatile Disposable subscription;
        volatile TurnResult result;

        Turn(
                String turnId,
                String userId,
                String question,
                List<ChildTask> tasks,
                int maxModelCalls,
                long timeoutMs) {
            this.turnId = turnId;
            this.userId = userId;
            this.tasks = tasks;
            this.maxModelCalls = maxModelCalls;
            this.deadline = Instant.now().plusMillis(timeoutMs);
            event("收到问题：" + question);
        }

        void checkActive() {
            if (state == TurnState.CANCELLED) throw new TurnCancelled();
            if (Instant.now().isAfter(deadline)) throw new TurnDeadline();
        }

        void acquireModelCall() {
            if (modelCalls.incrementAndGet() > maxModelCalls) throw new BudgetExhausted();
        }

        Duration remaining() {
            return Duration.between(Instant.now(), deadline);
        }

        Mono<TurnSignal> interruptMono() {
            return interruptLatch.get().asMono();
        }

        void signal(TurnSignal signal) {
            Sinks.One<TurnSignal> latch = interruptLatch.getAndSet(Sinks.one());
            latch.tryEmitValue(signal);
        }

        void event(String text) {
            if (events.size() < 200) {
                events.add(text);
            }
        }

        void record(ChildOutcome outcome) {
            outcomes.add(outcome);
        }

        List<ChildOutcome> outcomes() {
            synchronized (outcomes) {
                return List.copyOf(outcomes);
            }
        }
    }

    // ==================================================================================
    // 8. 内部信号
    // ==================================================================================

    private static final class SteeredSignal extends RuntimeException {
        SteeredSignal() {
            super("收到新输入，当前步骤让位");
        }
    }

    private static final class TurnCancelled extends RuntimeException {
        TurnCancelled() {
            super("turn 已取消");
        }
    }

    private static final class TurnDeadline extends RuntimeException {
        TurnDeadline() {
            super("turn 已超过截止时间");
        }
    }

    private static final class BudgetExhausted extends RuntimeException {
        BudgetExhausted() {
            super("模型调用预算已耗尽");
        }
    }

    private static final class RetryExhausted extends RuntimeException {
        RetryExhausted(Throwable cause) {
            super("模型请求重试次数已耗尽", cause);
        }
    }

    private static String classify(Throwable error) {
        Throwable root = Exceptions.unwrap(error);
        if (root instanceof RetryExhausted) return "RETRY_EXHAUSTED";
        if (root instanceof BudgetExhausted) return "BUDGET_EXHAUSTED";
        if (root instanceof TurnCancelled) return "CANCELLED";
        if (root instanceof TurnDeadline) return "TIMEOUT";
        if (root instanceof SteeredSignal) return "STEERED";
        return retryable(root) ? "RETRYABLE" : "NON_RETRYABLE";
    }

    private static String summarize(Throwable error) {
        Throwable root = Exceptions.unwrap(error);
        Throwable cause = root.getCause() == null ? root : root.getCause();
        String message = cause.getMessage();
        String text =
                cause.getClass().getSimpleName()
                        + (message == null || message.isBlank() ? "" : "：" + message);
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    // ==================================================================================
    // 9. 离线演示（脚本模型，不联网；SDK 的 Agent 循环和取消链路是真实的）
    // ==================================================================================

    public static void main(String[] args) throws Exception {
        demoModelRetry();
        demoSteer();
        demoCancel();
        System.out.println("\n全部演示结束。");
    }

    /** 场景一：临时错误按 retry_delay 重试；确定性错误只试一次；其他子任务继续。 */
    private static void demoModelRetry() {
        DemoModel model = new DemoModel();
        MultiAgentRunCoordinator coordinator = new MultiAgentRunCoordinator(role -> model);
        TurnView view =
                coordinator.start(
                        "demo-user",
                        "明天从杭州到上海，看看天气、车次并给建议",
                        List.of(
                                new ChildTask("W", "weather", "查询杭州明天的天气"),
                                new ChildTask("T", "train", "查询杭州到上海明天的车次"),
                                new ChildTask("I", "itinerary", "根据查询结果给出出行建议")));
        TurnResult result = coordinator.await(view.turnId()).orElseThrow();
        print("一、子 Agent 失败：按错误类型重试，失败不拖累其他子任务", result);
    }

    /** 场景二：执行中追加输入。 */
    private static void demoSteer() throws Exception {
        DemoModel model = new DemoModel();
        MultiAgentRunCoordinator coordinator = new MultiAgentRunCoordinator(role -> model);
        TurnView view =
                coordinator.start(
                        "demo-user",
                        "规划一条出行路线",
                        List.of(
                                new ChildTask("S", "slow", "生成一份较长的路线说明"),
                                new ChildTask("F", "fast", "给出一句简短提示")));

        Thread.sleep(300);
        InstructionReply reply =
                coordinator.steer(view.turnId(), "只保留二等座，不要一等座", view.revision());
        System.out.println("\n===== 二、执行中用户追加输入 =====");
        System.out.println("处理结果：" + reply.outcome() + " / " + reply.message());

        TurnResult result = coordinator.await(view.turnId()).orElseThrow();
        print("（补充输入后的收尾）", result);
    }

    /** 场景三：显式取消；已完成结果保留，宽限期后硬取消。 */
    private static void demoCancel() throws Exception {
        DemoModel model = new DemoModel();
        MultiAgentRunCoordinator coordinator = new MultiAgentRunCoordinator(role -> model);
        TurnView view =
                coordinator.start(
                        "demo-user",
                        "规划一条出行路线",
                        List.of(
                                new ChildTask("S", "slow", "生成一份较长的路线说明"),
                                new ChildTask("F", "fast", "给出一句简短提示")));

        Thread.sleep(300);
        CancelReply reply = coordinator.cancel(view.turnId(), view.revision());
        System.out.println("\n===== 三、执行中用户取消 =====");
        System.out.println(
                "acknowledged="
                        + reply.acknowledged()
                        + "，forced="
                        + reply.forced()
                        + "，"
                        + reply.message());
        System.out.println("已完成并保留：" + reply.completed().stream().map(ChildOutcome::taskId).toList());

        TurnResult result = coordinator.await(view.turnId()).orElseThrow();
        print("（取消后的收尾）", result);
    }

    private static void print(String title, TurnResult result) {
        System.out.println("\n===== " + title + " =====");
        System.out.println("状态：" + result.state() + "，模型调用：" + result.modelCalls());
        System.out.println("摘要：" + result.summary());
        for (ChildOutcome child : result.children()) {
            System.out.printf(
                    "  %-2s %-8s 模型尝试 %d 次  错误=%s  产物=%s%n",
                    child.taskId(),
                    child.state(),
                    child.modelAttempts(),
                    child.errorClass() == null ? "-" : child.errorClass() + " / " + child.detail(),
                    child.output() == null ? "-" : child.output());
        }
        if (!result.steerNotes().isEmpty()) {
            System.out.println("补充指令：" + result.steerNotes());
        }
        System.out.println("-- 事件 --");
        result.events().forEach(event -> System.out.println("  " + event));
    }

    /**
     * 脚本模型：用来离线演示，不联网。
     * 角色来自提示词里的 {@code [role:xxx]} 标记，调用次数按角色累计。
     */
    static final class DemoModel implements Model {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String prompt = lastText(messages);
            String role = role(prompt);
            int call = calls.computeIfAbsent(role, key -> new AtomicInteger()).incrementAndGet();

            if ("weather".equals(role) && call == 1) {
                // 临时连接错误：对应 retry_delay 的可重试类别。
                return Flux.error(
                        new ModelException("模拟临时断连", new ConnectException("connection reset")));
            }
            if ("train".equals(role)) {
                // 参数/业务错误：确定性失败，不重试。
                return Flux.error(
                        new ModelException("模拟不可重试的参数错误", new IllegalArgumentException("bad date")));
            }
            if ("slow".equals(role) && call == 1) {
                // 慢调用：用来演示新输入打断和取消；被取消后不会发出任何结果。
                return Mono.delay(Duration.ofSeconds(5))
                        .thenReturn(text("较长路线说明生成中"))
                        .flux();
            }
            return Mono.just(text(role + " 完成：" + prompt.lines().findFirst().orElse("")))
                    .flux();
        }

        @Override
        public String getModelName() {
            return "demo-scripted";
        }

        private ChatResponse text(String value) {
            return ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.<ContentBlock>of(TextBlock.builder().text(value).build()))
                    .usage(new ChatUsage(8, 16, 0.01))
                    .finishReason("stop")
                    .build();
        }

        private static String lastText(List<Msg> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                String text = messages.get(i).getTextContent();
                if (text != null && !text.isBlank()) return text;
            }
            return "";
        }

        private static String role(String prompt) {
            Matcher matcher = ROLE.matcher(prompt);
            return matcher.find() ? matcher.group(1) : "unknown";
        }
    }
}
