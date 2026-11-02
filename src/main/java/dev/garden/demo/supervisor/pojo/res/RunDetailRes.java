package dev.garden.demo.supervisor.pojo.res;

import dev.garden.demo.supervisor.pojo.dto.ApprovalDTO;
import dev.garden.demo.supervisor.pojo.dto.ChildRunDTO;
import dev.garden.demo.supervisor.pojo.dto.CompactionDTO;
import dev.garden.demo.supervisor.pojo.dto.CorrectionEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.DelegationDTO;
import dev.garden.demo.supervisor.pojo.dto.InstructionEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.PermissionsDTO;
import dev.garden.demo.supervisor.pojo.dto.PromptVersionDTO;
import dev.garden.demo.supervisor.pojo.dto.RecallDTO;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;
import dev.garden.demo.supervisor.pojo.dto.RoutingEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.RunEventDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.SpanDTO;
import dev.garden.demo.supervisor.pojo.dto.ToolCallDTO;
import dev.garden.demo.supervisor.pojo.dto.TrustEvidenceDTO;
import dev.garden.demo.supervisor.pojo.dto.UsageDTO;

import java.util.List;
import java.util.Map;

/**
 * 运行详情读模型。
 *
 * <p>只读、脱敏，用于页面逐项核对计划、父子关系、工具配对、委派回执、用量、span、
 * 压缩、召回、权限与检查点。完整模型会话（{@code loop:v3.3}）、召回原文、认证令牌
 * 和下游凭据永不进入本响应。
 *
 * <p>原有 {@code RunRes} 字段与含义保持不变；本响应是追加读取入口。
 */
public record RunDetailRes(
        ResultDTO result,
        String runId,
        String requestId,
        String sessionId,
        String state,
        int revision,
        int generation,
        int consumedRevision,
        int modelCalls,
        int maxModelCalls,
        long deadline,
        String replacedBy,
        String parentRunId,
        String rootRunId,
        String agentPath,
        String agentRole,
        String agentVersion,
        boolean accepted,
        Map<String, Object> plan,
        List<ChildRunDTO> children,
        List<ToolCallDTO> toolCalls,
        List<DelegationDTO> delegations,
        UsageDTO usage,
        List<SpanDTO> spans,
        List<CompactionDTO> compactions,
        List<RecallDTO> recalls,
        PermissionsDTO permissions,
        List<PromptVersionDTO> promptVersions,
        Map<String, String> checkpoints,
        List<ApprovalDTO> approvals,
        List<SideEffectEvidenceDTO> sideEffects,
        List<String> events,
        long createdAt,
        long firstEventAt,
        long completedAt,
        String triggerMode,
        String acceptanceHint,
        boolean recovered,
        String requestFingerprint,
        List<InstructionEvidenceDTO> instructions,
        List<RunEventDTO> timeline,
        RoutingEvidenceDTO routing,
        List<CorrectionEvidenceDTO> corrections,
        List<TrustEvidenceDTO> contentTrust) {}
