package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.*;

import java.time.*;
import java.util.*;

/** 单 Agent 回合的可信运行边界。父子共享树用量，但拥有独立代际和取消状态。 业务日期时钟不能改变实际截止时间；协作消息始终携带来源，不能提升为用户授权。 */
public interface RunControl {
    /** 角色只选择提示词和工具能力，不改变模型循环。 */
    default String agentRole() {
        return "supervisor";
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
}
