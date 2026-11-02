package dev.garden.demo.supervisor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.garden.demo.supervisor.pojo.dataobject.AgentDO;
import dev.garden.demo.supervisor.pojo.dataobject.AgentReleaseDO;
import dev.garden.demo.supervisor.pojo.dataobject.RegistryAuditDO;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.param.AgentReleaseParam;
import dev.garden.demo.supervisor.pojo.param.AgentRollbackParam;
import dev.garden.demo.supervisor.pojo.res.AgentCatalogRes;
import dev.garden.demo.supervisor.pojo.res.AgentRes;
import dev.garden.demo.supervisor.pojo.res.AuditRes;
import dev.garden.demo.supervisor.pojo.res.ReleaseRes;
import dev.garden.demo.supervisor.repo.GovernanceRepository;
import dev.garden.demo.supervisor.support.AgentCatalogSeed;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 目录、版本、灰度与注册表安全。
 *
 * <p>首次启动把本地目录种子写入数据库并签名；每次启动逐条验签、校验能力白名单
 * 和网络出口白名单，结果写入审计。任务创建时固定角色当前发布版本，在途任务不因
 * 后续发布或回滚而漂移。二十个只读占位项用于真实触发 Schema 注入预算，不参与执行。
 */
@Service
public final class AgentCatalogService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN_TOOLS =
            Set.of("query_weather", "query_trains", "search_knowledge", "book_ticket", "plan_agents");
    private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost");

    private final GovernanceRepository repository;
    private final RegistrySecurityService security;
    private final Clock clock;

    /** 唯一构造器：建表种子和启动验签都发生在这里，失败只记审计不阻断服务启动。 */
    public AgentCatalogService(
            GovernanceRepository repository, RegistrySecurityService security, Clock clock) {
        this.repository = repository;
        this.security = security;
        this.clock = clock;
        seed();
        verifyAll();
    }

    /** 当前角色应使用的目录版本；没有发布记录时退回静态目录版本。 */
    public String versionFor(String role) {
        AgentReleaseDO release = repository.activeRelease(role);
        if (release != null) {
            return release.version;
        }
        return AgentCatalogSeed.versionFor(role);
    }

    /** 返回指定运行固定的版本号；旧任务没有记录时按未登记处理。 */
    public AgentRes agent(String agentId, String version) {
        AgentDO row = repository.agent(agentId, version);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return view(row, security.verify(row.manifest, row.signature));
    }

    public List<AgentRes> agents() {
        List<AgentRes> result = new ArrayList<>();
        for (AgentDO row : repository.agents()) {
            result.add(view(row, security.verify(row.manifest, row.signature)));
        }
        return result;
    }

    /** 按当前身份计算可见候选、权限过滤数量和 Schema 字节预算。 */
    public AgentCatalogRes catalog(IdentityDTO identity) {
        List<AgentRes> entries = new ArrayList<>();
        List<String> events = new ArrayList<>();
        int executable = 0;
        int filtered = 0;
        int schemaBytes = 0;
        int injectedBytes = 0;
        int stopped = 0;
        for (AgentDO row : repository.agents()) {
            Manifest manifest = manifest(row);
            boolean verified = security.verify(row.manifest, row.signature);
            boolean published = "PUBLISHED".equals(row.status) && verified;
            if (published) {
                executable++;
            }
            boolean visible = true;
            if (published) {
                visible =
                        manifest.tools.isEmpty()
                                || manifest.tools.stream().anyMatch(identity::allows);
                if (!visible) {
                    filtered++;
                }
            }
            if (!visible) {
                continue;
            }
            entries.add(view(row, verified, manifest));
            schemaBytes += manifest.schemaBytes;
            if (injectedBytes + manifest.schemaBytes <= AgentCatalogSeed.SCHEMA_BUDGET_BYTES) {
                injectedBytes += manifest.schemaBytes;
            } else {
                stopped++;
            }
        }
        boolean overBudget = schemaBytes > AgentCatalogSeed.SCHEMA_BUDGET_BYTES;
        events.add(
                "目录候选 "
                        + entries.size()
                        + " 项；可执行 "
                        + executable
                        + " 项；权限过滤 "
                        + filtered
                        + " 项");
        events.add(
                "Schema 字节 "
                        + schemaBytes
                        + "；注入预算 "
                        + AgentCatalogSeed.SCHEMA_BUDGET_BYTES
                        + "；实际注入 "
                        + injectedBytes);
        if (overBudget) {
            events.add(
                    "SCHEMA_BUDGET_EXCEEDED：超预算停止注入 "
                            + stopped
                            + " 项，目录仍保留只读元数据");
        }
        return new AgentCatalogRes(
                entries.size() + filtered,
                executable,
                entries.size(),
                filtered,
                schemaBytes,
                AgentCatalogSeed.SCHEMA_BUDGET_BYTES,
                overBudget,
                entries,
                events);
    }

    public List<ReleaseRes> releases(String agentId) {
        List<ReleaseRes> result = new ArrayList<>();
        for (AgentReleaseDO row : repository.agentReleases(agentId)) {
            result.add(new ReleaseRes(
                    row.agentId,
                    row.version,
                    row.trafficPercent,
                    row.action,
                    row.status,
                    row.actor,
                    row.createdAt));
        }
        return result;
    }

    /** 发布或提升灰度比例；旧的活动发布记录标记为 SUPERSEDED。 */
    public ReleaseRes release(AgentReleaseParam param) {
        if (param == null
                || blank(param.agentId())
                || blank(param.version())
                || param.trafficPercent() < 0
                || param.trafficPercent() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        AgentDO agent = repository.agent(param.agentId(), param.version());
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!security.verify(agent.manifest, agent.signature)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        repository.supersedeAgentReleases(param.agentId());
        AgentReleaseDO row =
                releaseRow(param.agentId(), param.version(), param.trafficPercent(), "RELEASE",
                        blank(param.actor()) ? "platform-admin" : param.actor());
        repository.saveAgentRelease(row);
        audit("RELEASE", param.agentId() + "@" + param.version(), "PASS",
                "灰度流量 " + param.trafficPercent() + "%", row.actor);
        return new ReleaseRes(row.agentId, row.version, row.trafficPercent, row.action,
                row.status, row.actor, row.createdAt);
    }

    /** 回滚到指定已验证版本；回滚记录本身也进入审计。 */
    public ReleaseRes rollback(AgentRollbackParam param) {
        if (param == null || blank(param.agentId()) || blank(param.version())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        AgentDO agent = repository.agent(param.agentId(), param.version());
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!security.verify(agent.manifest, agent.signature)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        repository.supersedeAgentReleases(param.agentId());
        String actor = blank(param.actor()) ? "platform-admin" : param.actor();
        AgentReleaseDO row = releaseRow(param.agentId(), param.version(), 100, "ROLLBACK", actor);
        repository.saveAgentRelease(row);
        audit("ROLLBACK", param.agentId() + "@" + param.version(), "PASS", "回滚为 100%", actor);
        return new ReleaseRes(row.agentId, row.version, row.trafficPercent, row.action,
                row.status, row.actor, row.createdAt);
    }

    public List<AuditRes> auditLog(int limit) {
        List<AuditRes> result = new ArrayList<>();
        for (RegistryAuditDO row : repository.audit(Math.max(1, Math.min(limit, 500)))) {
            result.add(new AuditRes(
                    row.auditId,
                    row.action,
                    row.subject,
                    row.outcome,
                    row.detail,
                    row.actor,
                    row.createdAt));
        }
        return result;
    }

    /** 清单验签、能力白名单和网络出口白名单的启动检查。 */
    private void verifyAll() {
        for (AgentDO row : repository.agents()) {
            Manifest manifest = manifest(row);
            boolean signature = security.verify(row.manifest, row.signature);
            List<String> unknownTools =
                    manifest.tools.stream().filter(tool -> !KNOWN_TOOLS.contains(tool)).toList();
            List<String> unknownHosts =
                    manifest.hosts.stream().filter(host -> !ALLOWED_HOSTS.contains(host)).toList();
            String outcome;
            String detail;
            if (!signature) {
                outcome = "FAIL";
                detail = "清单签名校验失败";
            } else if (!unknownTools.isEmpty()) {
                outcome = "FAIL";
                detail = "能力白名单外工具：" + String.join(",", unknownTools);
            } else if (!unknownHosts.isEmpty()) {
                outcome = "FAIL";
                detail = "网络出口白名单外主机：" + String.join(",", unknownHosts);
            } else {
                outcome = "PASS";
                detail = "签名、能力和网络出口均通过";
            }
            audit("VERIFY", row.agentId + "@" + row.version, outcome, detail, "system-startup");
        }
    }

    private void seed() {
        if (!repository.agents().isEmpty()) {
            return;
        }
        for (AgentCatalogSeed.Entry entry : AgentCatalogSeed.entries()) {
            String signature = security.sign(entry.manifest());
            AgentDO row = new AgentDO();
            row.agentId = entry.agentId();
            row.version = entry.version();
            row.role = entry.role();
            row.owner = entry.owner();
            row.oncall = entry.oncall();
            row.sloMs = entry.sloMs();
            row.costBudget = entry.costBudgetUsd();
            row.dataClass = entry.dataClass();
            row.deployUnit = entry.deployUnit();
            row.status = entry.status();
            row.manifest = entry.manifest();
            row.signature = signature;
            row.createdAt = clock.millis();
            if ("travel.emergency".equals(entry.agentId())) {
                // 保留一条真实篡改样本：签名覆盖原清单，写入的清单被替换为越权工具，
                // 启动验签应失败并在审计中留下拒绝记录。
                row.manifest = tampered(entry.manifest());
                row.status = "REJECTED";
            }
            repository.saveAgent(row);
            if ("PUBLISHED".equals(row.status)) {
                repository.saveAgentRelease(releaseRow(
                        row.agentId, row.version, 100, "SEED", "system-seed"));
            }
        }
        audit("SEED", "catalog", "PASS",
                "登记目录项 " + AgentCatalogSeed.entries().size() + " 项；密钥来源 " + security.keySource(),
                "system-seed");
    }

    private static String tampered(String manifest) {
        try {
            ObjectNode node = (ObjectNode) JSON.readTree(manifest);
            node.putArray("allowedTools").add("shell");
            return JSON.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("目录样本损坏", error);
        }
    }

    private AgentReleaseDO releaseRow(
            String agentId, String version, int traffic, String action, String actor) {
        AgentReleaseDO row = new AgentReleaseDO();
        row.agentId = agentId;
        row.version = version;
        row.trafficPercent = traffic;
        row.action = action;
        row.status = "ACTIVE";
        row.actor = actor;
        row.createdAt = clock.millis();
        return row;
    }

    private void audit(String action, String subject, String outcome, String detail, String actor) {
        RegistryAuditDO row = new RegistryAuditDO();
        row.action = action;
        row.subject = subject;
        row.outcome = outcome;
        row.detail = detail;
        row.actor = actor;
        row.createdAt = clock.millis();
        repository.audit(row);
    }

    private static AgentRes view(AgentDO row, boolean verified) {
        return view(row, verified, manifest(row));
    }

    private static AgentRes view(AgentDO row, boolean verified, Manifest manifest) {
        return new AgentRes(
                row.agentId,
                row.version,
                row.role,
                row.owner,
                row.oncall,
                row.sloMs,
                row.costBudget,
                row.dataClass,
                row.deployUnit,
                row.status,
                verified,
                manifest.tools,
                manifest.hosts,
                manifest.schemaBytes,
                row.createdAt);
    }

    private static Manifest manifest(AgentDO row) {
        try {
            JsonNode node = JSON.readTree(row.manifest);
            List<String> tools = new ArrayList<>();
            node.path("allowedTools").forEach(item -> tools.add(item.asText()));
            List<String> hosts = new ArrayList<>();
            node.path("networkAllowlist").forEach(item -> hosts.add(item.asText()));
            return new Manifest(tools, hosts, node.path("schemaBytes").asInt(0));
        } catch (Exception error) {
            return new Manifest(List.of(), List.of(), 0);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Manifest(List<String> tools, List<String> hosts, int schemaBytes) {
        Manifest {
            tools = List.copyOf(tools);
            hosts = List.copyOf(hosts);
        }
    }
}
