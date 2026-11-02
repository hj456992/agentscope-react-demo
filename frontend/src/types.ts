export type Mode = "async" | "sync";
export type Action = "CANCEL" | "SWITCH" | "MESSAGE";
export interface ChatPayload {
  sessionId: string;
  question: string;
  history: [];
  requestId: string;
  /** 受控验收提示；仅实例开启受控触发且命中白名单时生效，正常请求不填写。 */
  acceptanceHint?: string;
}
export interface Evidence {
  tool: string;
  status: string;
  source: string;
  data: Record<string, unknown>;
}
export interface Run {
  runId: string;
  revision: number;
  requestId: string;
  state: string;
  status: string;
  originalQuery: string;
  answer: string;
  agents: string[];
  evidence: Evidence[];
  events: string[];
  modelCalls: number;
  deadline: number;
  replacedBy: string | null;
}
export interface ToolCallEvidence {
  runId: string;
  agentPath: string;
  callId: string;
  tool: string;
  argumentFingerprint: string;
  status: string;
  startedAt: number | null;
  endedAt: number | null;
  durationMs: number | null;
  resultBytes: number;
  resultSummary: string;
  operationId: string | null;
  paired: boolean;
  reused: boolean;
  failureClass: string | null;
  providerCode: string | null;
  retryAfterMs: number | null;
}

/** 子任务实际收到的任务书；只返回摘要、依赖和继承历史字节数。 */
export interface TaskBriefEvidence {
  objective: string;
  role: string;
  planTaskId: string;
  dependsOn: string[];
  batch: number;
  outputFormat: string;
  budgetModelCalls: number;
  deadline: number;
  inheritedMessages: number;
  inheritedBytes: number;
  forkMode: string;
  spawnOperationId: string;
}
export interface DelegationEvidence {
  parentRunId: string;
  parentAgentPath: string;
  operationId: string;
  receiptKey: string;
  fingerprint: string;
  childRunId: string | null;
  childAgentPath: string | null;
  childRole: string | null;
  childStatus: string | null;
  requiredPermissions: string[];
}
export interface ChildRunEvidence {
  runId: string;
  parentRunId: string | null;
  agentPath: string;
  agentRole: string;
  agentVersion: string;
  state: string;
  agentStatus: string;
  generation: number;
  revision: number;
  modelCalls: number;
  maxModelCalls: number;
  deadline: number;
  createdAt: number;
  accepted: boolean;
  answer: string | null;
  resultStatus: string | null;
  resultDigest: string | null;
  evidenceTools: string[];
  requiredPermissions: string[];
  taskBrief: TaskBriefEvidence | null;
  spawnOperationId: string;
  acceptanceReceiptKey: string | null;
  completedAt: number;
  recovered: boolean;
}
export interface AgentUsageEvidence {
  runId: string;
  agentPath: string;
  agentRole: string;
  modelCalls: number;
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
  source: string;
  ownModelCalls: number;
  subtreeModelCalls: number;
  remainingModelCalls: number;
  sharedBudget: boolean;
}
export interface UsageEvidence {
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
  source: string;
  agents: AgentUsageEvidence[];
}
export interface SpanEvidence {
  traceId: string;
  spanId: string;
  parentSpanId: string | null;
  kind: string;
  runId: string;
  agentPath: string;
  name: string;
  status: string;
  startedAt: number | null;
  endedAt: number | null;
  durationMs: number | null;
  errorCode: string | null;
}
export interface CompactionEvidence {
  runId: string;
  agentPath: string;
  generation: number;
  summaryBytes: number;
  summary: string;
  events: string[];
}
export interface RecallEvidence {
  runId: string;
  agentPath: string;
  generation: number;
  storedChars: number;
  available: boolean;
}
export interface PermissionEvidence {
  allowedTools: string[];
  requiredTools: string[];
  filteredTools: string[];
  evidenceTools: string[];
  evidenceCount: number;
  evidenceRecheckPassed: boolean;
  directoryTools: string[];
  executionTools: string[];
  filteredReasons: Record<string, string>;
}
export interface PromptVersionEvidence {
  role: string;
  promptId: string;
  version: string;
  hash: string;
  source: string;
}
export interface InstructionEvidence {
  instructionId: string;
  action: string;
  expectedRevision: number;
  resultRevision: number;
  resultState: string;
  resultRunId: string;
  result: string;
  fingerprint: string;
  createdAt: number;
}
export interface RunEventEvidence {
  sequence: number;
  at: number;
  text: string;
}
export interface RoutingCandidate {
  role: string;
  catalogTools: string[];
  permitted: boolean;
  selected: boolean;
  reason: string;
  confidence: string;
}
export interface RoutingEvidence {
  decision: string;
  reason: string;
  confidence: string;
  candidates: RoutingCandidate[];
}
export interface TrustEvidence {
  level: string;
  source: string;
  handling: string;
  count: number;
}

export interface CorrectionEvidence {
  type: string;
  tool: string;
  count: number;
}
export interface RunResult {
  status: string;
  originalQuery: string;
  rewrittenQuery: string;
  intent: string;
  answer: string;
  agents: string[];
  evidence: Evidence[];
  events: string[];
}
export interface RunDetail {
  result: RunResult | null;
  runId: string;
  requestId: string;
  sessionId: string;
  state: string;
  revision: number;
  generation: number;
  consumedRevision: number;
  modelCalls: number;
  maxModelCalls: number;
  deadline: number;
  replacedBy: string | null;
  parentRunId: string | null;
  rootRunId: string;
  agentPath: string;
  agentRole: string;
  agentVersion: string;
  accepted: boolean;
  plan: Record<string, unknown>;
  children: ChildRunEvidence[];
  toolCalls: ToolCallEvidence[];
  delegations: DelegationEvidence[];
  usage: UsageEvidence;
  spans: SpanEvidence[];
  compactions: CompactionEvidence[];
  recalls: RecallEvidence[];
  permissions: PermissionEvidence;
  promptVersions: PromptVersionEvidence[];
  checkpoints: Record<string, string>;
  approvals: unknown[];
  sideEffects: unknown[];
  events: string[];
  createdAt: number;
  firstEventAt: number;
  completedAt: number;
  triggerMode: string;
  acceptanceHint: string | null;
  recovered: boolean;
  requestFingerprint: string;
  instructions: InstructionEvidence[];
  timeline: RunEventEvidence[];
  routing: RoutingEvidence;
  corrections: CorrectionEvidence[];
  contentTrust: TrustEvidence[];
}
export interface ConfigEvidence {
  mode: string;
  timeoutSeconds: number;
  maxModelCalls: number;
  maxOutputTokens: number;
  maxSteps: number;
  maxConcurrentRequests: number;
  maxConcurrentPerUser: number;
  tasksPerUserMinute: number;
  modelCallsPerMinute: number;
  modelTokensPerMinute: number;
  maxStoredTasks: number;
  taskRetentionSeconds: number;
  conversationRetentionSeconds: number;
  preferenceRetentionSeconds: number;
  databaseType: string;
  authIdentities: string[];
  businessRulesEnabled: boolean;
  businessRuleCount: number;
  factCheckEnabled: boolean;
  semanticVerifierEnabled: boolean;
  correctionLimitsEnabled: boolean;
  multiAgentMode: string;
  multiAgentPlannerEnabled: boolean;
  multiAgentMaxAgents: number;
  multiAgentMaxDepth: number;
  topicSegmentationEnabled: boolean;
  summaryFidelityAlertEnabled: boolean;
  summaryFidelityThreshold: number;
  summaryMissingConditionPolicy: string;
  contextRecallEnabled: boolean;
  executionLimitsEnabled: boolean;
  loopDetectionEnabled: boolean;
  semanticLoopDetectionEnabled: boolean;
  maxRepeatedToolCalls: number;
  acceptanceHintsEnabled: boolean;
  downstreamEnabled: boolean;
  downstreamTimeoutSeconds: number;
  toolDelayMs: number;
}
export interface Instruction {
  runId: string;
  revision: number;
  state: string;
  message: string;
}
export interface Trace {
  method: string;
  path: string;
  status: number | null;
  ms: number;
  time: string;
}
export interface Turn {
  key: string;
  payload: ChatPayload;
  mode: Mode;
  runId?: string;
  result?: Run;
  phase: "submitting" | "running" | "ready" | "error" | "uncertain" | "restore";
  error: string;
  traces: Trace[];
  inputs: string[];
  visible: boolean;
  busy: boolean;
  paused: boolean;
  generation: number;
  lastInstruction?: InstructionPayload;
  instructionNotice?: string;
  probes: Probe[];
}
export interface Session {
  id: string;
  title: string;
  turns: Turn[];
}

export interface InstructionPayload {
  runId: string;
  instructionId: string;
  /** 停止不需要版本号；其他动作必须带上最新版本。 */
  expectedRevision?: number;
  action: Action;
  message: string;
}
export interface Probe {
  kind: string;
  time: string;
  httpStatus: number | null;
  request: ChatPayload | InstructionPayload;
  outcome: string;
  runId?: string;
}

/** 注册表目录项；签名只返回校验结论，不返回签名密钥。 */
export interface AgentEntry {
  agentId: string;
  version: string;
  role: string;
  owner: string;
  oncall: string;
  sloMs: number;
  costBudget: number;
  dataClass: string;
  deployUnit: string;
  status: string;
  signatureVerified: boolean;
  allowedTools: string[];
  networkAllowlist: string[];
  schemaBytes: number;
  createdAt: number;
}

/** 动态候选目录与 Schema 注入预算统计。 */
export interface AgentCatalog {
  total: number;
  executable: number;
  candidatesAfterPermission: number;
  filteredByPermission: number;
  schemaBytes: number;
  schemaBudgetBytes: number;
  overBudget: boolean;
  entries: AgentEntry[];
  events: string[];
}

/** 提示词版本元数据；响应刻意不包含正文。 */
export interface PromptEntry {
  promptId: string;
  version: string;
  role: string;
  owner: string;
  status: string;
  hash: string;
  evalRecord: string;
  createdBy: string;
  createdAt: number;
}

export interface PromptRelease {
  role: string;
  promptId: string;
  version: string;
  actor: string;
  updatedAt: number;
}

export interface AgentRelease {
  agentId: string;
  version: string;
  trafficPercent: number;
  action: string;
  status: string;
  actor: string;
  createdAt: number;
}

export interface AuditEntry {
  auditId: number;
  action: string;
  subject: string;
  outcome: string;
  detail: string;
  actor: string;
  createdAt: number;
}

export interface AlertEntry {
  alertId: string;
  kind: string;
  severity: string;
  message: string;
  metricValue: number;
  thresholdValue: number;
  status: string;
  createdAt: number;
  updatedAt: number;
}

export interface Metrics {
  collectedAt: number;
  tasks: number;
  running: number;
  completed: number;
  failureRate: number;
  p95DurationMs: number;
  modelCalls: number;
  promptTokens: number;
  completionTokens: number;
  toolCalls: number;
  failedToolCalls: number;
  rateLimitedEvents: number;
  queueRejections: number;
  approvalsPending: number;
  approvalsDecided: number;
  byState: Record<string, number>;
  alertsOpen: number;
  alerts: AlertEntry[];
}

export interface ApprovalEntry {
  approvalId: string;
  runId: string;
  toolName: string;
  operationId: string;
  requestHash: string;
  status: string;
  requestedBy: string;
  requiredPermission: string;
  requestedAt: number | null;
  expiresAt: number | null;
  decidedBy: string | null;
  decidedAt: number | null;
  reason: string | null;
  operationStatus: string | null;
  operationResponse: string | null;
}

export interface OperationEntry {
  tool: string;
  operationId: string;
  status: string;
  parameterHash: string;
  updatedAt: number;
  submitAttempts: number;
  lookupAttempts: number;
  response: string | null;
}


export interface MemoryCandidate {
  candidateId: string;
  key: string;
  value: string;
  source: string;
  status: string;
  expectedVersion: number;
  requestedAt: string;
  expiresAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
  reason: string | null;
  appliedVersion: number | null;
}

export interface MemoryVectorHit {
  key: string;
  value: string;
  version: number;
  source: string;
  score: number;
  embedding: string;
  dataClass: string;
}

export interface MemoryGovernance {
  conversationRetentionHours: number;
  preferenceRetentionHours: number;
  embedding: string;
  embeddingLocation: string;
  storage: string;
  crossReplica: string;
  dataResidency: string;
  pendingCandidates: number;
  approvedCandidates: number;
  rejectedCandidates: number;
  activeVectors: number;
  tombstones: number;
}
