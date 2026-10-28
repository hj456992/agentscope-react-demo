package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * 持久任务持有执行订阅，HTTP 等待者取消只会离开等待，不会取消任务。
 *
 * <p>并发模型说明（这是本类最容易看错的地方）：
 *
 * <ul>
 *   <li>任务真正的执行是“服务端自己订阅”的一条 {@code Mono<Void>} 链，生命周期跟随
 *       {@link #owned}，不跟随某个 HTTP 请求；浏览器断开只是不再等待，不会停任务。
 *   <li>所有 JDBC 操作都放在 {@code Schedulers.boundedElastic()} 上执行，因为 H2 的调用是阻塞的，
 *       绝不能跑在 WebFlux 的 Netty 事件线程上。
 *   <li>创建/指令等“读-判断-写”必须串行，所以整个方法用 {@code synchronized} 保护，
 *       并在同一事务里完成版本校验。
 * </ul>
 */
public final class TaskService implements AutoCloseable {
    public enum Action {
        SUPPLEMENT,
        REVISE,
        SWITCH,
        CANCEL,
        AUTO
    }

    public record Instruction(
            String instructionId, int expectedRevision, Action action, String message) {}

    public record View(
            @JsonUnwrapped Result result,
            String runId,
            int revision,
            String requestId,
            String state,
            int modelCalls,
            long deadline,
            String replacedBy) {}

    public record InstructionReply(String runId, int revision, String state, String message) {}

    private final TaskRepository repository;
    private final ConversationService conversations;
    private final SupervisorSettings settings;
    private final AccessPolicy accessPolicy;
    /**
     * “怎么执行一轮任务”被抽成一个函数对象。
     *
     * <p>生产环境传入的是 {@code supervisor::run}，测试里可以换成假实现。
     * {@code apply(request, control)} 等价于 {@code supervisor.run(request, control)}，
     * 只是拿到一个描述执行的 Mono，本身不跑。这样 TaskService 只负责任务生命周期，
     * 完全不依赖 Supervisor 的内部实现。
     */
    private final BiFunction<ChatInput, RunControl, Mono<Result>> runner;
    /**
     * 有界弹性调度器（boundedElastic）：专门跑阻塞的 JDBC 与任务链。
     *
     * <p>它和传统 {@code new ThreadPoolExecutor(...)} 的区别（参数看着少，是因为 Reactor 把很多
     * 策略写死了，只暴露两个容量）：
     *
     * <ul>
     *   <li><b>来源</b>：{@code Scheduler} 是 Project Reactor 的接口（{@code reactor.core.scheduler}），
     *       不是 JDK 类，JDK 里没有这个接口。本项目由 Spring Boot 3.3.5 引入 Reactor 3.6.x。
     *       容易混的是 JDK 自带的 {@code java.util.concurrent.ScheduledExecutorService}（Java 5 起），
     *       本类下方 {@code recovery} 用的就是它，两者职责不同，不要混用。
     *   <li><b>参数含义</b>：第一个 12 是 {@code threadCap}——最多同时存在 12 个线程；
     *       第二个 256 是 {@code queuedTaskCap}——<b>每个线程的等待队列</b>最多排 256 个任务
     *       （按线程各算一份，不是全池共用 256）。传统池要自己配 corePoolSize、
     *       maximumPoolSize、keepAliveTime、队列类型、拒绝策略，这里都不用写。
     *   <li><b>没有核心线程</b>：线程按需创建，空闲约 60 秒自动回收；传统池的核心线程默认常驻，
     *       除非开 {@code allowCoreThreadTimeOut}。
     *   <li><b>队列满了直接拒绝</b>：抛 {@code RejectedExecutionException}，在响应式链里表现为
     *       一条错误信号；传统池默认也是 AbortPolicy，但入口是 {@code execute/submit}。
     *   <li><b>返回 Disposable 而不是 Future</b>：只能取消，不能 {@code get()} 阻塞取结果；
     *       结果要通过 Mono/Flux 的订阅回调拿。所以它服务的是 {@code subscribeOn/publishOn}，
     *       不是“提交任务再等返回值”那种用法。
     *   <li><b>线程亲和</b>：同一个 {@code Scheduler.Worker} 上连续排的任务固定在同一线程执行，
     *       阻塞任务不会在线程间乱跳，ThreadLocal 之类的状态也稳定。
     *   <li><b>存在意义</b>：Reactor 规定阻塞代码不能跑在 Netty 事件线程上，boundedElastic
     *       就是官方给阻塞任务（JDBC、文件 IO、同步 SDK）准备的池；全局默认
     *       {@code Schedulers.boundedElastic()} 则是 10×CPU 核数线程、每线程 10 万队列。
     *   <li><b>12/256 是演示用业务配置</b>，可按部署容量调整，不是上游默认值，也不是行业标准。
     * </ul>
     */
    private final Scheduler scheduler = Schedulers.newBoundedElastic(12, 256, "supervisor-runtime");
    private final Map<String, OwnedRun> owned = new HashMap<>();
    private final ScheduledExecutorService recovery =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "supervisor-recovery");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile boolean closed;

    private static final class OwnedRun {
        final AccessPolicy.Identity identity;
        final Disposable.Swap subscription = Disposables.swap();

        OwnedRun(AccessPolicy.Identity identity) {
            this.identity = identity;
        }
    }

    public TaskService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings,
            Supervisor supervisor,
            AccessPolicy accessPolicy) {
        // supervisor::run 是“方法引用”，不是“调用”。
        // 它把 Supervisor 的两个参数的 run(ChatInput, RunControl) 方法，适配成
        // BiFunction<ChatInput, RunControl, Mono<Result>> 这个函数对象：
        // 现在只是把“怎么执行”记下来，还没有执行任何东西。
        // 之后 TaskService 只依赖这个函数，不需要认识 Supervisor 类本身。
        this(repository, conversations, settings, supervisor::run, accessPolicy);
    }

    TaskService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings,
            BiFunction<ChatInput, RunControl, Mono<Result>> runner) {
        this(repository, conversations, settings, runner, null);
    }

    TaskService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings,
            BiFunction<ChatInput, RunControl, Mono<Result>> runner,
            AccessPolicy accessPolicy) {
        this.repository = repository;
        this.conversations = conversations;
        this.settings = settings;
        this.runner = runner;
        this.accessPolicy = accessPolicy;
        recovery.scheduleWithFixedDelay(
                () -> {
                    if (!closed)
                        scheduler.schedule(
                                () -> {
                                    try {
                                        recover();
                                    } catch (RuntimeException ignored) {
                                        /* 下次有界扫描重试；不回显存储细节。 */
                                    }
                                });
                },
                1,
                1,
                TimeUnit.SECONDS);
    }

    /**
     * 受理请求：先建任务（写库），再决定是立即返回 202 还是等结果。
     *
     * <p>{@code Mono.fromCallable(...).subscribeOn(scheduler)} 把“建任务 + 启动执行”挪到弹性线程池，
     * 因为里面有事务和同步锁；WebFlux 只负责把这个 Mono 订阅出去。
     * {@code async} 分支直接拿到当前视图返回，同步分支进入 {@link #await} 轮询等待。
     */
    public Mono<View> submit(AccessPolicy.Identity identity, ChatInput request, boolean async) {
        // 1) 把“校验 + 建任务 + 落库 + 启动执行”整段包成一个延迟计算。
        //    fromCallable 的 lambda 此刻并不会执行，只有返回的 Mono 被订阅时才真正跑。
        //    返回值类型是 Mono<TaskRepository.Task>（建好的那条任务记录）。
        return Mono.fromCallable(() -> create(identity, request))
                // 2) create 里是同步方法，包含数据库事务和 synchronized 锁，属于“阻塞调用”。
                //    subscribeOn(scheduler) 表示：订阅和执行这段计算时，切到弹性线程池上去跑。
                //    这样就不会占用 WebFlux 的 Netty 事件线程（那条线程一旦被阻塞，整个服务都卡）。
                .subscribeOn(scheduler)
                // 3) 任务建好后拿到 task，再根据 async 决定给调用方返回什么。
                //    flatMap 的意思是“把上游的 1 个结果，异步地换成下游的 1 个新 Mono”。
                .flatMap(
                        task ->
                                async
                                        // 3a) 异步模式：任务已经在后台跑了，这里立刻把“当前快照”返回。
                                        //     Mono.just 就是“现成的一个值”，不等待、不再计算。
                                        //     前端拿到的 state 通常是 RUNNING，之后靠 get 接口查进度。
                                        ? Mono.just(view(identity, task))
                                        // 3b) 同步模式：进入 await 轮询，一直等到任务不再是 RUNNING，
                                        //     才把这个最终结果作为 HTTP 响应返回（长轮询）。
                                        : await(identity, task.runId));
    }

    private synchronized TaskRepository.Task create(
            AccessPolicy.Identity identity, ChatInput request) {
        validateRequest(request);
        String requestId =
                blank(request.requestId()) ? UUID.randomUUID().toString() : request.requestId();
        String hash = requestHash(request);
        TaskRepository.Task task =
                repository.transaction(
                        c -> {
                            List<TaskRepository.Task> all = repository.all();
                            for (var old : all)
                                if (sameIdentity(old, identity)
                                        && old.requestId.equals(requestId)) {
                                    if (!old.requestHash.equals(hash)) throw conflict("请求编号的内容不同");
                                    return old;
                                }
                            for (var old : all)
                                if (sameIdentity(old, identity)
                                        && old.sessionId.equals(request.sessionId())
                                        && old.state.equals("RUNNING"))
                                    throw conflict("会话已有活动任务：" + old.runId);
                            checkCapacity(identity);
                            if (all.size() >= settings.maxStoredTasks()) throw busy();
                            reserveNewTask(identity);
                            var fresh = newTask(identity, request, requestId, hash);
                            repository.save(fresh);
                            repository.currentSession(fresh);
                            return fresh;
                        });
        // 真正的任务执行就在这里启动：start() 会自己 subscribe 一条 cycle() 执行链，
        // 该链跑在 scheduler（supervisor-runtime 有界弹性池）上，生命周期跟随 owned，
        // 不跟随本次 HTTP 请求。submit() 后面的 await() 只是轮询数据库等终态，
        // 客户端断开只会停止等待，不会停止任务执行。
        if (task.state.equals("RUNNING") && !owned.containsKey(task.runId)) start(task);
        return task;
    }

    private TaskRepository.Task newTask(
            AccessPolicy.Identity identity, ChatInput request, String requestId, String hash) {
        var task = new TaskRepository.Task();
        task.runId = UUID.randomUUID().toString();
        task.identity = identity;
        task.tenantId = identity.tenantId();
        task.userId = identity.userId();
        task.sessionId = request.sessionId();
        task.requestId = requestId;
        task.requestHash = hash;
        task.createdAt = System.currentTimeMillis();
        task.deadline = task.createdAt + settings.timeout().toMillis();
        task.maxCalls = settings.maxModelCalls();
        task.userInputs.add(request.question());
        task.request =
                conversations.enrich(
                        identity,
                        new ChatInput(
                                request.sessionId(),
                                request.question(),
                                request.history(),
                                requestId));
        return task;
    }

    public Mono<View> get(AccessPolicy.Identity identity, String id) {
        return Mono.fromCallable(() -> view(identity, visible(identity, id)))
                .subscribeOn(scheduler);
    }

    /**
     * 同步等待结果：每 25ms 查一次库，直到状态不再是 RUNNING。
     *
     * <p>为什么用轮询而不是 CompletableFuture/Sinks 推送：任务可能在别的线程、甚至在进程重启后
     * 由恢复线程推进，等待方无法持有执行者。轮询以数据库为唯一真相，重启后依然成立。
     * {@code Mono.delay(...).then(Mono.defer(...))} 是非阻塞等待：不占线程，到点再查一次。
     */
    private Mono<View> await(AccessPolicy.Identity identity, String id) {
        return get(identity, id)
                .flatMap(
                        view ->
                                view.state().equals("RUNNING")
                                        ? Mono.delay(Duration.ofMillis(25))
                                                .then(Mono.defer(() -> await(identity, id)))
                                        : Mono.just(view));
    }

    public Mono<InstructionReply> instruct(
            AccessPolicy.Identity identity, String id, Instruction input) {
        return Mono.fromCallable(() -> applyInstruction(identity, id, input))
                .subscribeOn(scheduler);
    }

    private synchronized InstructionReply applyInstruction(
            AccessPolicy.Identity identity, String id, Instruction input) {
        if (input == null
                || blank(input.instructionId())
                || input.instructionId().length() > 200
                || input.action() == null
                || input.expectedRevision() < 1
                || (input.action() != Action.CANCEL && blank(input.message()))
                || (input.message() != null && input.message().length() > 2000)) throw invalid();
        String fingerprint = hash(repository.encode(input));
        InstructionReply reply =
                repository.transaction(
                        c -> {
                            TaskRepository.Task task = visible(identity, id);
                            String[] prior = repository.instruction(id, input.instructionId());
                            if (prior != null) {
                                if (!prior[0].equals(fingerprint)) throw conflict("指令编号的内容不同");
                                return repository.decode(prior[1], InstructionReply.class);
                            }
                            if (task.state.equals("CANCELLED") && input.action() == Action.CANCEL) {
                                var repeated =
                                        new InstructionReply(
                                                id, task.revision, task.state, "任务已停止");
                                repository.instruction(
                                        id, input.instructionId(), fingerprint, repeated);
                                return repeated;
                            }
                            if (task.revision != input.expectedRevision())
                                throw conflict("任务版本已更新");
                            if (!task.state.equals("RUNNING") && !task.state.equals("NEEDS_INPUT"))
                                throw conflict("任务已结束");
                            if (task.deadline <= System.currentTimeMillis())
                                throw conflict("任务已过截止时间");
                            if (!id.equals(repository.currentSession(identity, task.sessionId)))
                                throw conflict("会话已开始后续任务");
                            if (input.action() == Action.AUTO) {
                                var clarification =
                                        new InstructionReply(
                                                id,
                                                task.revision,
                                                task.state,
                                                "请明确选择补充、修改、切换或停止；自动动作未执行。");
                                repository.instruction(
                                        id, input.instructionId(), fingerprint, clarification);
                                return clarification;
                            }
                            InstructionReply answer;
                            if (input.action() == Action.CANCEL) {
                                task.state = "CANCELLED";
                                task.generation++;
                                task.revision++;
                                task.pending.clear();
                                task.result = emptyResult(task, "CANCELLED", "任务已停止");
                                repository.save(task);
                                answer =
                                        new InstructionReply(
                                                id, task.revision, task.state, "任务已停止");
                            } else if (input.action() == Action.SWITCH) {
                                // 新任务必须先取得用户速率额度；原执行订阅释放后才启动替代任务。
                                if (!owned.containsKey(id)) checkCapacity(identity);
                                if (repository.all().size() >= settings.maxStoredTasks())
                                    throw busy();
                                reserveNewTask(identity);
                                ChatInput replacement =
                                        new ChatInput(
                                                task.sessionId,
                                                input.message(),
                                                List.of(),
                                                UUID.randomUUID().toString());
                                var next =
                                        newTask(
                                                identity,
                                                replacement,
                                                replacement.requestId(),
                                                requestHash(replacement));
                                task.state = "REPLACED";
                                task.generation++;
                                task.revision++;
                                task.replacedBy = next.runId;
                                task.result = emptyResult(task, "REPLACED", "已切换新任务");
                                repository.save(task);
                                repository.save(next);
                                repository.currentSession(next);
                                answer =
                                        new InstructionReply(
                                                next.runId, next.revision, next.state, "已切换新任务");
                            } else {
                                if (!owned.containsKey(id)) checkCapacity(identity);
                                if (task.pending.size() >= 32) throw busy();
                                int pendingCharacters =
                                        task.pending.stream().mapToInt(String::length).sum()
                                                + task.pending.size()
                                                + input.message().length();
                                if (pendingCharacters > 2000)
                                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
                                task.checkpoints.remove("progress");
                                task.pending.add(input.message());
                                task.userInputs.add(input.message());
                                task.revision++;
                                task.state = "RUNNING";
                                task.result = null;
                                repository.save(task);
                                answer =
                                        new InstructionReply(
                                                id, task.revision, task.state, "指令已排队，将在执行边界处理");
                            }
                            repository.instruction(id, input.instructionId(), fingerprint, answer);
                            return answer;
                        });
        TaskRepository.Task original = repository.get(id);
        if (original.state.equals("CANCELLED") || original.state.equals("REPLACED")) {
            OwnedRun old = owned.get(id);
            if (old != null) old.subscription.dispose();
        }
        TaskRepository.Task target = repository.get(reply.runId());
        if (target != null
                && target.state.equals("RUNNING")
                && !owned.containsKey(target.runId)
                && hasCapacity(target.identity)) start(target);
        return reply;
    }

    /**
     * 启动一个任务的执行订阅。
     *
     * <p>{@code Disposable.Swap} 的意义：切换/取消时可以用 {@code update/dispose} 替换或销毁订阅，
     * 而不必担心旧订阅还在跑。{@code doFinally} 负责把所有权从 {@link #owned} 移除——
     * 无论正常结束、报错还是被取消都会执行，避免名额泄漏。
     *
     * <p>注意 {@code subscribe(ignored -> {}, error -> fail(...))}：任务链的错误不往上抛给 HTTP，
     * 而是收敛成任务状态 FAILED/TIMEOUT 等，这样查询接口仍然能读到终态。
     */
    private void start(TaskRepository.Task task) {
        // 服务已关闭，或者这个任务已经有执行链在跑了，就直接返回。
        // 后者是防重复用的：比如恢复线程和指令线程可能同时想把同一个任务跑起来。
        if (closed || owned.containsKey(task.runId)) return;
        // 先造一张“所有权凭证”：记录任务属于哪个身份，并准备一个可替换、可取消的订阅槽（Swap）。
        var ownership = new OwnedRun(task.identity);
        // 先把凭证登记进 owned，再真正启动执行。
        // 这样并发进来的 start() 一看到登记就已经退出了，同时这个任务也占住了并发名额。
        owned.put(task.runId, ownership);
        // 创建本次执行的控制对象：预算、超时、取消、新输入这些检查都靠它，且都会落库。
        var control = new PersistentControl(task);
        // 组装并订阅执行链。cycle 是“调一轮 Supervisor → 把结果写回数据库 → 视情况再来一轮”的循环；
        // 这里执行 subscribe 的那一刻，任务才真正开始跑。
        Disposable execution =
                cycle(task.runId, control)
                        // 整条链切到 supervisor-runtime 有界弹性池执行，绝不占用 Netty 事件线程。
                        .subscribeOn(scheduler)
                        // 收尾钩子：不管正常结束、报错还是被取消，都会走到这里。
                        .doFinally(
                                signal -> {
                                    // owned 是普通 HashMap，多线程会一起改，所以先加锁。
                                    synchronized (TaskService.this) {
                                        // 只删自己这一份（remove(key, value)），
                                        // 防止把后来者重新登记的同名任务凭证误删。
                                        owned.remove(task.runId, ownership);
                                    }
                                })
                        // 服务端自己订阅，所以 HTTP 断开不影响任务；
                        // 正常结束不用回调（结果已写库），出错则交给 fail(...) 收敛成 FAILED/TIMEOUT 等终态。
                        .subscribe(ignored -> {}, error -> fail(task.runId, control, error));
        // 把订阅句柄交给 Swap 保管：以后取消或切换时，通过它 dispose 即可，不用再找这个局部变量。
        ownership.subscription.update(execution);
    }

    /**
     * 任务的一轮执行：调用 Supervisor，然后把结果写回任务行。
     *
     * <p>两个响应式细节：
     *
     * <ul>
     *   <li>{@code timeout(control.remaining())} 用“剩余时间”而不是固定值，重启续跑后也不会重新计时；
     *   <li>{@code publishOn(scheduler)} 让随后的 {@code flatMap}（里面有事务）跑在弹性线程池上；
     *       如果版本已被新指令推进（{@code revision} 变化），事务返回 false，这里递归再跑一轮，
     *       从而“旧结果不覆盖新条件”。
     * </ul>
     */
    private Mono<Void> cycle(String id, PersistentControl control) {
        // Mono.defer 的含义：方法被调用时并不执行里面的代码，只有被订阅时才执行；
        // 每一轮递归都会重新跑一遍，所以读到的永远是数据库里的最新状态。
        return Mono.defer(
                () -> {
                    // 从数据库重新读任务行（数据库是唯一真相）。
                    // 指令接口可能刚刚改过 pending、revision、state，这里必须拿最新的，不能用启动时的旧值。
                    TaskRepository.Task task = repository.get(id);
                    // 每轮开始先“验票”：任务被取消/替换、或已过截止时间，就在这里抛异常。
                    // 异常会冒泡到 start() 里的 error 回调，由 fail(...) 收敛成 CANCELLED/TIMEOUT 等终态。
                    control.checkActive();
                    // 真正干活：把当前请求交给 Supervisor 的模型-工具循环。
                    // runner 在构造器里绑定为 Supervisor::run（见本类构造器），
                    // 所以模型调用和 Agent 创建都不在本类里，调用链是：
                    //   runner.apply(...)
                    //     → Supervisor.run()
                    //       → Supervisor.step()：models.apply("supervisor") 取主模型，
                    //         model.stream(...) 发起真正的模型请求；
                    //         registry(...) → TravelAgentFactory 把三个子 Agent 包装成工具，
                    //         模型真的调用某个子 Agent 工具时，ChildAgentDispatcher.invoke(...)
                    //         才通过 SubAgentTool 懒创建 ReActAgent。
                    // 这里拿到的 result 就是这条链最终的产物。
                    // apply(...) 本身只是一次普通的方法调用，等价于 supervisor.run(request, control)，
                    // 它不会立刻执行；返回的 Mono 是“冷”的，描述“订阅后该怎么跑”，
                    // 等下游订阅时（cycle 自身的 Mono 被执行时）才真正进入 Supervisor 的循环。
                    return runner.apply(task.request, control)
                            // 执行没产出结果属于异常情况，转成错误信号，避免任务悄悄“成功”结束。
                            .switchIfEmpty(Mono.error(new IllegalStateException("执行没有返回结果")))
                            // 超时用“剩余时间”而不是固定时长：进程重启续跑后不会重新计时。
                            // 超时抛 TimeoutException，同样由 fail(...) 落成 TIMEOUT 终态。
                            .timeout(control.remaining())
                            // 从这里开始，下游（下面的 flatMap 里有数据库事务）切到弹性线程池执行。
                            // 因为 H2 是阻塞调用，绝不能留在 Netty 事件线程上。
                            .publishOn(scheduler)
                            // 模型/工具循环结束时拿到 Result，在这里做“旧结果不能覆盖新条件”的校验。
                            .flatMap(
                                    result -> {
                                        // 事务返回 true = 这条执行链可以收工；
                                        // 返回 false = 结果已过期，要按最新条件再跑一轮。
                                        boolean finished =
                                                repository.transaction(
                                                        c -> {
                                                            // 事务内再读一次最新任务行，后面所有判断都以它为准。
                                                            var current = repository.get(id);
                                                            // 两种情况说明这次执行已经不是“当前有效的那次”，
                                                            // 结果直接丢弃、链到此结束：
                                                            //   1) generation 变了 —— 任务被取消或替换；
                                                            //   2) 状态不再是 RUNNING —— 例如 recovery 线程已把它标成 TIMEOUT。
                                                            if (current.generation
                                                                            != control.generation
                                                                    || !current.state.equals(
                                                                            "RUNNING")) return true;
                                                            // revision 变了，说明执行期间用户又发了指令、
                                                            // 而本次执行还没消费到它。当前结果是在旧条件下算出来的，
                                                            // 不能落库，返回 false 让外层递归重跑。
                                                            if (current.revision
                                                                    != control.revision())
                                                                return false;
                                                            // 版本和条件都对得上：把结果和终态（COMPLETED/FAILED 等）写回任务行。
                                                            current.result = result;
                                                            current.state = result.status().name();
                                                            repository.save(current);
                                                            // 同时把这轮用户输入和回答写进会话记忆，供后续对话使用。
                                                            saveConversation(current, result);
                                                            // 结果已落库，正常收工。
                                                            return true;
                                                        });
                                        // true：正常结束（或被更新的一次执行接管），整条链到此为止。
                                        // false：用最新条件再跑一轮。注意这是响应式递归，每次 flatMap 回调里
                                        // 才创建下一个 Mono，不会占着线程空转。
                                        return finished ? Mono.<Void>empty() : cycle(id, control);
                                    });
                });
    }

    private void saveConversation(TaskRepository.Task task, Result result) {
        if (task.userInputs.isEmpty()) task.userInputs.add(task.request.question());
        for (int index = task.recordedInputs; index < task.userInputs.size(); index++) {
            String answer = index == task.userInputs.size() - 1 ? result.answer() : "";
            Result turnResult =
                    new Result(
                            result.status(),
                            task.userInputs.get(index),
                            "",
                            result.intent(),
                            answer,
                            List.of(),
                            index == task.userInputs.size() - 1 ? result.evidence() : List.of(),
                            List.of());
            conversations.complete(
                    task.identity,
                    new ChatInput(
                            task.sessionId, task.userInputs.get(index), List.of(), task.requestId),
                    turnResult,
                    task.runId + ":input:" + index);
        }
        task.recordedInputs = task.userInputs.size();
        repository.save(task);
    }

    private void fail(String id, PersistentControl control, Throwable error) {
        repository.transaction(
                c -> {
                    var task = repository.get(id);
                    if (task == null
                            || !task.state.equals("RUNNING")
                            || task.generation != control.generation) return null;
                    String status =
                            error instanceof RunControl.BudgetExceeded
                                    ? "BUDGET_EXHAUSTED"
                                    : error instanceof RunControl.ContextLimit
                                            ? "CONTEXT_LIMIT"
                                            : error instanceof TimeoutException
                                                            || error
                                                                    instanceof
                                                                    RunControl.DeadlineExceeded
                                                    ? "TIMEOUT"
                                                    : error instanceof RunControl.Cancelled
                                                            ? "CANCELLED"
                                                            : "FAILED";
                    task.state = status;
                    task.result = emptyResult(task, status, "任务未能完成，请查看状态后重试。");
                    repository.save(task);
                    return null;
                });
    }

    private synchronized void recover() {
        if (closed) return;
        for (var task : repository.all()) {
            if ((task.state.equals("RUNNING") || task.state.equals("NEEDS_INPUT"))
                    && task.deadline <= System.currentTimeMillis()) {
                boolean expired =
                        repository.transaction(
                                c -> {
                                    var current = repository.get(task.runId);
                                    if (current == null
                                            || (!current.state.equals("RUNNING")
                                                    && !current.state.equals("NEEDS_INPUT"))
                                            || current.deadline > System.currentTimeMillis())
                                        return false;
                                    current.state = "TIMEOUT";
                                    current.generation++;
                                    current.result = emptyResult(current, "TIMEOUT", "任务已超过截止时间");
                                    repository.save(current);
                                    return true;
                                });
                var owner = owned.get(task.runId);
                if (expired && owner != null) owner.subscription.dispose();
            } else if (task.state.equals("RUNNING")
                    && !owned.containsKey(task.runId)
                    && hasCapacity(task.identity)) start(task);
        }
        repository.cleanup(System.currentTimeMillis() - settings.taskRetention().toMillis());
        conversations.cleanup();
    }

    private final class PersistentControl implements RunControl {
        private final String id;
        private final int generation;
        private final AccessPolicy.Identity identity;
        private volatile int consumed;

        PersistentControl(TaskRepository.Task task) {
            id = task.runId;
            generation = task.generation;
            identity = task.identity;
            consumed = task.consumedRevision;
        }

        public String runId() {
            return id;
        }

        public int revision() {
            return consumed;
        }

        public AccessPolicy.Identity identity() {
            return accessPolicy == null
                    ? identity
                    : accessPolicy.resolve(identity.tenantId(), identity.userId());
        }

        private TaskRepository.Task active() {
            identity();
            var task = repository.get(id);
            if (task == null || task.generation != generation || !task.state.equals("RUNNING"))
                throw new Cancelled("任务已取消或替换");
            if (task.deadline <= System.currentTimeMillis()) throw new DeadlineExceeded();
            return task;
        }

        public void checkActive() {
            active();
        }

        public Duration remaining() {
            var task = repository.get(id);
            return Duration.ofMillis(Math.max(0, task.deadline - System.currentTimeMillis()));
        }

        public void reserveModel(int estimatedTokens) {
            repository.transaction(
                    c -> {
                        var task = active();
                        if (estimatedTokens < 0) throw new IllegalArgumentException("Token 预留不能为负");
                        if (task.calls >= task.maxCalls) throw new BudgetExceeded("模型调用预算已耗尽");
                        repository.reserve(
                                "models:global",
                                1,
                                estimatedTokens,
                                settings.modelCallsPerMinute(),
                                settings.modelTokensPerMinute());
                        task.calls++;
                        repository.save(task);
                        return null;
                    });
        }

        @Override
        public void reserveModelStep(String stepKey, int estimatedTokens, int maxAttempts) {
            repository.transaction(
                    c -> {
                        var task = active();
                        int attempts =
                                Integer.parseInt(task.checkpoints.getOrDefault(stepKey, "0"));
                        if (attempts >= maxAttempts) throw new BudgetExceeded("模型步骤尝试预算已耗尽");
                        if (estimatedTokens < 0) throw new IllegalArgumentException("Token 预留不能为负");
                        if (task.calls >= task.maxCalls) throw new BudgetExceeded("模型调用预算已耗尽");
                        repository.reserve(
                                "models:global",
                                1,
                                estimatedTokens,
                                settings.modelCallsPerMinute(),
                                settings.modelTokensPerMinute());
                        task.calls++;
                        task.checkpoints.put(stepKey, Integer.toString(attempts + 1));
                        repository.save(task);
                        return null;
                    });
        }

        public String checkpoint(String key) {
            return repository.transaction(c -> active().checkpoints.get(key));
        }

        public void checkpoint(String key, String value) {
            repository.transaction(
                    c -> {
                        var task = active();
                        if (key.equals("progress") && task.revision != consumed) return null;
                        if (key.length() > 1024 || (value != null && value.length() > 131072))
                            throw new ContextLimit("检查点内容超过存储上限");
                        if (!task.checkpoints.containsKey(key) && task.checkpoints.size() >= 512)
                            throw new ContextLimit("检查点数量超过上限");
                        if (value == null) task.checkpoints.remove(key);
                        else task.checkpoints.put(key, value);
                        repository.save(task);
                        return null;
                    });
        }

        public String drainInput() {
            return repository.transaction(
                    c -> {
                        var task = active();
                        if (task.pending.isEmpty()) return null;
                        String text = String.join("\n", task.pending);
                        task.pending.clear();
                        consumed = task.revision;
                        task.consumedRevision = consumed;
                        var history = new ArrayList<>(task.request.history());
                        history.add(new Turn(task.request.question(), "当前任务此前的用户要求"));
                        task.request = new ChatInput(task.sessionId, text, history, task.requestId);
                        repository.save(task);
                        return text;
                    });
        }

        public void event(String event) {
            repository.transaction(
                    c -> {
                        var task = active();
                        if (task.events.size() < 200)
                            task.events.add(event.length() > 512 ? event.substring(0, 512) : event);
                        repository.save(task);
                        return null;
                    });
        }

        public int modelCalls() {
            return repository.get(id).calls;
        }
    }

    private TaskRepository.Task visible(AccessPolicy.Identity identity, String id) {
        var task = repository.get(id);
        if (task == null || !sameIdentity(task, identity))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return task;
    }

    private boolean sameIdentity(TaskRepository.Task task, AccessPolicy.Identity identity) {
        return task.tenantId.equals(identity.tenantId()) && task.userId.equals(identity.userId());
    }

    private boolean hasCapacity(AccessPolicy.Identity identity) {
        return owned.size() < settings.maxConcurrentRequests()
                && owned.values().stream()
                                .filter(
                                        o ->
                                                o.identity.tenantId().equals(identity.tenantId())
                                                        && o.identity
                                                                .userId()
                                                                .equals(identity.userId()))
                                .count()
                        < settings.maxConcurrentPerUser();
    }

    private void checkCapacity(AccessPolicy.Identity identity) {
        if (!hasCapacity(identity)) throw busy();
    }

    private void reserveNewTask(AccessPolicy.Identity identity) {
        try {
            repository.reserve(
                    "tasks:" + identity.tenantId() + ":" + identity.userId(),
                    1,
                    0,
                    settings.tasksPerUserMinute(),
                    Long.MAX_VALUE);
        } catch (RunControl.BudgetExceeded e) {
            throw busy();
        }
    }

    private View view(AccessPolicy.Identity identity, TaskRepository.Task task) {
        Result result = task.result == null ? emptyResult(task, task.state, "任务执行中") : task.result;
        for (Evidence evidence : result.evidence()) {
            if (!identity.allows(evidence.tool()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return new View(
                result,
                task.runId,
                task.revision,
                task.requestId,
                task.state,
                task.calls,
                task.deadline,
                task.replacedBy);
    }

    private Result emptyResult(TaskRepository.Task task, String status, String answer) {
        String saved = task.checkpoints.get("progress");
        if (saved != null) {
            Result progress = repository.decode(saved, Result.class);
            return new Result(
                    Status.valueOf(status),
                    progress.originalQuery(),
                    progress.rewrittenQuery(),
                    progress.intent(),
                    answer,
                    progress.agents(),
                    progress.evidence(),
                    List.copyOf(task.events));
        }
        return new Result(
                Status.valueOf(status),
                task.request.question(),
                "",
                Intent.UNKNOWN,
                answer,
                List.of(),
                List.of(),
                List.copyOf(task.events));
    }

    private String requestHash(ChatInput request) {
        return hash(
                repository.encode(
                        new ChatInput(
                                request.sessionId(), request.question(), request.history(), null)));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void validateRequest(ChatInput request) {
        if (request == null || blank(request.sessionId()) || blank(request.question())) throw invalid();
        // 过大属于容量问题（413），字段无效才是 400。
        if (request.sessionId().length() > 200
                || request.question().length() > 2000
                || (request.requestId() != null && request.requestId().length() > 200))
            throw tooLarge();
        for (Turn turn : request.history())
            if (turn == null || blank(turn.question()) || turn.answer() == null) throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    @Override
    public synchronized void close() {
        closed = true;
        recovery.shutdownNow();
        for (var run : new ArrayList<>(owned.values())) run.subscription.dispose();
        scheduler.dispose();
    }
}
