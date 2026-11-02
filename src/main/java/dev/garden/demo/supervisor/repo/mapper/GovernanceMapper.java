package dev.garden.demo.supervisor.repo.mapper;

import dev.garden.demo.supervisor.pojo.dataobject.AgentDO;
import dev.garden.demo.supervisor.pojo.dataobject.AgentReleaseDO;
import dev.garden.demo.supervisor.pojo.dataobject.AlertDO;
import dev.garden.demo.supervisor.pojo.dataobject.ApprovalDO;
import dev.garden.demo.supervisor.pojo.dataobject.PromptDO;
import dev.garden.demo.supervisor.pojo.dataobject.PromptReleaseDO;
import dev.garden.demo.supervisor.pojo.dataobject.RegistryAuditDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 平台治理表映射；SQL 位于 resources/mapper/GovernanceMapper.xml。 */
@Mapper
public interface GovernanceMapper {
    void createPromptsTable();

    void createPromptReleasesTable();

    void createAgentsTable();

    void createAgentReleasesTable();

    void createRegistryAuditTable();

    void createApprovalsTable();

    void createAlertsTable();

    int insertPrompt(PromptDO row);

    PromptDO selectPrompt(@Param("promptId") String promptId, @Param("version") String version);

    List<PromptDO> selectPrompts();

    PromptDO selectActivePrompt(@Param("role") String role);

    void upsertPromptRelease(PromptReleaseDO row);

    List<PromptReleaseDO> selectPromptReleases();

    int insertAgent(AgentDO row);

    AgentDO selectAgent(@Param("agentId") String agentId, @Param("version") String version);

    List<AgentDO> selectAgents();

    void insertAgentRelease(AgentReleaseDO row);

    void supersedeAgentReleases(@Param("agentId") String agentId);

    List<AgentReleaseDO> selectAgentReleases(@Param("agentId") String agentId);

    AgentReleaseDO selectActiveRelease(@Param("role") String role);

    void insertAudit(RegistryAuditDO row);

    List<RegistryAuditDO> selectAudit(@Param("limit") int limit);

    int insertApproval(ApprovalDO row);

    ApprovalDO selectApproval(@Param("approvalId") String approvalId);

    ApprovalDO selectApprovalByRunToolOp(
            @Param("runId") String runId,
            @Param("toolName") String toolName,
            @Param("operationId") String operationId);

    List<ApprovalDO> selectApprovalsByRun(@Param("runId") String runId);

    int countApprovals(@Param("status") String status);

    int countDecidedApprovals();

    void updateApprovalDecision(
            @Param("approvalId") String approvalId,
            @Param("status") String status,
            @Param("decidedBy") String decidedBy,
            @Param("decidedAt") long decidedAt,
            @Param("reason") String reason);

    void updateApprovalOperation(
            @Param("approvalId") String approvalId,
            @Param("operationStatus") String operationStatus,
            @Param("operationResponse") String operationResponse);

    void upsertAlert(AlertDO row);

    List<AlertDO> selectAlerts();

    AlertDO selectAlert(@Param("alertId") String alertId);
}
