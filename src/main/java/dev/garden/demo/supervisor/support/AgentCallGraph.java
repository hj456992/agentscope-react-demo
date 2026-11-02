package dev.garden.demo.supervisor.support;

import io.agentscope.core.util.JsonUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 任务树内的协作调用图，用于阻止 A→B→A 或更长的回边。
 *
 * <p>图只记录会开启目标新回合的调用边，例如 {@code spawn_agent} 和
 * {@code followup_task}；普通 {@code send_message} 不开启新回合，不进入图。
 * 边写入根任务的共享检查点，因此父、子会话和重启恢复看到的是同一张图。
 */
public final class AgentCallGraph {
    /** 单棵任务树最多记录的边数；超过后拒绝新边，避免检查点无限增长。 */
    public static final int MAX_EDGES = 256;

    /** 协作调用图的检查点键。 */
    public static final String KEY = "loop:v1:agent-edges";

    private AgentCallGraph() {}

    /** 记录一条边；如果会形成环或超过容量，返回 {@code cycle=true} 且不修改原图。 */
    public static Decision record(String saved, String caller, String callee) {
        if (caller == null || caller.isBlank() || callee == null || callee.isBlank()) {
            throw new IllegalArgumentException("协作调用边不能为空");
        }
        Map<String, Set<String>> graph = read(saved);
        if (caller.equals(callee) || reachable(graph, callee, caller)) {
            return new Decision(true, saved == null ? "" : saved);
        }
        int edges = graph.values().stream().mapToInt(Set::size).sum();
        if (edges >= MAX_EDGES) {
            return new Decision(true, saved == null ? "" : saved);
        }
        graph.computeIfAbsent(caller, ignored -> new LinkedHashSet<>()).add(callee);
        return new Decision(false, Contracts.json(graph));
    }

    /** 从 {@code start} 出发能否到达 {@code target}；使用深度优先遍历，边数很小。 */
    static boolean reachable(Map<String, Set<String>> graph, String start, String target) {
        Set<String> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        pending.push(start);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(target)) {
                return true;
            }
            for (String next : graph.getOrDefault(current, Set.of())) {
                pending.push(next);
            }
        }
        return false;
    }

    /** 读取检查点中的图；空值表示还没有边，损坏内容直接拒绝。 */
    static Map<String, Set<String>> read(String saved) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        if (saved == null || saved.isBlank()) {
            return graph;
        }
        Map<?, ?> raw;
        try {
            raw = JsonUtils.getJsonCodec().fromJson(saved, Map.class);
        } catch (RuntimeException error) {
            throw new IllegalStateException("协作调用图损坏", error);
        }
        if (raw == null) {
            return graph;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String caller = Objects.toString(entry.getKey(), "");
            if (caller.isBlank() || !(entry.getValue() instanceof Collection<?> values)) {
                throw new IllegalStateException("协作调用图损坏");
            }
            Set<String> targets = new LinkedHashSet<>();
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    targets.add(value.toString());
                }
            }
            graph.put(caller, targets);
        }
        return graph;
    }

    /** 记录结果；{@code cycle=true} 时 {@code value} 是未修改的原图。 */
    public record Decision(boolean cycle, String value) {}
}
