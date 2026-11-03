package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.res.MemoryRes;
import dev.garden.demo.supervisor.support.SemanticLoopGuard;

import java.time.*;
import java.util.*;

/** 单 Agent 回合的可信运行边界。父子共享树用量，但拥有独立代际和取消状态。 业务日期时钟不能改变实际截止时间；协作消息始终携带来源，不能提升为用户授权。 */
public interface RunControl {
    /** 角色只选择提示词和工具能力，不改变模型循环。 */
    default String agentRole() {
        return "supervisor";
    }

    /**
     * 返回角色当前固定的提示词定义。
     *
     * <p>持久任务由提示词注册表实现；CLI 和旧测试实现退回进程内静态目录。
     */
    default PromptDefinitionDTO prompt(String role) {
        return dev.garden.demo.supervisor.support.PromptCatalog.definition(role);
    }

    /**
     * 写入工具入口：登记审批或在批准后执行一次外部写入。
     *
     * <p>旧控制实现没有审批和账本，默认拒绝，避免在无治理组件的运行路径上静默写外部系统。
     */
    default BookingOutcomeDTO book(String operationId, Map<String, Object> parameters) {
        throw new UnsupportedOperationException("审批与外部写入不可用");
    }

    /**
     * 记录模型在对话中提议的低风险长期偏好。
     *
     * <p>旧控制实现默认拒绝，避免没有持久化记忆的运行路径静默写入；持久任务由服务层
     * 校验身份、任务代际和运行状态后写入。
     */
    default MemoryRes rememberPreference(String key, String value) {
        throw new UnsupportedOperationException("长期偏好写入不可用");
    }

    /** 每次运行共享同一份策略；旧控制实现默认关闭新增能力。 */
    default SupervisorPolicy policy() {
        return SupervisorPolicy.defaults();
    }

    default String agentPath() {
        return "/root";
    }

    default String turnId() {
        return runId() + ":" + revision();
    }

    /** CLI 内存运行树绑定同一个主子执行函数；持久执行器由服务构造器装配。 */
    default void bindRunner(
            java.util.function.BiFunction<
                            RequestDTO, RunControl, reactor.core.publisher.Mono<ResultDTO>>
                    runner) {}

    /** 返回已接受的子会话；后续父轮次取消不会撤销它。 */
    default reactor.core.publisher.Mono<AgentSnapshotDTO> spawnAgent(
            String taskName,
            String message,
            String agentRole,
            String forkTurns,
            Map<String, String> inheritedCheckpoints) {
        return reactor.core.publisher.Mono.error(
                new UnsupportedOperationException("Agent lifecycle unavailable"));
    }

    /** triggerTurn=false 只入邮箱；true 在目标闲置时开启新回合。 */
    default reactor.core.publisher.Mono<Void> sendMessage(
            String target, String message, boolean triggerTurn) {
        return reactor.core.publisher.Mono.error(
                new UnsupportedOperationException("Agent lifecycle unavailable"));
    }

    default reactor.core.publisher.Mono<AgentSnapshotDTO> interruptAgent(String target) {
        return reactor.core.publisher.Mono.error(
                new UnsupportedOperationException("Agent lifecycle unavailable"));
    }

    default List<AgentSnapshotDTO> listAgents(String pathPrefix) {
        return List.of();
    }

    default reactor.core.publisher.Mono<AgentWaitDTO> waitAgent(long timeoutMs) {
        return reactor.core.publisher.Mono.just(new AgentWaitDTO("Wait timed out.", true));
    }

    default List<AgentMessageDTO> drainMailbox() {
        return List.of();
    }

    /**
     * 记录一条会开启目标新回合的协作调用边。
     *
     * <p>{@code caller} 必须是当前会话路径，{@code callee} 可以是绝对路径或相对引用。
     * 返回 {@code true} 表示新增这条边会形成环或超过调用图容量，调用方必须拒绝本次协作。
     * 旧控制实现默认不记录，保持既有调用行为。
     */
    default boolean recordAgentEdge(String caller, String callee) {
        return false;
    }

    /**
     * 记录一次近期目标并返回语义循环检测结论。
     *
     * <p>记录写入根任务的共享检查点，因此不同子会话比较的是同一段目标历史。
     * 旧控制实现默认返回继续执行，不改变既有调用行为。
     *
     * @param scope 目标类型，例如 {@code agent} 或 {@code tool}
     * @param key 目标标识，例如子会话路径或工具名
     * @param text 模型生成的目标文本或规范化工具参数
     * @param window 保留的近期目标数量
     * @param threshold 相似度阈值
     * @param maxWarnings 允许返回提醒的次数
     * @param crossKey 是否跨同类目标比较
     */
    default SemanticLoopGuard.Result recordSemanticGoal(
            String scope,
            String key,
            String text,
            int window,
            double threshold,
            int maxWarnings,
            boolean crossKey) {
        return SemanticLoopGuard.Result.proceed(null);
    }

    /** 保留每一条真实用户输入边界；兼容旧控制实现时只包装其单条输出。 */
    default List<String> drainInputs() {
        String input = drainInput();
        return input == null ? List.of() : List.of(input);
    }

    /** 工具调用编号参与受理回执去重；旧控制实现保留无回执的兼容入口。 */
    default reactor.core.publisher.Mono<AgentSnapshotDTO> spawnAgent(
            String operationId,
            String taskName,
            String message,
            String role,
            String forkTurns,
            Map<String, String> inherited) {
        return spawnAgent(taskName, message, role, forkTurns, inherited);
    }

    default reactor.core.publisher.Mono<Void> sendMessage(
            String operationId, String target, String message, boolean triggerTurn) {
        return sendMessage(target, message, triggerTurn);
    }

    default reactor.core.publisher.Mono<AgentSnapshotDTO> interruptAgent(
            String operationId, String target) {
        return interruptAgent(target);
    }

    String runId();

    int revision();

    IdentityDTO identity();

    void checkActive();

    Duration remaining();

    void reserveModel(int estimatedTokens);

    default void reserveModelStep(String stepKey, int estimatedTokens, int maxAttempts) {
        synchronized (this) {
            String saved = checkpoint(stepKey);
            int attempts = saved == null ? 0 : Integer.parseInt(saved);
            if (attempts >= maxAttempts) {
                throw new BudgetExceeded("模型步骤尝试预算已耗尽");
            }
            reserveModel(estimatedTokens);
            checkpoint(stepKey, Integer.toString(attempts + 1));
        }
    }

    String checkpoint(String key);

    void checkpoint(String key, String value);

    String drainInput();

    void event(String event);

    int modelCalls();

    class Cancelled extends RuntimeException {
        public Cancelled(String message) {
            super(message);
        }
    }

    class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String message) {
            super(message);
        }
    }

    class ContextLimit extends RuntimeException {
        public ContextLimit(String message) {
            super(message);
        }
    }

    class DeadlineExceeded extends RuntimeException {
        public DeadlineExceeded() {
            super("任务已超过截止时间");
        }
    }

    static RunControl local(LimitsDTO limits) {
        return LocalAgentControl.root(limits);
    }

    static RunControl local(LimitsDTO limits, SupervisorPolicy policy) {
        return LocalAgentControl.root(limits, policy);
    }
}
