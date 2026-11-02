package dev.garden.demo.supervisor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.AgentLifecycle;
import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.pojo.dto.AgentOperationReceiptDTO;
import dev.garden.demo.supervisor.pojo.dto.AgentUsageDTO;
import dev.garden.demo.supervisor.pojo.dto.ApprovalDTO;
import dev.garden.demo.supervisor.pojo.dto.ChildRunDTO;
import dev.garden.demo.supervisor.pojo.dto.CompactionDTO;
import dev.garden.demo.supervisor.pojo.dto.CorrectionEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.DelegationDTO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.InstructionEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.PermissionsDTO;
import dev.garden.demo.supervisor.pojo.dto.PromptVersionDTO;
import dev.garden.demo.supervisor.pojo.dto.RecallDTO;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;
import dev.garden.demo.supervisor.pojo.dto.RoutingCandidateDTO;
import dev.garden.demo.supervisor.pojo.dto.RoutingEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.RunEventDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.SpanDTO;
import dev.garden.demo.supervisor.pojo.dto.TaskBriefDTO;
import dev.garden.demo.supervisor.pojo.dto.ToolCallDTO;
import dev.garden.demo.supervisor.pojo.dto.TrustEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.UsageDTO;
import dev.garden.demo.supervisor.pojo.res.RunDetailRes;
import dev.garden.demo.supervisor.pojo.res.RunRes;
import dev.garden.demo.supervisor.repo.TaskRepository;
import dev.garden.demo.supervisor.support.PromptCatalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 组装运行详情读模型。
 *
 * <p>只读取已经落盘的任务记录、检查点和协作回执，不调用模型、不执行工具、不改变任务
 * 状态。原始模型会话（{@code loop:v3.3}）、压缩原文和认证令牌永不进入响应；工具参数
 * 只返回指纹，压缩只返回摘要。
 *
 * <p>所有任务归属与证据权限先在 {@link TaskStoreService#view} 中复核；子运行如果包含
 * 当前身份无权读取的证据，只返回状态元数据，答案和证据列表保持为空。
 *
 * <p>类与读取方法必须保持非 final，声明式事务的 CGLIB 代理才能生效。
 */
@Service
public class RunDetailService {
    private static final String LOOP_KEY = "loop:v3.3";
    private static final String PLAN_KEY = "plan:v1:current";
    private static final String RECALL_PREFIX = "context-recall:v1:";
    private static final String INHERITED_HISTORY = "agent:inherited-history";
    /** 与 RunContext 的工具调用检查点前缀保持一致；每个调用编号一条。 */
    private static final String TOOL_CALL_KEY = "toolcall:v1:";
    private static final DateTimeFormatter MESSAGE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_TREE = 200;
    private static final int MAX_ITEMS = 500;
    private static final int MAX_CHECKPOINT = 500;
    private static final int MAX_SUMMARY = 2000;
    private static final int MAX_RESULT = 300;
    private static final List<String> DATA_TOOLS =
            List.of("query_weather", "query_trains", "search_knowledge");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskStoreService store;
    private final TaskRepository repository;
    /** 审批与副作用读取服务；未装配时返回空列表，不编造证据。 */
    private final ApprovalService approvals;

    /**
     * 唯一构造器：任务存储提供归属与权限检查，仓储提供同根运行树读取，
     * 审批服务提供写入审批和账本证据。
     */
    public RunDetailService(
            TaskStoreService store, TaskRepository repository, ApprovalService approvals) {
        this.store = store;
        this.repository = repository;
        this.approvals = approvals;
    }

    /**
     * 返回指定运行的详情。
     *
     * <p>先用 {@code visible} 校验任务属于当前身份，再用 {@code view} 复用现有证据权限
     * 检查；随后读取同一根运行下的后代任务。读取操作只使用只读事务。
     */
    @Transactional(readOnly = true)
    public RunDetailRes detail(IdentityDTO identity, String runId) {
        TaskDO task = store.visible(identity, runId);
        RunRes view = store.view(identity, task);
        List<TaskDO> tree = tree(task);
        List<ToolCallDTO> toolCalls = new ArrayList<>();
        List<SpanDTO> spans = new ArrayList<>();
        List<AgentUsageDTO> usages = new ArrayList<>();
        List<CompactionDTO> compactions = new ArrayList<>();
        List<RecallDTO> recalls = new ArrayList<>();
        Set<String> required = new LinkedHashSet<>();
        List<String> evidenceTools = new ArrayList<>();
        int evidenceCount = 0;
        for (TaskDO member : tree) {
            Snapshot snapshot = snapshot(member);
            toolCalls.addAll(snapshot.toolCalls());
            spans.add(runSpan(member, rootId(task)));
            spans.addAll(snapshot.spans());
            usages.add(
                    snapshot.usage(
                            member,
                            subtreeCalls(member, tree),
                            sharedRemaining(member)));
            required.addAll(AgentLifecycle.requiredPermissions(member.checkpoints, member.result));
            if (member.result != null) {
                for (var evidence : member.result.evidence()) {
                    if (identity.allows(evidence.tool())) {
                        evidenceTools.add(evidence.tool());
                        evidenceCount++;
                    }
                }
            }
            compaction(member, snapshot).ifPresent(compactions::add);
            recalls.addAll(recalls(member));
            if (!member.runId.equals(task.runId)) {
                spans.add(delegationSpan(task, member));
            }
        }
        // 子运行详情需要父运行的运行段作为父 span，避免页面出现悬空引用。
        for (TaskDO ancestor : lineage(task)) {
            spans.add(runSpan(ancestor, rootId(task)));
        }
        Tri totals = totals(usages);
        Set<String> allowed = new TreeSet<>(identity.permissions());
        Set<String> filtered = new TreeSet<>(required);
        filtered.removeAll(allowed);
        Set<String> directory = directoryTools(task, allowed);
        Set<String> execution = executionTools(toolCalls);
        Map<String, String> filteredReasons = filteredReasons(filtered, directory, execution, allowed);
        return new RunDetailRes(
                view.result(),
                task.runId,
                task.requestId,
                task.sessionId,
                task.state,
                task.revision,
                task.generation,
                task.consumedRevision,
                task.calls,
                task.maxCalls,
                task.deadline,
                task.replacedBy,
                task.parentRunId,
                rootId(task),
                task.agentPath,
                task.agentRole,
                task.agentVersion == null ? "unregistered" : task.agentVersion,
                task.accepted,
                plan(task),
                children(task, tree, identity),
                limited(toolCalls),
                delegations(tree),
                new UsageDTO(
                        totals.prompt(),
                        totals.completion(),
                        totals.cached(),
                        totals.prompt() + totals.completion(),
                        totals.source(),
                        usages),
                limited(spans),
                compactions,
                recalls,
                new PermissionsDTO(
                        allowed,
                        required,
                        filtered,
                        List.copyOf(new LinkedHashSet<>(evidenceTools)),
                        evidenceCount,
                        filtered.isEmpty(),
                        directory,
                        execution,
                        filteredReasons),
                promptVersions(tree),
                checkpoints(task),
                approvals == null
                        ? List.<ApprovalDTO>of()
                        : approvals.forRun(task.runId, identity),
                approvals == null
                        ? List.<SideEffectEvidenceDTO>of()
                        : approvals.sideEffects(task.runId, identity),
                List.copyOf(task.events),
                task.createdAt,
                task.firstEventAt,
                task.completedAt,
                task.triggerMode == null ? "NORMAL" : task.triggerMode,
                task.acceptanceHint,
                task.recoveredAt > 0,
                task.requestHash,
                List.copyOf(task.instructionEvidence),
                timeline(task),
                routing(task, tree, identity),
                corrections(task),
                contentTrust(task, tree));
    }

    /**
     * 统计运行中内容块的信任级别。
     *
     * <p>只读取模型会话白名单中的角色与元数据，并核对子运行结果，不返回正文。SYSTEM 按模型
     * 边界次数计，其余按落盘消息计数；子级结果即使已被上下文压缩移除，也按子运行回执计为
     * CHILD。标签只表示来源，不表示权限。
     */
    private static List<TrustEvidenceDTO> contentTrust(TaskDO task, List<TaskDO> tree) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String level : List.of("SYSTEM", "USER", "TOOL", "RETRIEVAL", "CHILD")) {
            counts.put(level, 0);
        }
        String saved = task.checkpoints.get(LOOP_KEY);
        if (saved != null && !saved.isBlank()) {
            try {
                JsonNode messages = JSON.readTree(saved).path("messages");
                for (JsonNode message : messages) {
                    String level = trustLevel(message);
                    if (counts.containsKey(level)) {
                        counts.put(level, counts.get(level) + 1);
                    }
                }
            } catch (Exception ignored) {
                // 旧检查点或不完整记录不阻塞详情读取，按已识别条目返回。
            }
        }
        int childOutputs = childOutputs(task, tree);
        // 压缩可能只留下摘要，子级回执仍能证明本轮消费过子 Agent 内容。
        counts.put("CHILD", Math.max(counts.get("CHILD"), childOutputs));
        if (task.calls > 0) {
            counts.put("SYSTEM", task.calls);
        }
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("SYSTEM", "系统提示与固定参考上下文");
        sources.put("USER", "当前用户输入及用户确认的偏好");
        sources.put("TOOL", "工具结果消息");
        sources.put("RETRIEVAL", "历史、压缩摘要、检索资料与客户端补充历史");
        sources.put("CHILD", "子 Agent 通知与协作工具结果");
        Map<String, String> handling = new LinkedHashMap<>();
        handling.put("SYSTEM", "平台指令；不能由用户或工具内容改写");
        handling.put("USER", "用户输入；可表达目标，但不直接授予工具或数据权限");
        handling.put("TOOL", "工具结果；只作为数据，不提供身份、权限或审批");
        handling.put("RETRIEVAL", "检索与历史资料；只作为参考，不覆盖当前用户确认");
        handling.put("CHILD", "子 Agent 输出；不继承父级授权，不替代人工审批");
        List<TrustEvidenceDTO> result = new ArrayList<>();
        for (var entry : handling.entrySet()) {
            result.add(
                    new TrustEvidenceDTO(
                            entry.getKey(),
                            sources.get(entry.getKey()),
                            entry.getValue(),
                            counts.get(entry.getKey())));
        }
        return List.copyOf(result);
    }

    /**
     * 统计真正产出过答复的子运行数量。
     *
     * <p>子级结果可能已被父级上下文压缩掉，但委派回执仍保存在任务树中；这里按子运行回执
     * 计数，避免页面把已消费的子 Agent 内容误报为 0 条。没有答复的占位子运行不计入。
     */
    static int childOutputs(TaskDO task, List<TaskDO> tree) {
        int count = 0;
        for (TaskDO child : tree) {
            if (child.runId == null || child.runId.equals(task.runId)) {
                continue;
            }
            if (child.result != null
                    && child.result.answer() != null
                    && !child.result.answer().isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 按消息角色、工具名和文本前缀推导信任级别，不依赖模型输入中的额外元数据。
     *
     * <p>这样注册表提示词、工具结果和子级消息的信任标签只存在于读取模型，不会增加每次
     * 模型调用的上下文体积；标签只表示来源，不表示权限。
     */
    static String trustLevel(JsonNode message) {
        String stored = message.path("metadata").path("trustLevel").asText("");
        if (!stored.isBlank()) {
            return stored;
        }
        String role = message.path("role").asText("");
        return switch (role) {
            case "SYSTEM" -> "SYSTEM";
            case "TOOL" -> toolTrust(message);
            case "USER" -> userTrust(message);
            default -> "RETRIEVAL";
        };
    }

    /** 子级协作工具和检索工具按输出用途标记；普通业务工具结果标为 TOOL。 */
    private static String toolTrust(JsonNode message) {
        boolean retrieval = false;
        for (JsonNode block : message.path("content")) {
            String name = block.path("name").asText("");
            if (Set.of(
                            "spawn_agent",
                            "followup_task",
                            "send_message",
                            "interrupt_agent",
                            "list_agents",
                            "wait_agent")
                    .contains(name)) {
                return "CHILD";
            }
            if (name.equals("search_knowledge")) {
                retrieval = true;
            }
        }
        return retrieval ? "RETRIEVAL" : "TOOL";
    }

    /** 历史、压缩摘要、注入资料和子 Agent 通知都不是当前用户对权限的重新授权。 */
    private static String userTrust(JsonNode message) {
        if (message.path("metadata").path("contextCompaction").asBoolean(false)
                || message.path("metadata").path("contextNotice").asBoolean(false)
                || message.path("metadata").path("inheritedUserMessage").asBoolean(false)) {
            return "RETRIEVAL";
        }
        String text = textOf(message);
        if (text.stripLeading().startsWith("<agent_message")) {
            return "CHILD";
        }
        if (text.contains("客户端补充历史")
                || text.contains("上一话题已归档")
                || text.contains("服务端历史原文摘要")
                || text.contains("用户明确确认的当前有效偏好")) {
            return "RETRIEVAL";
        }
        return "USER";
    }

    /** 读取消息中的文本块；兼容只有 textContent 的旧检查点。 */
    private static String textOf(JsonNode message) {
        String direct = message.path("textContent").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : message.path("content")) {
            if (block.path("type").asText("").equals("text")) {
                text.append(block.path("text").asText(""));
            }
        }
        return text.toString();
    }

    /**
     * 返回任务实际使用的提示词版本。
     *
     * <p>每个角色首次装配时把版本、哈希、提示词编号和来源写入检查点；读取时以检查点
     * 为准，任务尚未执行模型时回退到进程内静态目录，并明确标记来源。
     */
    private List<PromptVersionDTO> promptVersions(List<TaskDO> tree) {
        Map<String, PromptVersionDTO> result = new LinkedHashMap<>();
        for (TaskDO task : tree) {
            for (Map.Entry<String, String> entry : task.checkpoints.entrySet()) {
                if (!entry.getKey().startsWith("prompt:v1:")) {
                    continue;
                }
                String role = entry.getKey().substring("prompt:v1:".length());
                String[] parts = entry.getValue().split("\\|", -1);
                if (parts.length < 4) {
                    continue;
                }
                result.putIfAbsent(
                        role + "@" + parts[0],
                        new PromptVersionDTO(
                                role,
                                parts[2],
                                parts[0],
                                parts[1],
                                parts[3]));
            }
        }
        List<PromptVersionDTO> versions = new ArrayList<>(result.values());
        versions.sort(
                Comparator.comparing(PromptVersionDTO::role)
                        .thenComparing(PromptVersionDTO::version));
        return versions.isEmpty() ? PromptCatalog.versions() : List.copyOf(versions);
    }

    /** 同一根运行下、当前路径及其后代的已受理或待受理任务。 */
    private List<TaskDO> tree(TaskDO task) {
        String root = rootId(task);
        return repository.all().stream()
                .filter(
                        candidate ->
                                Objects.equals(candidate.tenantId, task.tenantId)
                                        && Objects.equals(candidate.userId, task.userId))
                .filter(candidate -> rootId(candidate).equals(root))
                .filter(
                        candidate ->
                                candidate.agentPath.equals(task.agentPath)
                                        || candidate.agentPath.startsWith(task.agentPath + "/"))
                .sorted(Comparator.comparing(candidate -> candidate.agentPath))
                .limit(MAX_TREE)
                .toList();
    }

    private List<ChildRunDTO> children(TaskDO task, List<TaskDO> tree, IdentityDTO identity) {
        List<ChildRunDTO> result = new ArrayList<>();
        for (TaskDO child : tree) {
            if (child.runId.equals(task.runId)) {
                continue;
            }
            Set<String> required =
                    AgentLifecycle.requiredPermissions(child.checkpoints, child.result);
            boolean readable = required.stream().allMatch(identity::allows);
            List<String> tools = new ArrayList<>();
            if (child.result != null) {
                for (var evidence : child.result.evidence()) {
                    if (identity.allows(evidence.tool())) {
                        tools.add(evidence.tool());
                    }
                }
            }
            TaskDO parent =
                    child.parentRunId == null ? null : repository.get(child.parentRunId);
            String spawn = spawnOperationId(child, parent);
            result.add(
                    new ChildRunDTO(
                            child.runId,
                            child.parentRunId,
                            child.agentPath,
                            child.agentRole,
                            "unregistered",
                            child.state,
                            AgentLifecycle.status(child.state),
                            child.generation,
                            child.revision,
                            child.calls,
                            child.maxCalls,
                            child.deadline,
                            child.createdAt,
                            child.accepted,
                            readable && child.result != null ? child.result.answer() : null,
                            child.result == null ? null : child.result.status().name(),
                            child.result == null ? null : sha256(repository.encode(child.result)),
                            List.copyOf(tools),
                            required,
                            taskBrief(child, parent, spawn),
                            spawn,
                            child.acceptanceReceiptKey,
                            child.completedAt,
                            child.recoveredAt > 0));
        }
        return result;
    }

    /**
     * 从已持久化的子任务请求和父计划生成任务书证据。
     *
     * <p>目标取子任务实际收到的请求，计划字段取父任务写入的 {@code plan:v1:current}；
     * 继承历史只统计消息数和字节数，不返回正文。缺少计划字段时保持空值，不推断。
     */
    private TaskBriefDTO taskBrief(TaskDO child, TaskDO parent, String spawnOperationId) {
        List<Map<String, Object>> steps =
                parent == null ? List.of() : planTasks(plan(parent));
        String name = agentName(child.agentPath);
        Map<String, Object> matched = null;
        for (Map<String, Object> step : steps) {
            if (name.equals(String.valueOf(step.get("task_name")))) {
                matched = step;
                break;
            }
        }
        List<String> dependsOn = new ArrayList<>();
        int batch = 0;
        String role = child.agentRole;
        String objective = child.request == null ? "" : child.request.question();
        if (matched != null) {
            Object saved = matched.get("depends_on");
            if (saved instanceof List<?> values) {
                for (Object value : values) {
                    dependsOn.add(String.valueOf(value));
                }
            }
            Object savedBatch = matched.get("batch");
            if (savedBatch instanceof Number number) {
                batch = number.intValue();
            }
            String planRole = String.valueOf(matched.get("agent_type"));
            if (!planRole.isBlank() && !"null".equals(planRole)) {
                role = planRole;
            }
            String planObjective = String.valueOf(matched.get("objective"));
            if (!planObjective.isBlank() && !"null".equals(planObjective)) {
                objective = planObjective;
            }
        }
        String inherited = child.checkpoints.get(INHERITED_HISTORY);
        int inheritedMessages = 0;
        int inheritedBytes = 0;
        if (inherited != null && !inherited.isBlank()) {
            inheritedBytes = inherited.getBytes(StandardCharsets.UTF_8).length;
            try {
                JsonNode node = repository.tree(inherited);
                if (node.isArray()) {
                    inheritedMessages = node.size();
                }
            } catch (RuntimeException ignored) {
                inheritedMessages = 0;
            }
        }
        return new TaskBriefDTO(
                objective,
                role,
                matched == null ? "" : String.valueOf(matched.get("task_name")),
                List.copyOf(dependsOn),
                batch,
                "answer+evidence",
                child.maxCalls,
                child.deadline,
                inheritedMessages,
                inheritedBytes,
                inherited == null ? "none" : "all",
                spawnOperationId);
    }

    private String spawnOperationId(TaskDO child, TaskDO parent) {
        if (child.acceptanceReceiptKey != null && !child.acceptanceReceiptKey.isBlank()) {
            int split = child.acceptanceReceiptKey.indexOf(':');
            return split < 0 ? child.acceptanceReceiptKey : child.acceptanceReceiptKey.substring(split + 1);
        }
        if (parent == null) {
            return "";
        }
        for (Map.Entry<String, String> entry : parent.collaborationReceipts.entrySet()) {
            try {
                AgentOperationReceiptDTO receipt =
                        repository.decode(entry.getValue(), AgentOperationReceiptDTO.class);
                if (receipt != null
                        && receipt.result() != null
                        && child.runId.equals(receipt.result().agentId())) {
                    int split = entry.getKey().indexOf(':');
                    return split < 0 ? entry.getKey() : entry.getKey().substring(split + 1);
                }
            } catch (RuntimeException ignored) {
                // 损坏的单条回执不影响其他委派证据的读取。
            }
        }
        return "";
    }

    private static String agentName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int split = path.lastIndexOf('/');
        return split < 0 ? path : path.substring(split + 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> planTasks(Map<String, Object> plan) {
        Object saved = plan.get("tasks");
        if (!(saved instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> item = new LinkedHashMap<>();
                map.forEach((key, content) -> item.put(String.valueOf(key), content));
                result.add(item);
            }
        }
        return result;
    }

    /** 从当前任务向上读取父运行；只用于补齐父 span，不读取父运行正文。 */
    private List<TaskDO> lineage(TaskDO task) {
        List<TaskDO> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String parent = task.parentRunId;
        while (parent != null && !parent.isBlank() && seen.add(parent)) {
            TaskDO ancestor = repository.get(parent);
            if (ancestor == null) {
                break;
            }
            result.add(ancestor);
            parent = ancestor.parentRunId;
        }
        return result;
    }

    /** 运行段：每个任务一条根 span，子运行挂到父运行段，页面不出现悬空父引用。 */
    private static SpanDTO runSpan(TaskDO task, String trace) {
        Long start = task.createdAt > 0 ? task.createdAt : null;
        Long end = task.completedAt > 0 ? task.completedAt : null;
        Long duration = start != null && end != null && end >= start ? end - start : null;
        return new SpanDTO(
                trace,
                "run:" + task.runId,
                task.parentRunId == null ? null : "run:" + task.parentRunId,
                "RUN",
                task.runId,
                task.agentPath,
                "run",
                task.state,
                start,
                end,
                duration,
                null);
    }

    /** 委派段：父运行观察到的一次子运行创建，起止时间来自子任务记录。 */
    private static SpanDTO delegationSpan(TaskDO parent, TaskDO child) {
        Long start = child.createdAt > 0 ? child.createdAt : null;
        Long end = child.completedAt > 0 ? child.completedAt : null;
        Long duration = start != null && end != null && end >= start ? end - start : null;
        return new SpanDTO(
                rootId(parent),
                "spawn:" + child.runId,
                "run:" + parent.runId,
                "DELEGATION",
                parent.runId,
                parent.agentPath,
                "spawn_agent",
                child.state,
                start,
                end,
                duration,
                null);
    }

    /** 本任务及后代自身模型调用数合计。 */
    private static int subtreeCalls(TaskDO task, List<TaskDO> tree) {
        int total = 0;
        for (TaskDO member : tree) {
            if (member.runId.equals(task.runId)
                    || member.agentPath.startsWith(task.agentPath + "/")) {
                total += member.ownCalls;
            }
        }
        return total;
    }

    /** 所有任务共享根任务的模型调用上限；返回值只反映服务端已记录的剩余量。 */
    private int sharedRemaining(TaskDO task) {
        TaskDO root = rootId(task).equals(task.runId) ? task : repository.get(rootId(task));
        if (root == null) {
            return 0;
        }
        return Math.max(0, root.maxCalls - root.calls);
    }

    private static Set<String> directoryTools(TaskDO task, Set<String> allowed) {
        Set<String> result = new TreeSet<>();
        for (String tool : roleTools(task.agentRole)) {
            if (allowed.contains(tool)) {
                result.add(tool);
            }
        }
        return result;
    }

    private static Set<String> executionTools(List<ToolCallDTO> calls) {
        Set<String> result = new TreeSet<>();
        for (ToolCallDTO call : calls) {
            if (call.tool() != null && !call.tool().isBlank()) {
                result.add(call.tool());
            }
        }
        return result;
    }

    private static Map<String, String> filteredReasons(
            Set<String> filtered,
            Set<String> directory,
            Set<String> execution,
            Set<String> allowed) {
        Map<String, String> result = new TreeMap<>();
        for (String tool : filtered) {
            result.put(tool, "当前身份缺少该工具权限");
        }
        for (String tool : execution) {
            if (!allowed.contains(tool)) {
                result.put(tool, "执行记录中的工具当前身份不可见");
            }
        }
        return result;
    }

    private static List<String> roleTools(String role) {
        return switch (role == null ? "" : role) {
            case "weather_agent" -> List.of("query_weather");
            case "train_agent" -> List.of("query_trains");
            case "itinerary_agent" -> List.of("query_weather", "query_trains", "search_knowledge");
            default -> DATA_TOOLS;
        };
    }

    private RoutingEvidenceDTO routing(TaskDO task, List<TaskDO> tree, IdentityDTO identity) {
        Map<String, Object> plan = plan(task);
        if (plan.isEmpty() && task.parentRunId != null) {
            TaskDO parent = repository.get(task.parentRunId);
            if (parent != null) {
                plan = plan(parent);
            }
        }
        List<Map<String, Object>> steps = planTasks(plan);
        String decision = stringValue(plan.get("decision"), "SINGLE_AGENT");
        String reason = stringValue(plan.get("reason"), "未写入多Agent计划");
        String confidence =
                switch (decision) {
                    case "MULTI_AGENT" -> "HIGH";
                    case "SINGLE_AGENT" -> "MEDIUM";
                    case "REJECTED" -> "LOW";
                    default -> "RECORDED";
                };
        Set<String> roles = new LinkedHashSet<>();
        roles.add("supervisor");
        for (Map<String, Object> step : steps) {
            String role = stringValue(step.get("agent_type"), "");
            if (!role.isBlank()) {
                roles.add(role);
            }
        }
        for (TaskDO member : tree) {
            if (!member.runId.equals(task.runId)) {
                roles.add(member.agentRole);
            }
        }
        if (roles.size() == 1 && task.parentRunId != null) {
            roles.add(task.agentRole);
        }
        Set<String> selectedRoles = new LinkedHashSet<>();
        selectedRoles.add(task.agentRole);
        for (TaskDO member : tree) {
            if (!member.runId.equals(task.runId)) {
                selectedRoles.add(member.agentRole);
            }
        }
        List<RoutingCandidateDTO> candidates = new ArrayList<>();
        for (String role : roles) {
            List<String> catalog = roleTools(role);
            boolean permitted =
                    role.equals("supervisor")
                            ? catalog.stream().anyMatch(identity::allows)
                            : catalog.stream().anyMatch(identity::allows);
            boolean selected = selectedRoles.contains(role);
            boolean planned = steps.stream().anyMatch(step -> role.equals(step.get("agent_type")));
            String candidateReason;
            if (!permitted) {
                candidateReason = "当前身份缺少目录工具权限";
            } else if (selected && planned) {
                candidateReason = "计划任务已派发";
            } else if (selected) {
                candidateReason = "本运行实际使用该角色";
            } else if (planned) {
                candidateReason = "计划包含但未创建子运行";
            } else {
                candidateReason = "未进入本次计划";
            }
            candidates.add(
                    new RoutingCandidateDTO(
                            role,
                            catalog,
                            permitted,
                            selected,
                            candidateReason,
                            selected ? "HIGH" : permitted ? "MEDIUM" : "LOW"));
        }
        return new RoutingEvidenceDTO(decision, reason, confidence, List.copyOf(candidates));
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equals(text) ? fallback : text;
    }

    private static List<RunEventDTO> timeline(TaskDO task) {
        List<RunEventDTO> result = new ArrayList<>();
        for (int index = 0; index < task.events.size(); index++) {
            long at = index < task.eventTimes.size() ? task.eventTimes.get(index) : 0;
            result.add(new RunEventDTO(index + 1, at, task.events.get(index)));
        }
        return List.copyOf(result);
    }

    private static List<CorrectionEvidenceDTO> corrections(TaskDO task) {
        Map<String, CorrectionEvidenceDTO> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : task.checkpoints.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) {
                continue;
            }
            if (key.equals("correction:v1:disabled")) {
                for (String tool : entry.getValue().split(",")) {
                    String name = tool.strip();
                    if (!name.isEmpty()) {
                        result.put(
                                "DISABLED:" + name,
                                new CorrectionEvidenceDTO("DISABLED", name, 1));
                    }
                }
                continue;
            }
            if (!key.startsWith("correction:v1:type:")) {
                continue;
            }
            String rest = key.substring("correction:v1:type:".length());
            int split = rest.indexOf(':');
            String type = split < 0 ? rest : rest.substring(0, split);
            String tool = split < 0 ? "" : rest.substring(split + 1);
            int count;
            try {
                count = Integer.parseInt(entry.getValue());
            } catch (NumberFormatException invalid) {
                continue;
            }
            result.put(
                    type + ":" + tool, new CorrectionEvidenceDTO(type, tool, count));
        }
        return List.copyOf(result.values());
    }

    private Map<String, Object> plan(TaskDO task) {
        String saved = task.checkpoints.get(PLAN_KEY);
        if (saved == null || saved.isBlank()) {
            return Map.of();
        }
        JsonNode node = repository.tree(saved);
        return repository
                .codec()
                .mapper()
                .convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, String> checkpoints(TaskDO task) {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, String> entry : task.checkpoints.entrySet()) {
            String key = entry.getKey();
            if (key == null
                    || entry.getValue() == null
                    || key.equals(LOOP_KEY)
                    || key.startsWith(RECALL_PREFIX)
                    || key.startsWith(INHERITED_HISTORY)) {
                continue;
            }
            result.put(key, truncate(entry.getValue(), MAX_CHECKPOINT));
        }
        return result;
    }

    private List<DelegationDTO> delegations(List<TaskDO> tree) {
        List<DelegationDTO> result = new ArrayList<>();
        for (TaskDO caller : tree) {
            for (Map.Entry<String, String> entry : caller.collaborationReceipts.entrySet()) {
                String key = entry.getKey();
                int split = key.indexOf(':');
                String operationId = split < 0 ? key : key.substring(split + 1);
                JsonNode receipt = repository.tree(entry.getValue());
                JsonNode child = receipt.path("result");
                Set<String> permissions = new LinkedHashSet<>();
                JsonNode saved = child.path("requiredPermissions");
                if (saved.isArray()) {
                    for (JsonNode item : saved) {
                        permissions.add(item.asText());
                    }
                }
                String childId = textOrNull(child, "agentId");
                TaskDO current = childId == null ? null : repository.get(childId);
                result.add(
                        new DelegationDTO(
                                caller.runId,
                                caller.agentPath,
                                operationId,
                                key,
                                receipt.path("fingerprint").asText(""),
                                childId,
                                current == null
                                        ? textOrNull(child, "agentName")
                                        : current.agentPath,
                                current == null
                                        ? textOrNull(child, "agentRole")
                                        : current.agentRole,
                                current == null
                                        ? textOrNull(child, "agentStatus")
                                        : AgentLifecycle.status(current.state) + ":" + current.state,
                                permissions));
            }
        }
        return result;
    }

    private List<RecallDTO> recalls(TaskDO task) {
        List<RecallDTO> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : task.checkpoints.entrySet()) {
            if (!entry.getKey().startsWith(RECALL_PREFIX) || entry.getValue() == null) {
                continue;
            }
            String suffix = entry.getKey().substring(RECALL_PREFIX.length());
            int generation;
            try {
                generation = Integer.parseInt(suffix);
            } catch (NumberFormatException error) {
                continue;
            }
            result.add(
                    new RecallDTO(
                            task.runId,
                            task.agentPath,
                            generation,
                            entry.getValue().codePointCount(0, entry.getValue().length()),
                            true));
        }
        result.sort(Comparator.comparingInt(RecallDTO::generation));
        return result;
    }

    private java.util.Optional<CompactionDTO> compaction(TaskDO task, Snapshot snapshot) {
        List<String> related = new ArrayList<>();
        for (String event : task.events) {
            if (event.contains("压缩") || event.contains("摘要")) {
                related.add(event);
            }
        }
        if (snapshot.compactionGeneration() <= 0
                && (snapshot.compactionSummary() == null || snapshot.compactionSummary().isBlank())
                && related.isEmpty()) {
            return java.util.Optional.empty();
        }
        String summary = truncate(snapshot.compactionSummary(), MAX_SUMMARY);
        return java.util.Optional.of(
                new CompactionDTO(
                        task.runId,
                        task.agentPath,
                        snapshot.compactionGeneration(),
                        summary.getBytes(StandardCharsets.UTF_8).length,
                        summary,
                        List.copyOf(related)));
    }

    /**
     * 读取工具执行阶段写入的调用记录。
     *
     * <p>这些记录按调用编号单独持久化：窗口被压缩、任务恢复或进程重启后，
     * 仍能看到请求是否已发出、结果是否配齐；只有结果真正返回后才标记 paired。
     */
    private List<ToolCallDTO> recordedCalls(TaskDO task) {
        List<ToolCallDTO> calls = new ArrayList<>();
        for (Map.Entry<String, String> entry : task.checkpoints.entrySet()) {
            if (!entry.getKey().startsWith(TOOL_CALL_KEY)) {
                continue;
            }
            JsonNode record;
            try {
                record = JSON.readTree(entry.getValue());
            } catch (Exception error) {
                continue;
            }
            String callId =
                    record.path("callId")
                            .asText(entry.getKey().substring(TOOL_CALL_KEY.length()));
            String tool = record.path("tool").asText("");
            if (callId.isBlank() || tool.isBlank()) {
                continue;
            }
            Long startedAt =
                    record.path("startedAt").isNumber() ? record.path("startedAt").asLong() : null;
            Long endedAt =
                    record.path("endedAt").isNumber() ? record.path("endedAt").asLong() : null;
            boolean aborted = record.path("aborted").asBoolean(false);
            calls.add(
                    new ToolCallDTO(
                            task.runId,
                            task.agentPath,
                            callId,
                            tool,
                            record.path("argumentFingerprint").asText(null),
                            record.path("status").asText("RUNNING"),
                            startedAt,
                            endedAt,
                            startedAt != null && endedAt != null
                                    ? Math.max(0, endedAt - startedAt)
                                    : null,
                            record.path("resultBytes").asInt(0),
                            record.path("resultSummary").asText(""),
                            callId,
                            endedAt != null && !aborted,
                            record.path("reused").asBoolean(false),
                            textOrNull(record, "failureClass"),
                            textOrNull(record, "providerCode"),
                            record.path("retryAfterMs").isNumber()
                                    ? record.path("retryAfterMs").asLong()
                                    : null));
        }
        calls.sort(
                Comparator.comparing(
                        ToolCallDTO::startedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return calls;
    }

    private Snapshot snapshot(TaskDO task) {
        String saved = task.checkpoints.get(LOOP_KEY);
        if (saved == null) {
            return Snapshot.empty();
        }
        JsonNode root = repository.tree(saved);
        JsonNode messages = root.path("messages");
        List<CallBuilder> ordered = new ArrayList<>();
        Map<String, CallBuilder> byId = new LinkedHashMap<>();
        List<ToolCallDTO> calls = new ArrayList<>(recordedCalls(task));
        Set<String> recordedIds = new LinkedHashSet<>();
        for (ToolCallDTO recorded : calls) {
            recordedIds.add(recorded.callId());
        }
        List<SpanDTO> spans = new ArrayList<>();
        long prompt = 0;
        long completion = 0;
        long cached = 0;
        boolean usageFound = false;
        String trace = rootId(task);
        String parentSpan = "run:" + task.runId;
        int index = 0;
        for (JsonNode message : messages) {
            index++;
            if (message.path("metadata").path("inheritedUserMessage").asBoolean(false)) {
                continue;
            }
            JsonNode usage = message.path("usage");
            boolean hasUsage = usage.isObject();
            long start = parseTime(message.path("timestamp").asText(""));
            if (hasUsage) {
                usageFound = true;
                prompt += usage.path("inputTokens").asLong(0);
                completion += usage.path("outputTokens").asLong(0);
                cached += usage.path("cachedTokens").asLong(0);
                long duration = Math.max(0, Math.round(usage.path("time").asDouble(0) * 1000));
                spans.add(
                        new SpanDTO(
                                trace,
                                "model:" + task.runId + ":" + index,
                                parentSpan,
                                "MODEL",
                                task.runId,
                                task.agentPath,
                                "model:" + message.path("role").asText("UNKNOWN"),
                                "COMPLETED",
                                start,
                                start == 0 ? null : start + duration,
                                duration,
                                null));
            }
            for (JsonNode block : message.path("content")) {
                String type = block.path("type").asText("");
                if ("tool_use".equals(type)) {
                    String callId = block.path("id").asText("");
                    if (!callId.isBlank() && !byId.containsKey(callId)) {
                        CallBuilder call =
                                new CallBuilder(
                                        callId,
                                        block.path("name").asText(""),
                                        sha256(block.path("input").toString()),
                                        start);
                        byId.put(callId, call);
                        ordered.add(call);
                    }
                } else if ("tool_result".equals(type)) {
                    String callId = block.path("id").asText("");
                    CallBuilder call = byId.get(callId);
                    if (call == null) {
                        call =
                                new CallBuilder(
                                        callId,
                                        block.path("name").asText(""),
                                        null,
                                        null);
                        byId.put(callId, call);
                        ordered.add(call);
                    }
                    call.finish(start, resultText(block.path("output")));
                }
            }
        }
        for (CallBuilder call : ordered) {
            if (!recordedIds.contains(call.callId)) {
                calls.add(call.dto(task));
            }
            spans.add(call.span(task, trace, parentSpan));
        }
        return new Snapshot(
                List.copyOf(calls),
                List.copyOf(spans),
                prompt,
                completion,
                cached,
                usageFound,
                root.path("compactionGeneration").asInt(0),
                root.path("compactionSummary").asText(""));
    }

    private static <T> List<T> limited(List<T> values) {
        int from = Math.max(0, values.size() - MAX_ITEMS);
        return List.copyOf(values.subList(from, values.size()));
    }

    private static Tri totals(List<AgentUsageDTO> usages) {
        long prompt = 0;
        long completion = 0;
        long cached = 0;
        int provider = 0;
        for (AgentUsageDTO usage : usages) {
            prompt += usage.promptTokens();
            completion += usage.completionTokens();
            cached += usage.cachedTokens();
            if ("provider".equals(usage.source())) {
                provider++;
            }
        }
        String source =
                usages.isEmpty() || provider == 0
                        ? "missing"
                        : (provider == usages.size() ? "provider" : "partial");
        return new Tri(prompt, completion, cached, source);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static Long parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, MESSAGE_TIME).atZone(ZONE).toInstant().toEpochMilli();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static ResultText resultText(JsonNode output) {
        StringBuilder text = new StringBuilder();
        if (output.isArray()) {
            for (JsonNode block : output) {
                if (block.has("text")) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(block.path("text").asText(""));
                }
            }
        }
        String value = text.toString();
        String status = "COMPLETED";
        boolean reused = false;
        String failureClass = null;
        String providerCode = null;
        Long retryAfterMs = null;
        String trimmed = value.strip();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode parsed = JSON.readTree(trimmed);
                if (parsed.hasNonNull("status")) {
                    status = parsed.path("status").asText(status);
                }
                JsonNode data = parsed.path("data");
                reused = data.path("reused").asBoolean(false);
                failureClass = textOrNull(data, "failureClass");
                providerCode = textOrNull(data, "providerCode");
                if (data.path("retryAfterMs").isNumber()) {
                    retryAfterMs = data.path("retryAfterMs").asLong();
                }
            } catch (Exception ignored) {
                /* 非 JSON 文本按已完成处理；状态不在页面推断。 */
            }
        }
        return new ResultText(
                status,
                value.getBytes(StandardCharsets.UTF_8).length,
                truncate(value, MAX_RESULT),
                reused,
                failureClass,
                providerCode,
                retryAfterMs);
    }

    private static String rootId(TaskDO task) {
        return task.rootRunId == null ? task.runId : task.rootRunId;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少 SHA-256", error);
        }
    }

    private static final class CallBuilder {
        private final String callId;
        private final String tool;
        private final String fingerprint;
        private final Long startedAt;
        private Long endedAt;
        private String status = "PENDING";
        private int resultBytes;
        private String resultSummary = "";
        private boolean reused;
        private String failureClass;
        private String providerCode;
        private Long retryAfterMs;

        private CallBuilder(
                String callId, String tool, String fingerprint, Long startedAt) {
            this.callId = callId;
            this.tool = tool;
            this.fingerprint = fingerprint;
            this.startedAt = startedAt;
        }

        private void finish(Long ended, ResultText text) {
            this.endedAt = ended;
            this.status = text.status();
            this.resultBytes = text.bytes();
            this.resultSummary = text.summary();
            this.reused = text.reused();
            this.failureClass = text.failureClass();
            this.providerCode = text.providerCode();
            this.retryAfterMs = text.retryAfterMs();
        }

        private ToolCallDTO dto(TaskDO task) {
            Long duration =
                    startedAt != null && endedAt != null && endedAt >= startedAt
                            ? endedAt - startedAt
                            : null;
            // 任务已结束时仍没有结果的调用不会再有结果，页面显示 ABORTED 而不是永久 PENDING。
            boolean abandoned = "PENDING".equals(status) && terminal(task);
            return new ToolCallDTO(
                    task.runId,
                    task.agentPath,
                    callId,
                    tool,
                    fingerprint,
                    abandoned ? "ABORTED" : status,
                    startedAt,
                    endedAt,
                    duration,
                    resultBytes,
                    abandoned ? "未执行：任务已结束" : resultSummary,
                    callId,
                    endedAt != null,
                    reused,
                    failureClass,
                    providerCode,
                    retryAfterMs);
        }

        private static boolean terminal(TaskDO task) {
            return switch (task.state == null ? "" : task.state) {
                case "SUCCESS", "FAILED", "PARTIAL", "CANCELLED", "BUDGET_EXHAUSTED",
                        "NO_DATA", "REPLACED", "CLOSED" -> true;
                default -> false;
            };
        }

        private SpanDTO span(TaskDO task, String trace, String parent) {
            Long duration =
                    startedAt != null && endedAt != null && endedAt >= startedAt
                            ? endedAt - startedAt
                            : null;
            boolean abandoned = "PENDING".equals(status) && terminal(task);
            return new SpanDTO(
                    trace,
                    "tool:" + task.runId + ":" + callId,
                    parent,
                    "TOOL",
                    task.runId,
                    task.agentPath,
                    tool,
                    abandoned ? "ABORTED" : status,
                    startedAt,
                    endedAt,
                    duration,
                    failureClass == null
                            ? (abandoned ? "ABORTED" : ("PENDING".equals(status) ? null : status))
                            : failureClass);
        }
    }

    private record ResultText(
            String status,
            int bytes,
            String summary,
            boolean reused,
            String failureClass,
            String providerCode,
            Long retryAfterMs) {}

    private record Tri(long prompt, long completion, long cached, String source) {}

    private record Snapshot(
            List<ToolCallDTO> toolCalls,
            List<SpanDTO> spans,
            long promptTokens,
            long completionTokens,
            long cachedTokens,
            boolean usageFound,
            int compactionGeneration,
            String compactionSummary) {
        private static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), 0, 0, 0, false, 0, "");
        }

        private AgentUsageDTO usage(TaskDO task, int subtreeCalls, int remainingCalls) {
            return new AgentUsageDTO(
                    task.runId,
                    task.agentPath,
                    task.agentRole,
                    subtreeCalls,
                    promptTokens,
                    completionTokens,
                    cachedTokens,
                    promptTokens + completionTokens,
                    usageFound ? "provider" : "missing",
                    task.ownCalls,
                    subtreeCalls,
                    remainingCalls,
                    true);
        }
    }
}
