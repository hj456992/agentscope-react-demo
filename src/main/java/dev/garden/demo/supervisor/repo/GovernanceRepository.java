package dev.garden.demo.supervisor.repo;

import dev.garden.demo.supervisor.pojo.dataobject.AgentDO;
import dev.garden.demo.supervisor.pojo.dataobject.AgentReleaseDO;
import dev.garden.demo.supervisor.pojo.dataobject.AlertDO;
import dev.garden.demo.supervisor.pojo.dataobject.ApprovalDO;
import dev.garden.demo.supervisor.pojo.dataobject.PromptDO;
import dev.garden.demo.supervisor.pojo.dataobject.PromptReleaseDO;
import dev.garden.demo.supervisor.pojo.dataobject.RegistryAuditDO;
import dev.garden.demo.supervisor.repo.mapper.GovernanceMapper;

import java.util.List;

/**
 * 治理数据仓储：提示词、Agent 目录与发布、注册审计、审批和告警。
 *
 * <p>只使用 MyBatis 语句；建表在构造器内完成，沿用现有仓储模式。仓储不加事务，
 * 审批决策跨表的原子性由 {@link dev.garden.demo.supervisor.service.ApprovalService}
 * 的声明式事务保证。
 */
public final class GovernanceRepository {
    private final GovernanceMapper mapper;

    /** 唯一构造器：建表幂等，重复启动不丢已有记录。 */
    public GovernanceRepository(Database database) {
        this.mapper = database.mapper(GovernanceMapper.class);
        mapper.createPromptsTable();
        mapper.createPromptReleasesTable();
        mapper.createAgentsTable();
        mapper.createAgentReleasesTable();
        mapper.createRegistryAuditTable();
        mapper.createApprovalsTable();
        mapper.createAlertsTable();
    }

    public void savePrompt(PromptDO row) {
        mapper.insertPrompt(row);
    }

    public PromptDO prompt(String promptId, String version) {
        return mapper.selectPrompt(promptId, version);
    }

    public List<PromptDO> prompts() {
        return mapper.selectPrompts();
    }

    public PromptDO activePrompt(String role) {
        return mapper.selectActivePrompt(role);
    }

    public void releasePrompt(PromptReleaseDO row) {
        mapper.upsertPromptRelease(row);
    }

    public List<PromptReleaseDO> promptReleases() {
        return mapper.selectPromptReleases();
    }

    public void saveAgent(AgentDO row) {
        mapper.insertAgent(row);
    }

    public AgentDO agent(String agentId, String version) {
        return mapper.selectAgent(agentId, version);
    }

    public List<AgentDO> agents() {
        return mapper.selectAgents();
    }

    public void saveAgentRelease(AgentReleaseDO row) {
        mapper.insertAgentRelease(row);
    }

    public void supersedeAgentReleases(String agentId) {
        mapper.supersedeAgentReleases(agentId);
    }

    public List<AgentReleaseDO> agentReleases(String agentId) {
        return mapper.selectAgentReleases(agentId);
    }

    public AgentReleaseDO activeRelease(String role) {
        return mapper.selectActiveRelease(role);
    }

    public void audit(RegistryAuditDO row) {
        mapper.insertAudit(row);
    }

    public List<RegistryAuditDO> audit(int limit) {
        return mapper.selectAudit(limit);
    }

    public void saveApproval(ApprovalDO row) {
        mapper.insertApproval(row);
    }

    public ApprovalDO approval(String approvalId) {
        return mapper.selectApproval(approvalId);
    }

    public ApprovalDO approval(String runId, String toolName, String operationId) {
        return mapper.selectApprovalByRunToolOp(runId, toolName, operationId);
    }

    public List<ApprovalDO> approvals(String runId) {
        return mapper.selectApprovalsByRun(runId);
    }

    public int pendingApprovals() {
        return mapper.countApprovals("PENDING");
    }

    public int decidedApprovals() {
        return mapper.countDecidedApprovals();
    }

    public void decide(String approvalId, String status, String actor, long at, String reason) {
        mapper.updateApprovalDecision(approvalId, status, actor, at, reason);
    }

    public void operation(String approvalId, String status, String response) {
        mapper.updateApprovalOperation(approvalId, status, response);
    }

    public void saveAlert(AlertDO row) {
        mapper.upsertAlert(row);
    }

    public List<AlertDO> alerts() {
        return mapper.selectAlerts();
    }

    public AlertDO alert(String alertId) {
        return mapper.selectAlert(alertId);
    }
}
