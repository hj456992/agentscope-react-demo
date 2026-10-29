package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.*;

import io.agentscope.core.util.JsonUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** V2 生命周期共用的路径和状态映射，不依赖业务角色。 */
public final class AgentLifecycle {
    /** 本服务独立 Agent 树的子执行容量；不是 Codex 默认值，也不是 HTTP 请求额度。 */
    public static final int MAX_ACTIVE_CHILDREN = 8;

    private AgentLifecycle() {}

    public static String childPath(String parent, String name) {
        if (name == null || !name.matches("[a-z0-9_]+") || name.equals("root")) {
            throw new IllegalArgumentException("task_name 只能包含小写字母、数字、下划线，且不能为 root");
        }
        return parent + "/" + name;
    }

    public static String resolve(String caller, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("目标不能为空");
        }
        String path = reference.startsWith("/") ? reference : caller + "/" + reference;
        if (!path.equals("/root")) {
            if (!path.startsWith("/root/")) {
                throw new IllegalArgumentException("目标不在任务树中");
            }
            for (String segment : path.substring(6).split("/", -1)) {
                childPath("/root", segment);
            }
        }
        return path;
    }

    public static void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
    }

    public static String role(String role) {
        return role == null || role.isBlank() || role.equals("default") ? "supervisor" : role;
    }

    public static String status(String state) {
        return switch (state) {
            case "RUNNING" -> "running";
            case "PROVISIONAL" -> "pending_init";
            case "CANCELLED", "REPLACED", "BUDGET_EXHAUSTED" -> "interrupted";
            case "FAILED", "TIMEOUT", "CONTEXT_LIMIT" -> "errored";
            case "CLOSED" -> "shutdown";
            default -> "completed";
        };
    }

    public static AgentMessageDTO result(
            String sender, String recipient, ResultDTO result, Map<String, String> checkpoints) {
        String text =
                status(result.status().name()).equals("errored")
                        ? "Agent errored: " + result.answer()
                        : result.answer();
        return new AgentMessageDTO(
                sender,
                recipient,
                "RESULT",
                text,
                result,
                requiredPermissions(checkpoints, result));
    }

    /** 权限来自完整历史快照，不能因为新回合无新证据就丢掉旧内容的访问约束。 */
    public static Set<String> requiredPermissions(
            Map<String, String> checkpoints, ResultDTO result) {
        Set<String> permissions = new LinkedHashSet<>();
        String inherited = checkpoints.get("agent:inherited-permissions");
        if (inherited != null) {
            permissions.addAll(
                    java.util.Arrays.asList(
                            JsonUtils.getJsonCodec().fromJson(inherited, String[].class)));
        }
        String loop = checkpoints.get("loop:v3.3");
        if (loop != null) {
            Map<?, ?> snapshot = JsonUtils.getJsonCodec().fromJson(loop, Map.class);
            if (snapshot.get("requiredPermissions") instanceof Collection<?> saved) {
                for (Object permission : saved) {
                    if (permission instanceof String tool) {
                        permissions.add(tool);
                    }
                }
            }
        }
        if (result != null) {
            result.evidence().forEach(e -> permissions.add(e.tool()));
        }
        return Set.copyOf(permissions);
    }
}
