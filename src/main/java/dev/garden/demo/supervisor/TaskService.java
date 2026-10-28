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

/** 持久任务持有执行订阅，HTTP 等待者取消只会离开等待，不会取消任务。 */
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
    private final BiFunction<Request, RunControl, Mono<Result>> runner;
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
        this(repository, conversations, settings, supervisor::run, accessPolicy);
    }

    TaskService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings,
            BiFunction<Request, RunControl, Mono<Result>> runner) {
        this(repository, conversations, settings, runner, null);
    }

    TaskService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings,
            BiFunction<Request, RunControl, Mono<Result>> runner,
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

    public Mono<View> submit(AccessPolicy.Identity identity, Request request, boolean async) {
        return Mono.fromCallable(() -> create(identity, request))
                .subscribeOn(scheduler)
                .flatMap(
                        task ->
                                async
                                        ? Mono.just(view(identity, task))
                                        : await(identity, task.runId));
    }

    private synchronized TaskRepository.Task create(
            AccessPolicy.Identity identity, Request request) {
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
        if (task.state.equals("RUNNING") && !owned.containsKey(task.runId)) start(task);
        return task;
    }

    private TaskRepository.Task newTask(
            AccessPolicy.Identity identity, Request request, String requestId, String hash) {
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
                        new Request(
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
                                Request replacement =
                                        new Request(
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

    private void start(TaskRepository.Task task) {
        if (closed || owned.containsKey(task.runId)) return;
        var ownership = new OwnedRun(task.identity);
        owned.put(task.runId, ownership);
        var control = new PersistentControl(task);
        Disposable execution =
                cycle(task.runId, control)
                        .subscribeOn(scheduler)
                        .doFinally(
                                signal -> {
                                    synchronized (TaskService.this) {
                                        owned.remove(task.runId, ownership);
                                    }
                                })
                        .subscribe(ignored -> {}, error -> fail(task.runId, control, error));
        ownership.subscription.update(execution);
    }

    private Mono<Void> cycle(String id, PersistentControl control) {
        return Mono.defer(
                () -> {
                    TaskRepository.Task task = repository.get(id);
                    control.checkActive();
                    return runner.apply(task.request, control)
                            .switchIfEmpty(Mono.error(new IllegalStateException("执行没有返回结果")))
                            .timeout(control.remaining())
                            .publishOn(scheduler)
                            .flatMap(
                                    result -> {
                                        boolean finished =
                                                repository.transaction(
                                                        c -> {
                                                            var current = repository.get(id);
                                                            if (current.generation
                                                                            != control.generation
                                                                    || !current.state.equals(
                                                                            "RUNNING")) return true;
                                                            if (current.revision
                                                                    != control.revision())
                                                                return false;
                                                            current.result = result;
                                                            current.state = result.status().name();
                                                            repository.save(current);
                                                            saveConversation(current, result);
                                                            return true;
                                                        });
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
                    new Request(
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
                        task.request = new Request(task.sessionId, text, history, task.requestId);
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

    private String requestHash(Request request) {
        return hash(
                repository.encode(
                        new Request(
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

    static void validateRequest(Request request) {
        if (request == null
                || blank(request.sessionId())
                || request.sessionId().length() > 200
                || blank(request.question())
                || request.question().length() > 2000
                || (request.requestId() != null && request.requestId().length() > 200))
            throw invalid();
        for (Turn turn : request.history())
            if (turn == null || blank(turn.question()) || turn.answer() == null) throw invalid();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    private static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
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
