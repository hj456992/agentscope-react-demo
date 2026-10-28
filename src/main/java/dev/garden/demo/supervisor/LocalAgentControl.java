package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.*;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;

/** CLI 使用的 V2 内存运行树；每个子回合独立订阅，共享预算但不共享父取消状态。 */
final class LocalAgentControl implements RunControl {
    private static final class Tree {
        final LimitsDTO limits;
        final long deadline;
        final Map<String, Node> nodes = new LinkedHashMap<>();
        BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner;
        int calls;
        Tree(LimitsDTO limits) {
            this.limits = limits;
            this.deadline = System.nanoTime() + limits.timeout().toNanos();
        }
    }

    private static final class Node {
        final String id = UUID.randomUUID().toString();
        final String path, parent, role;
        final Map<String, String> checkpoints = new LinkedHashMap<>();
        final List<AgentMessageDTO> mailbox = new ArrayList<>();
        String state = "RUNNING";
        int generation = 1;
        ResultDTO result;
        Disposable.Swap execution = Disposables.swap();
        Node(String path, String parent, String role) {
            this.path = path;
            this.parent = parent;
            this.role = role;
        }
    }

    private final Tree tree;
    private final Node node;
    private final int generation;

    private LocalAgentControl(Tree tree, Node node, int generation) {
        this.tree = tree;
        this.node = node;
        this.generation = generation;
    }

    static RunControl root(LimitsDTO limits) {
        Tree tree = new Tree(limits);
        Node root = new Node("/root", null, "supervisor");
        tree.nodes.put(root.path, root);
        return new LocalAgentControl(tree, root, root.generation);
    }

    public String runId() { return node.id; }
    public int revision() { return generation; }
    public String agentRole() { return node.role; }
    public String agentPath() { return node.path; }
    public IdentityDTO identity() { return IdentityDTO.local(); }
    public Duration remaining() { return Duration.ofNanos(Math.max(0, tree.deadline - System.nanoTime())); }

    public void checkActive() {
        synchronized (tree) {
            if (generation != node.generation || !node.state.equals("RUNNING")) {
                throw new Cancelled("本轮已中断");
            }
            if (remaining().isZero()) {
                throw new DeadlineExceeded();
            }
        }
    }

    public void bindRunner(BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner) {
        synchronized (tree) { tree.runner = runner; }
    }

    public void reserveModel(int tokens) {
        synchronized (tree) {
            checkActive();
            if (tokens < 0) {
                throw new IllegalArgumentException("Token 预留不能为负");
            }
            if (tree.calls >= tree.limits.modelCalls()) {
                throw new BudgetExceeded("模型调用预算已耗尽");
            }
            tree.calls++;
        }
    }

    public int modelCalls() { synchronized (tree) { return tree.calls; } }
    public String checkpoint(String key) { synchronized (tree) { return node.checkpoints.get(key); } }
    public void checkpoint(String key, String value) {
        synchronized (tree) {
            checkActive();
            if (value == null) {
                node.checkpoints.remove(key);
            } else {
                node.checkpoints.put(key, value);
            }
        }
    }
    public String drainInput() { checkActive(); return null; }
    public void event(String event) {}

    public Mono<AgentSnapshotDTO> spawnAgent(String taskName, String message, String role,
            String forkTurns, Map<String, String> inheritedCheckpoints) {
        return Mono.fromCallable(() -> {
            synchronized (tree) {
                checkActive();
                AgentLifecycle.validateMessage(message);
                String path = AgentLifecycle.childPath(node.path, taskName);
                if (tree.nodes.containsKey(path)) {
                    throw new IllegalArgumentException("任务名已存在：" + path);
                }
                if (tree.runner == null) {
                    throw new IllegalStateException("未绑定 Agent 执行器");
                }
                // CLI 默认最多八条活动执行；是本地资源配置，不声称来自上游默认值。
                if (tree.nodes.values().stream().filter(n -> n.parent != null && n.state.equals("RUNNING")).count() >= AgentLifecycle.MAX_ACTIVE_CHILDREN) {
                    throw new BudgetExceeded("子会话并发额度已满");
                }
                Node child = new Node(path, node.path, AgentLifecycle.role(role));
                if (!"none".equalsIgnoreCase(forkTurns)) {
                    child.checkpoints.putAll(inheritedCheckpoints);
                }
                tree.nodes.put(path, child);
                start(child, message);
                return snapshot(child);
            }
        });
    }

    private void start(Node target, String message) {
        target.state = "RUNNING";
        target.result = null;
        target.execution = Disposables.swap();
        LocalAgentControl control = new LocalAgentControl(tree, target, target.generation);
        Disposable execution = Mono.defer(() -> tree.runner.apply(
                        new RequestDTO(target.id, message, List.of(), UUID.randomUUID().toString()), control))
                .timeout(control.remaining())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(result -> finish(target, control.generation, result),
                        error -> finish(target, control.generation, new ResultDTO(
                                error instanceof Cancelled ? Status.CANCELLED : Status.FAILED,
                                message, "", Intent.UNKNOWN, "Agent turn failed.",
                                List.of(), List.of(), List.of())));
        target.execution.update(execution);
    }

    private void finish(Node target, int turnGeneration, ResultDTO result) {
        synchronized (tree) {
            if (target.generation != turnGeneration || !target.state.equals("RUNNING")) {
                return;
            }
            target.state = result.status().name();
            target.result = result;
            Node parent = tree.nodes.get(target.parent);
            if (parent != null && !AgentLifecycle.status(target.state).equals("interrupted")) {
                parent.mailbox.add(AgentLifecycle.result(target.path, parent.path, result));
            }
        }
    }

    public Mono<Void> sendMessage(String target, String message, boolean triggerTurn) {
        return Mono.fromRunnable(() -> {
            synchronized (tree) {
                AgentLifecycle.validateMessage(message);
                Node receiver = resolve(target);
                if (triggerTurn && receiver.parent == null) {
                    throw new IllegalArgumentException("追加任务不能发给根 Agent");
                }
                receiver.mailbox.add(new AgentMessageDTO(node.path, receiver.path,
                        triggerTurn ? "FOLLOWUP" : "MESSAGE", message, null));
                if (triggerTurn && !receiver.state.equals("RUNNING")) {
                    receiver.generation++;
                    receiver.checkpoints.put("agent:turn-start", "true");
                    start(receiver, message);
                }
            }
        });
    }

    public Mono<AgentSnapshotDTO> interruptAgent(String target) {
        return Mono.fromCallable(() -> {
            synchronized (tree) {
                Node receiver = resolve(target);
                if (receiver.parent == null || receiver == node) {
                    throw new IllegalArgumentException("不能中断根 Agent 或自己");
                }
                AgentSnapshotDTO before = snapshot(receiver);
                if (receiver.state.equals("RUNNING")) {
                    receiver.state = "CANCELLED";
                    receiver.generation++;
                    receiver.checkpoints.put("agent:interrupted", "The previous turn was interrupted.");
                    receiver.execution.dispose();
                }
                return before;
            }
        });
    }

    private Node resolve(String target) {
        Node receiver = tree.nodes.values().stream().filter(n -> n.id.equals(target)).findFirst().orElse(null);
        if (receiver == null) {
            receiver = tree.nodes.get(AgentLifecycle.resolve(node.path, target));
        }
        if (receiver == null) {
            throw new IllegalArgumentException("子会话不存在");
        }
        return receiver;
    }

    public List<AgentSnapshotDTO> listAgents(String prefix) {
        synchronized (tree) {
            String path = prefix == null ? "/root" : AgentLifecycle.resolve(node.path, prefix);
            return tree.nodes.values().stream()
                    .filter(n -> n.path.equals(path) || n.path.startsWith(path + "/"))
                    .sorted(Comparator.comparing(n -> n.path)).map(this::snapshot).toList();
        }
    }

    private AgentSnapshotDTO snapshot(Node target) {
        return new AgentSnapshotDTO(target.id, target.path, target.role, AgentLifecycle.status(target.state),
                target.result == null ? null : target.result.answer(), target.result);
    }

    public List<AgentMessageDTO> drainMailbox() {
        synchronized (tree) {
            List<AgentMessageDTO> messages = List.copyOf(node.mailbox);
            node.mailbox.clear();
            return messages;
        }
    }

    public Mono<AgentWaitDTO> waitAgent(long timeoutMs) {
        return Mono.defer(() -> waitUntil(System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos()));
    }

    private Mono<AgentWaitDTO> waitUntil(long deadline) {
        return Mono.defer(() -> {
            synchronized (tree) {
                checkActive();
                if (!node.mailbox.isEmpty()) {
                    return Mono.just(new AgentWaitDTO("Wait completed.", false));
                }
                if (System.nanoTime() >= deadline) {
                    return Mono.just(new AgentWaitDTO("Wait timed out.", true));
                }
            }
            return Mono.delay(Duration.ofMillis(10)).then(waitUntil(deadline));
        });
    }
}
