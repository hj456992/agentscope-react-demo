package dev.garden.demo.supervisor.service;

import dev.garden.demo.supervisor.RunControl;
import dev.garden.demo.supervisor.AgentLifecycle;
import dev.garden.demo.supervisor.pojo.dto.AgentSnapshotDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentMessageDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentWaitDTO;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import dev.garden.demo.supervisor.config.SupervisorSettings;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.RequestDTO;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;
import dev.garden.demo.supervisor.pojo.param.InstructionParam;
import dev.garden.demo.supervisor.pojo.res.InstructionRes;
import dev.garden.demo.supervisor.pojo.res.RunRes;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** 持久任务持有执行订阅，HTTP 等待者取消只会离开等待，不会取消任务。 */
@Service
public final class TaskService implements AutoCloseable {
    private final TaskStoreService store;
    private final SupervisorSettings settings;
    private final AccessPolicy accessPolicy;
    private final BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner;
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
        final IdentityDTO identity;
        final boolean child;
        final CountDownLatch terminated = new CountDownLatch(1);
        final Disposable.Swap subscription = Disposables.swap();

        OwnedRun(IdentityDTO identity, boolean child) {
            this.identity = identity;
            this.child = child;
        }
    }

    /**
     * 唯一构造器：只做字段赋值并启动后台恢复线程，不执行模型调用。
     *
     * <p>容器按构造器注入：TaskStoreService（带 @Transactional 代理）、SupervisorSettings、
     * 执行函数 runner（supervisorRunner bean，即 supervisor::run）和 AccessPolicy。
     * 直接构造时也只用这一个构造器，不另加重载；
     * accessPolicy 允许为 null，此时执行期使用任务保存的身份快照。
     */
    public TaskService(
            TaskStoreService store,
            SupervisorSettings settings,
            BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner,
            AccessPolicy accessPolicy) {
        this.store = store;
        this.settings = settings;
        this.runner = runner;
        this.accessPolicy = accessPolicy;
        recovery.scheduleWithFixedDelay(
                () -> {
                    if (!closed) {
                        scheduler.schedule(
                                () -> {
                                    try {
                                        recover();
                                    } catch (RuntimeException ignored) {
                                        /* 下次有界扫描重试；不回显存储细节。 */
                                    }
                                });
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);
    }

    /**
     * 对话接口的受理入口：先建任务（或复用幂等结果），再按同步 / 异步两条分支交付结果。
     *
     * <p>方法只组装 {@link Mono}，被调用时业务代码一行都不执行；建任务发生在订阅之后，
     * 在 {@code scheduler} 线程上跑。任务执行是另一条独立订阅，客户端断开只取消“等待”，
     * 已受理的任务仍会继续跑，要停止必须调用指令接口。
     *
     * <p>重复提交同一 requestId 且内容一致时不会新建任务，返回原任务的当前快照。
     * 建任务失败（参数 400、编号或会话冲突 409、容量或配额不足 429）走 {@code onError}；
     * 同步模式的任务失败 / 超时不是异常，而是 FAILED / TIMEOUT 状态的 {@link RunRes}。
     */
    public Mono<RunRes> submit(IdentityDTO identity, RequestDTO request, boolean async) {
        // fromCallable：把“同步的建任务”包成一个 Mono。
        // 注意它此刻不执行，要等有人订阅时才去跑里面的 create(...)。
        return Mono.fromCallable(() -> create(identity, request))
                // subscribeOn：指定上面那段建任务代码在哪个线程池跑。
                // 放到有界弹性池，既不让 HTTP 请求线程被数据库写入占住，
                // 也用有界队列兜住并发，避免无限堆积。
                .subscribeOn(scheduler)
                // flatMap：等任务建好、拿到 task 之后，再去接“交付结果”的那段流，
                // 并把它压平成同一条流，而不是套成 Mono<Mono<...>>。
                .flatMap(
                        // async 为 true 时：任务已经在后台跑了，
                        // 这里只要一份当前快照就能立刻兑现，Controller 回 202。
                        // async 为 false 时：走 await，一直轮询到任务不再是 RUNNING。
                        task ->
                                async
                                        // Mono.just：直接把现成对象发出去，不产生新的等待。
                                        ? Mono.just(store.view(identity, task))
                                        // await：每 25 毫秒回查一次，直到任务结束才把结果交出去。
                                        : await(identity, task.runId));
    }

    /**
     * 受理一次对话：校验参数、幂等复用、会话互斥、容量与配额检查，落库后按需启动执行。
     *
     * <p>只能由 {@link #submit} 的 {@code Mono.fromCallable} 在调度线程上调用，
     * 不要在请求线程直接调用（会阻塞等数据库事务）。本方法不加 {@code @Transactional}，
     * 事务边界在 {@link TaskStoreService#create}；锁顺序是 TaskService → TaskStoreService。
     * {@code synchronized} 保护的是本类的 {@code owned} 映射，临界区内只有一次短事务。
     */
    private synchronized TaskDO create(IdentityDTO identity, RequestDTO request) {
        // 建任务本身交给存储层：参数校验、requestId 幂等复用、同会话只允许一个 RUNNING、
        // 存储上限和准入配额都在 TaskStoreService#create 里，那才是唯一的事务边界。
        // 第三个参数是容量回调：它会在存储层的事务里执行，并发满了直接抛 429，
        // 这样“检查并发”和“落库”之间不会被别的请求插队。
        TaskDO task = store.create(identity, request, () -> checkCapacity(identity));
        // 只有两种情况才启动：任务确实在 RUNNING，而且本实例手里还没有它的执行订阅。
        // 幂等重放拿到的旧任务可能早就在跑了（owned 里已有），再 start 一次会跑两遍。
        if (task.state.equals("RUNNING") && !owned.containsKey(task.runId)) {
            // start 只是登记归属、另起一条独立订阅，不会在这里等任务跑完；
            // 所以本方法返回时，任务已经在后台跑了。
            start(task);
        }
        // 返回的是“刚受理那一刻”的快照：异步模式直接拿它回 202。
        // 之后任务状态再变，这个对象也不会跟着变，要看最新进度得重新查存储。
        return task;
    }

    public Mono<RunRes> get(IdentityDTO identity, String id) {
        return Mono.fromCallable(() -> store.view(identity, store.visible(identity, id)))
                .subscribeOn(scheduler);
    }

    private Mono<RunRes> await(IdentityDTO identity, String id) {
        return get(identity, id)
                .flatMap(
                        view ->
                                view.state().equals("RUNNING")
                                        ? Mono.delay(Duration.ofMillis(25))
                                                .then(Mono.defer(() -> await(identity, id)))
                                        : Mono.just(view));
    }

    public Mono<InstructionRes> instruct(IdentityDTO identity, InstructionParam input) {
        return Mono.fromCallable(() -> applyInstruction(identity, input))
                .subscribeOn(scheduler);
    }

    private synchronized InstructionRes applyInstruction(
            IdentityDTO identity, InstructionParam input) {
        String id = input.runId();
        InstructionRes reply =
                store.applyInstruction(
                        identity,
                        input,
                        () -> {
                            if (!owned.containsKey(id)) {
                                checkCapacity(identity);
                            }
                        });
        TaskDO original = store.get(id);
        if (original != null
                && (original.state.equals("CANCELLED") || original.state.equals("REPLACED"))) {
            OwnedRun old = owned.get(id);
            if (old != null) {
                old.subscription.dispose();
            }
        }
        TaskDO target = store.get(reply.runId());
        if (target != null
                && target.state.equals("RUNNING")
                && !owned.containsKey(target.runId)
                && hasCapacity(target.identity)) {
            start(target);
        }
        return reply;
    }

    /**
     * 把一个已经落库、状态为 RUNNING 的任务接到本实例的执行循环上：
     * 先在 owned 里登记“这个任务归我跑”，再单独起一条订阅去执行 cycle。
     *
     * <p>这条订阅是整条链的终点（{@code subscribe} 之后没有下游了），所以任务的生命周期
     * 和 HTTP 请求无关：客户端断开、Controller 那条 Mono 被取消，都不影响这里已经开始执行。
     * 要停任务只能走指令接口或 {@link #close()}。
     *
     * <p>约定：只在持锁的同步方法里调用（{@link #create}、applyInstruction、recover），
     * 并且同一个 runId 只会成功启动一次——已关闭或已有归属时直接返回，避免重复执行。
     * 结束时无论成功、失败还是取消，{@code doFinally} 都会释放归属，
     * 之后恢复扫描可以重新接手。
     */
    private void start(TaskDO requested) {
        // 两种情况不启动：服务已经关了，或者这个 runId 已经有执行订阅了。
        // recover() 每秒扫一次，可能和 create() 抢同一个任务，这里就是防止重复启动的检查。
        if (closed || owned.containsKey(requested.runId)) {
            return;
        }
        TaskDO task = store.claimExecution(requested.runId);
        // 先登记归属：OwnedRun 记录身份（并发额度统计要用），
        // subscription 是可替换的订阅句柄，停止任务和关闭服务都靠它取消。
        OwnedRun ownership = new OwnedRun(task.identity, task.parentRunId != null);
        owned.put(task.runId, ownership);
        // 控制对象绑定这一代执行：后面校验期限、预留预算、检查版本时都要带上它，
        // 这样旧执行的操作不会误伤新版本。
        PersistentControl control = new PersistentControl(task);
        // 到这里才真正开跑：cycle 返回的是惰性链，只有 subscribe 才会开始执行。
        Disposable execution =
                cycle(task.runId, control)
                        // 整个执行循环放到调度线程池，不占用 HTTP 请求线程。
                        .subscribeOn(scheduler)
                        // 收尾：不管正常结束、出错还是被取消，最后都把自己从 owned 里删掉。
                        .doFinally(
                                signal -> {
                                    // 改 owned 要加锁，和取消、恢复扫描互斥。
                                    // remove(key, value) 只在“自己还是当前归属”时才删，
                                    // 防止误删之后重新接手的那个登记。
                                    synchronized (TaskService.this) {
                                        owned.remove(task.runId, ownership);
                                    }
                                    ownership.terminated.countDown();
                                })
                        // subscribe 是链的终点：任务从这里开始独立执行，不再需要调用方等待。
                        // 第一个回调接收正常结果（cycle 是 Mono<Void>，没有值，直接忽略）；
                        // 第二个回调把异常写进存储，把任务标记为失败。
                        .subscribe(ignored -> {}, error -> fail(task.runId, control, error));
        // 把订阅句柄交给登记对象保存，之后取消或关闭时就能用 dispose 结束这条订阅。
        ownership.subscription.update(execution);
    }

    /**
     * 一个任务的执行主循环：跑一轮 → 尝试提交结果 → 如果期间又来了新指令，就带着最新输入再跑一轮。
     *
     * <p>只有当结果按“最新版本”提交成功（或者任务已被取消、替换、不再是 RUNNING）时循环才结束，
     * 返回的 {@link Mono} 发出完成信号；取消、超期、执行异常都以 {@code onError} 结束，
     * 由 {@link #start} 里订阅的 error 回调交给存储层标记最终状态。
     *
     * <p>每一轮都会重新读库、重新检查期限，所以补充 / 修改的输入能在下一轮生效；
     * 期限是任务的绝对截止时间，多轮共享，不会因为重跑而续期。
     */
    private Mono<Void> cycle(String id, PersistentControl control) {
        // defer：把里面整段推迟到“订阅那一刻”才执行。
        // 好处是每一轮都在真正开跑前才去读库，拿到的永远是最新状态，
        // 而不是组装这条链时读到的旧状态。
        return Mono.defer(
                () -> {
                    // 每轮重新查一次任务：期间用户可能补充或修改了问题，
                    // 最新原话就写在这份 task.request 里。
                    TaskDO task = store.get(id);
                    // 检查这一代执行还算不算数：任务被取消 / 替换、或已过绝对截止时间时，
                    // 这里会抛 Cancelled / DeadlineExceeded，直接把循环打断。
                    control.checkActive();
                    // 构造器参数 runner（执行函数）是注入的 BiFunction，
                    // SupervisorConfiguration 里的 supervisorRunner bean 返回的就是 supervisor::run。
                    // 注意：这一行只是调用 Supervisor.run 拿回一条 Mono 链，不会当场打模型；
                    // 真正的模型请求在 Supervisor.step 的 model.stream(...)，
                    // 要等这条链被订阅（start 里的 subscribe）之后才发出去。
                    // 线程上，这一段跑在 scheduler（supervisor-runtime）里，不占 HTTP 请求线程。
                    return runner.apply(task.request, control)
                            // 正常情况下执行函数一定会给结果；空结果属于实现错误，
                            // 转成错误信号，别让循环悄悄空转。
                            .switchIfEmpty(Mono.error(new IllegalStateException("执行没有返回结果")))
                            // 这一轮最多等到任务的绝对截止时间（传的是“还剩多久”）。
                            // 到点抛 TimeoutException，最终由 start 的 error 回调落库成 TIMEOUT。
                            // 因为多轮都用同一个截止时间，重跑不会把期限往后顺延。
                            .timeout(control.remaining())
                            // 切到调度线程池：后面的提交结果、写数据库都不在原来的执行线程上跑。
                            .publishOn(scheduler)
                            // 结果回来之后开始处理：先看能不能按当前版本提交，再决定要不要再来一轮。
                            .flatMap(
                                    result -> {
                                        // 提交时带上这一轮消费的输入版本：
                                        // 如果跑的过程中用户又发了补充 / 修改，版本对不上，返回 false，结果作废。
                                        // 返回 true 表示“这一轮到此为止”：要么提交成功，要么任务已被取消 / 替换。
                                        boolean finished =
                                                commitResult(
                                                        id,
                                                        control.generation,
                                                        control.revision(),
                                                        control.mailboxRevision,
                                                        result);
                                        // true：任务已有最终状态，发完成信号，整条链结束。
                                        // false：说明跑的时候又来了新指令，带着最新输入再跑一轮。
                                        // 递归写在回调里，只有拿到结果才会拼下一轮，不会在组装时无限展开。
                                        return finished ? Mono.<Void>empty() : cycle(id, control);
                                    });
                });
    }

    private synchronized boolean commitResult(String id, int generation, int revision, long mailboxRevision, ResultDTO result) {
        return store.commitResult(id, generation, revision, mailboxRevision, result);
    }

    private synchronized void fail(String id, PersistentControl control, Throwable error) {
        store.fail(id, control.generation, error);
    }

    private synchronized void recover() {
        if (closed) {
            return;
        }
        for (TaskDO task : store.all()) {
            if (!task.accepted) {
                store.discardChild(task.runId);
                continue;
            }
            if ((task.state.equals("RUNNING") || task.state.equals("NEEDS_INPUT"))
                    && task.deadline <= System.currentTimeMillis()) {
                boolean expired = store.expire(task.runId);
                OwnedRun owner = owned.get(task.runId);
                if (expired && owner != null) {
                    owner.subscription.dispose();
                }
            } else if (task.state.equals("RUNNING")
                    && !owned.containsKey(task.runId)
                    && (task.parentRunId == null ? hasCapacity(task.identity) : hasChildCapacity(task))) {
                start(task);
            }
        }
        store.cleanup(System.currentTimeMillis() - settings.taskRetention().toMillis());
        store.cleanupConversations();
    }

    /** 临时记录准备完成后以原子状态提交接受边界；调用方取消只能清理尚未接受的子会话。 */
    private Mono<AgentSnapshotDTO> spawnChild(PersistentControl caller, String taskName, String message,
            String role, String forkTurns, Map<String, String> inherited) {
        return Mono.create(sink -> {
            // 0=准备中，-1=调用方已取消，1=接受边界已提交；取消不能撤销 1。
            AtomicInteger admission = new AtomicInteger();
            sink.onCancel(() -> admission.compareAndSet(0, -1));
            scheduler.schedule(() -> {
                synchronized (TaskService.this) {
                    if (admission.get() == -1) {
                        return;
                    }
                    TaskDO prepared = null;
                    try {
                        caller.checkActive();
                        TaskDO parent = store.get(caller.id);
                        if (!hasChildCapacity(parent)) {
                            throw new RunControl.BudgetExceeded("子会话并发额度已满");
                        }
                        prepared = store.prepareChild(caller.id, caller.generation,
                                taskName, message, role, forkTurns, inherited);
                        if (admission.get() == -1) {
                            store.discardChild(prepared.runId);
                            return;
                        }
                        TaskDO accepted = store.acceptChild(prepared.runId);
                        AgentSnapshotDTO receipt = store.agentSnapshot(accepted);
                        if (!admission.compareAndSet(0, 1)) {
                            store.discardChild(accepted.runId);
                            return;
                        }
                        // 接受后不再检查父回合取消；执行由 TaskService 独立拥有。
                        start(accepted);
                        sink.success(receipt);
                    } catch (Throwable error) {
                        if (prepared != null && admission.get() != 1) {
                            store.discardChild(prepared.runId);
                        }
                        sink.error(error);
                    }
                }
            });
        });
    }

    private boolean hasChildCapacity(TaskDO task) {
        String root = task.rootRunId == null ? task.runId : task.rootRunId;
        return !closed && owned.keySet().stream().map(store::get)
                .filter(java.util.Objects::nonNull)
                .filter(t -> t.parentRunId != null)
                .filter(t -> root.equals(t.rootRunId == null ? t.runId : t.rootRunId)).count()
                < AgentLifecycle.MAX_ACTIVE_CHILDREN;
    }

    private final class PersistentControl implements RunControl {
        private final String id;
        private final int generation;
        private final IdentityDTO identity;
        private volatile int consumed;
        private final String role, path;
        private long inputAck, mailboxAck, mailboxRevision;

        PersistentControl(TaskDO task) {
            id = task.runId;
            generation = task.generation;
            identity = task.identity;
            consumed = task.consumedRevision;
            mailboxRevision = task.consumedMailboxRevision;
            role = task.agentRole;
            path = task.agentPath;
        }

        public String runId() {
            return id;
        }

        public String agentRole() { return role; }
        public String agentPath() { return path; }
        public String turnId() { return id + ":" + generation; }

        public Mono<AgentSnapshotDTO> spawnAgent(String taskName, String message, String agentRole,
                String forkTurns, Map<String, String> inherited) {
            return spawnChild(this, taskName, message, agentRole, forkTurns, inherited);
        }

        public Mono<Void> sendMessage(String target, String message, boolean triggerTurn) {
            return Mono.fromRunnable(() -> {
                synchronized (TaskService.this) {
                    checkActive();
                    TaskDO receiver = store.agentTarget(id, target);
                    if (triggerTurn && !receiver.state.equals("RUNNING") && !hasChildCapacity(receiver)) {
                        throw new RunControl.BudgetExceeded("子会话并发额度已满");
                    }
                    receiver = store.sendAgent(id, target, message, triggerTurn);
                    if (receiver.state.equals("RUNNING") && !owned.containsKey(receiver.runId)) {
                        start(receiver);
                    }
                }
            });
        }

        public Mono<AgentSnapshotDTO> interruptAgent(String target) {
            return Mono.fromCallable(() -> {
                synchronized (TaskService.this) {
                    checkActive();
                    AgentSnapshotDTO previous = store.interruptAgent(id, target);
                    OwnedRun running = owned.get(previous.agentId());
                    if (running != null) {
                        running.subscription.dispose();
                    }
                    checkEvidence(previous.result());
                    return previous;
                }
            });
        }

        public List<AgentSnapshotDTO> listAgents(String prefix) {
            synchronized (TaskService.this) {
                identity();
                List<AgentSnapshotDTO> agents = store.listAgents(id, prefix);
                agents.forEach(agent -> checkEvidence(agent.result()));
                return agents;
            }
        }

        public List<AgentMessageDTO> drainMailbox() {
            synchronized (TaskService.this) {
                identity();
                var delivery = store.deliverMailbox(id, generation);
                delivery.messages().forEach(message -> checkEvidence(message.result()));
                mailboxRevision = delivery.consumedMailboxRevision();
                mailboxAck = delivery.deliveryId();
                return delivery.messages();
            }
        }

        private void checkEvidence(ResultDTO result) {
            if (result != null) {
                IdentityDTO current = identity();
                if (result.evidence().stream().anyMatch(e -> !current.allows(e.tool()))) {
                    throw new RunControl.Cancelled("子会话结果权限已变化，不能继续回放");
                }
            }
        }

        public List<String> drainInputs() {
            synchronized (TaskService.this) {
                identity();
                var input = store.drainInputs(id, generation);
                consumed = input.consumedRevision();
                inputAck = input.deliveryId();
                return input.inputs();
            }
        }

        public Mono<AgentWaitDTO> waitAgent(long timeoutMs) {
            return Mono.defer(() -> waitUntil(System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos()));
        }

        private Mono<AgentWaitDTO> waitUntil(long deadline) {
            return Mono.defer(() -> {
                synchronized (TaskService.this) {
                    checkActive();
                    TaskDO latest = store.get(id);
                    if (!latest.pending.isEmpty() || !latest.inflightInputs.isEmpty()) {
                        return Mono.just(new AgentWaitDTO("Wait interrupted by new input.", false));
                    }
                    if (!latest.mailbox.isEmpty() || !latest.inflightMailbox.isEmpty()) {
                        return Mono.just(new AgentWaitDTO("Wait completed.", false));
                    }
                    if (System.nanoTime() >= deadline) {
                        return Mono.just(new AgentWaitDTO("Wait timed out.", true));
                    }
                }
                return Mono.delay(Duration.ofMillis(10)).then(waitUntil(deadline));
            });
        }

        public int revision() {
            return consumed;
        }

        public IdentityDTO identity() {
            return accessPolicy == null
                    ? identity
                    : accessPolicy.resolve(identity.tenantId(), identity.userId());
        }

        private TaskDO active() {
            if (closed) {
                throw new RunControl.Cancelled("服务已关闭");
            }
            identity();
            return store.active(id, generation);
        }

        public void checkActive() {
            active();
        }

        public Duration remaining() {
            TaskDO task = store.get(id);
            return Duration.ofMillis(Math.max(0, task.deadline - System.currentTimeMillis()));
        }

        public void reserveModel(int estimatedTokens) {
            synchronized (TaskService.this) {
                checkActive();
                store.reserveModel(id, generation, estimatedTokens);
            }
        }

        @Override
        public void reserveModelStep(String stepKey, int estimatedTokens, int maxAttempts) {
            synchronized (TaskService.this) {
                checkActive();
                store.reserveModelStep(id, generation, stepKey, estimatedTokens, maxAttempts);
            }
        }

        public String checkpoint(String key) {
            identity();
            return store.checkpoint(id, generation, key);
        }

        public void checkpoint(String key, String value) {
            synchronized (TaskService.this) {
                checkActive();
                store.checkpointWithDeliveryAck(id, generation, consumed, key, value, inputAck, mailboxAck);
                if (key.equals("loop:v3.3")) {
                    inputAck = 0;
                    mailboxAck = 0;
                }
            }
        }

        public String drainInput() {
            synchronized (TaskService.this) {
                identity();
                var drained = store.drainInput(id, generation);
                if (drained == null) {
                    return null;
                }
                consumed = drained.consumedRevision();
                return drained.text();
            }
        }

        public void event(String event) {
            synchronized (TaskService.this) {
                checkActive();
                store.event(id, generation, event);
            }
        }

        public int modelCalls() {
            return store.modelCalls(id);
        }
    }

    private boolean hasCapacity(IdentityDTO identity) {
        return owned.values().stream().filter(o -> !o.child).count() < settings.maxConcurrentRequests()
                && owned.values().stream()
                                .filter(
                                        o ->
                                                !o.child && o.identity.tenantId().equals(identity.tenantId())
                                                        && o.identity
                                                                .userId()
                                                                .equals(identity.userId()))
                                .count()
                        < settings.maxConcurrentPerUser();
    }

    private void checkCapacity(IdentityDTO identity) {
        if (!hasCapacity(identity)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Override
    public void close() {
        List<OwnedRun> executions;
        synchronized (this) {
            closed = true;
            recovery.shutdownNow();
            executions = new ArrayList<>(owned.values());
        }
        // Fence 后取消全部独立订阅；不要持锁等待 doFinally，否则无法完成归属清理。
        for (OwnedRun run : executions) {
            run.subscription.dispose();
        }
        for (OwnedRun run : executions) {
            try {
                if (!run.terminated.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("运行树关闭未完成");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("运行树关闭等待被打断", error);
            }
        }
        scheduler.dispose();
    }
}
