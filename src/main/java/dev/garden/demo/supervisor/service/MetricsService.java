package dev.garden.demo.supervisor.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.garden.demo.supervisor.pojo.dataobject.AlertDO;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.res.AlertRes;
import dev.garden.demo.supervisor.pojo.res.MetricsRes;
import dev.garden.demo.supervisor.repo.GovernanceRepository;
import dev.garden.demo.supervisor.repo.TaskRepository;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从真实任务记录聚合指标并计算告警。
 *
 * <p>任务量、状态、耗时、模型调用、token 和工具调用都来自当前数据库；token 从每个
 * 任务保存的模型会话快照中累加，缺失字段按 0 处理。告警阈值固定为本地演示值，
 * 只根据实际采集值判定，不用人工汇总冒充。
 */
@Service
public final class MetricsService {
    private static final String LOOP_KEY = "loop:v3.3";
    private static final String TOOL_CALL_PREFIX = "toolcall:v1:";
    private static final Set<String> FAILED_STATES =
            Set.of("FAILED", "TIMEOUT", "BUDGET_EXHAUSTED", "CONTEXT_LIMIT");

    private final TaskRepository tasks;
    private final GovernanceRepository governance;
    private final ApprovalService approvals;
    private final Clock clock;

    /** 唯一构造器：只读任务、治理仓储和审批统计。 */
    public MetricsService(
            TaskRepository tasks,
            GovernanceRepository governance,
            ApprovalService approvals,
            Clock clock) {
        this.tasks = tasks;
        this.governance = governance;
        this.approvals = approvals;
        this.clock = clock;
    }

    /** 采集当前指标，并刷新告警状态。 */
    public MetricsRes collect() {
        List<TaskDO> all = tasks.all();
        Map<String, Integer> byState = new LinkedHashMap<>();
        List<Long> durations = new ArrayList<>();
        long modelCalls = 0;
        long prompt = 0;
        long completion = 0;
        long toolCalls = 0;
        long failedTools = 0;
        long rateLimited = 0;
        int running = 0;
        int completed = 0;
        int failed = 0;
        for (TaskDO task : all) {
            String state = task.state == null ? "UNKNOWN" : task.state;
            byState.merge(state, 1, Integer::sum);
            modelCalls += Math.max(0, task.calls);
            if ("RUNNING".equals(state)) {
                running++;
            }
            if (!"RUNNING".equals(state) && !"NEEDS_INPUT".equals(state)) {
                completed++;
                if (task.completedAt > 0) {
                    durations.add(Math.max(0, task.completedAt - task.createdAt));
                }
                if (FAILED_STATES.contains(state)) {
                    failed++;
                }
            }
            TokenUsage usage = usage(task);
            prompt += usage.prompt;
            completion += usage.completion;
            for (Map.Entry<String, String> checkpoint : task.checkpoints.entrySet()) {
                if (!checkpoint.getKey().startsWith(TOOL_CALL_PREFIX)) {
                    continue;
                }
                toolCalls++;
                try {
                    JsonNode node = tasks.tree(checkpoint.getValue());
                    if ("FAILED".equals(node.path("status").asText(""))) {
                        failedTools++;
                    }
                } catch (RuntimeException ignored) {
                    // 单条记录损坏不影响整体指标。
                }
            }
            for (String event : task.events) {
                if (event.contains("RATE_LIMITED") || event.contains("429")) {
                    rateLimited++;
                }
            }
        }
        double failureRate = completed == 0 ? 0 : (double) failed / completed;
        double p95 = percentile95(durations);
        int pending = approvals.pending();
        int decided = approvals.decided();
        List<AlertRes> alerts = refresh(failureRate, p95, pending, rateLimited);
        int open = 0;
        for (AlertRes alert : alerts) {
            if ("OPEN".equals(alert.status())) {
                open++;
            }
        }
        return new MetricsRes(
                clock.millis(),
                all.size(),
                running,
                completed,
                round(failureRate),
                round(p95),
                modelCalls,
                prompt,
                completion,
                toolCalls,
                failedTools,
                rateLimited,
                0,
                pending,
                decided,
                byState,
                open,
                alerts);
    }

    private List<AlertRes> refresh(double failureRate, double p95, int pending, long rateLimited) {
        List<AlertRes> result = new ArrayList<>();
        result.add(rule("task_failure_rate", "HIGH", "任务失败率超过阈值", failureRate, 0.2));
        result.add(rule("p95_duration_ms", "MEDIUM", "任务 P95 耗时超过阈值", p95, 30_000));
        result.add(rule("pending_approvals", "MEDIUM", "存在待审批写入", pending, 0));
        result.add(rule("rate_limited_events", "MEDIUM", "出现限流事件", rateLimited, 0));
        result.sort(Comparator.comparing(AlertRes::alertId));
        return List.copyOf(result);
    }

    private AlertRes rule(
            String kind, String severity, String message, double value, double threshold) {
        boolean open = value > threshold;
        AlertDO row = new AlertDO();
        row.alertId = kind;
        row.kind = kind;
        row.severity = severity;
        row.message = message;
        row.metricValue = value;
        row.thresholdValue = threshold;
        row.status = open ? "OPEN" : "RESOLVED";
        row.createdAt = clock.millis();
        row.updatedAt = row.createdAt;
        AlertDO existing = governance.alert(kind);
        if (existing != null) {
            row.createdAt = existing.createdAt;
        }
        governance.saveAlert(row);
        return view(row);
    }

    private TokenUsage usage(TaskDO task) {
        String saved = task.checkpoints.get(LOOP_KEY);
        if (saved == null) {
            return TokenUsage.EMPTY;
        }
        long prompt = 0;
        long completion = 0;
        try {
            for (JsonNode message : tasks.tree(saved).path("messages")) {
                JsonNode usage = message.path("usage");
                if (usage.isObject()) {
                    prompt += usage.path("inputTokens").asLong(0);
                    completion += usage.path("outputTokens").asLong(0);
                }
            }
        } catch (RuntimeException ignored) {
            return TokenUsage.EMPTY;
        }
        return new TokenUsage(prompt, completion);
    }

    private static double percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private static double round(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private static AlertRes view(AlertDO row) {
        return new AlertRes(
                row.alertId,
                row.kind,
                row.severity,
                row.message,
                row.metricValue,
                row.thresholdValue,
                row.status,
                row.createdAt,
                row.updatedAt);
    }

    private record TokenUsage(long prompt, long completion) {
        static final TokenUsage EMPTY = new TokenUsage(0, 0);
    }
}
