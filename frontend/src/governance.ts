import { governanceRequest, governanceRequestAs } from "./store";
import type {
  AgentCatalog,
  AgentEntry,
  AgentRelease,
  AlertEntry,
  ApprovalEntry,
  AuditEntry,
  Metrics,
  OperationEntry,
  PromptEntry,
  PromptRelease,
} from "./types";

/**
 * 治理接口封装。
 *
 * <p>所有路径和字段均对应服务端真实接口；提示词正文只在创建请求中出现，
 * 响应和页面记录都不保存正文。
 */

const segment = (value: string) => encodeURIComponent(value);

export function loadAgents(): Promise<AgentEntry[]> {
  return governanceRequest("/api/supervisor/agents");
}

export function loadCatalog(): Promise<AgentCatalog> {
  return governanceRequest("/api/supervisor/agents/catalog");
}

export function loadAgentReleases(agentId: string): Promise<AgentRelease[]> {
  return governanceRequest(`/api/supervisor/agents/${segment(agentId)}/releases`);
}

export function releaseAgent(body: {
  agentId: string;
  version: string;
  trafficPercent: number;
  actor: string;
}): Promise<AgentRelease> {
  return governanceRequest("/api/supervisor/agents/releases", {
    method: "POST",
    body,
  });
}

export function rollbackAgent(body: {
  agentId: string;
  version: string;
  actor: string;
}): Promise<AgentRelease> {
  return governanceRequest("/api/supervisor/agents/rollbacks", {
    method: "POST",
    body,
  });
}

export function loadPrompts(): Promise<PromptEntry[]> {
  return governanceRequest("/api/supervisor/prompts");
}

export function loadPromptReleases(): Promise<PromptRelease[]> {
  return governanceRequest("/api/supervisor/prompts/releases");
}

export function createPrompt(body: {
  promptId: string;
  version: string;
  role: string;
  owner: string;
  content: string;
  evalRecord: string;
  status: string;
  actor: string;
}): Promise<PromptEntry> {
  return governanceRequest("/api/supervisor/prompts", {
    method: "POST",
    body,
  });
}

export function releasePrompt(body: {
  role: string;
  promptId: string;
  version: string;
  actor: string;
}): Promise<void> {
  return governanceRequest("/api/supervisor/prompts/releases", {
    method: "POST",
    body,
  });
}

export function loadMetrics(): Promise<Metrics> {
  return governanceRequest("/api/supervisor/metrics");
}

export function loadAlerts(): Promise<AlertEntry[]> {
  return governanceRequest("/api/supervisor/alerts");
}

export function loadAudit(): Promise<AuditEntry[]> {
  return governanceRequest("/api/supervisor/registry/audit");
}

export function loadApproval(approvalId: string): Promise<ApprovalEntry> {
  return governanceRequest(`/api/supervisor/approvals/${segment(approvalId)}`);
}

export function decideApproval(
  body: {
    approvalId: string;
    decision: "APPROVE" | "REJECT";
    reason: string;
  },
  approverToken: string,
): Promise<ApprovalEntry> {
  // 审批使用单独的审批人令牌；申请人令牌即使拥有写工具权限也不能自己批准。
  return governanceRequestAs("/api/supervisor/approvals", approverToken, {
    method: "POST",
    body,
  });
}

export function loadOperation(
  tool: string,
  operationId: string,
): Promise<OperationEntry> {
  return governanceRequest(
    `/api/supervisor/operations/${segment(tool)}/${segment(operationId)}`,
  );
}

export function loadMemoryCandidates(): Promise<import("./types").MemoryCandidate[]> {
  return governanceRequest("/api/supervisor/memories/candidates");
}

export function proposeMemoryCandidate(body: {
  key: string;
  value: string;
  source: string;
  expectedVersion: number;
  expiresAt?: string | null;
}): Promise<import("./types").MemoryCandidate> {
  return governanceRequest("/api/supervisor/memories/candidates", {
    method: "POST",
    body,
  });
}

export function decideMemoryCandidate(body: {
  candidateId: string;
  decision: "APPROVE" | "REJECT";
  reason: string;
}): Promise<import("./types").MemoryCandidate> {
  return governanceRequest("/api/supervisor/memories/candidates/decisions", {
    method: "POST",
    body,
  });
}

export function searchMemories(body: {
  query: string;
  limit: number;
}): Promise<import("./types").MemoryVectorHit[]> {
  return governanceRequest("/api/supervisor/memories/search", {
    method: "POST",
    body,
  });
}

export function loadMemoryGovernance(): Promise<import("./types").MemoryGovernance> {
  return governanceRequest("/api/supervisor/memories/governance");
}
