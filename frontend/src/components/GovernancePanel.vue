<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import Icon from "./Icon.vue";
import { ApiError } from "../api";
import { authenticated, credentialEpoch } from "../store";
import {
  createPrompt,
  decideApproval,
  loadAgentReleases,
  loadAgents,
  loadAlerts,
  loadApproval,
  loadAudit,
  loadCatalog,
  loadMemoryCandidates,
  loadMemoryGovernance,
  loadMetrics,
  loadOperation,
  loadPromptReleases,
  loadPrompts,
  proposeMemoryCandidate,
  releaseAgent,
  releasePrompt,
  rollbackAgent,
  searchMemories,
  decideMemoryCandidate,
} from "../governance";
import type {
  AgentCatalog,
  AgentEntry,
  AgentRelease,
  AlertEntry,
  ApprovalEntry,
  AuditEntry,
  MemoryCandidate,
  MemoryGovernance,
  MemoryVectorHit,
  Metrics,
  OperationEntry,
  PromptEntry,
  PromptRelease,
} from "../types";

const emit = defineEmits<{ close: [] }>();

type Tab =
  | "catalog"
  | "prompts"
  | "memory"
  | "metrics"
  | "alerts"
  | "approvals"
  | "audit";

const tabs: { id: Tab; label: string }[] = [
  { id: "catalog", label: "目录" },
  { id: "prompts", label: "提示词" },
  { id: "memory", label: "记忆" },
  { id: "metrics", label: "指标" },
  { id: "alerts", label: "告警" },
  { id: "approvals", label: "审批" },
  { id: "audit", label: "审计" },
];

const panel = ref<HTMLDialogElement>();
const closeButton = ref<HTMLButtonElement>();
const previousFocus =
  document.activeElement instanceof HTMLElement ? document.activeElement : null;
const tab = ref<Tab>("catalog");
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const notice = ref("");

const agents = ref<AgentEntry[]>([]);
const catalog = ref<AgentCatalog>();
const agentReleases = ref<AgentRelease[]>([]);
const prompts = ref<PromptEntry[]>([]);
const promptReleases = ref<PromptRelease[]>([]);
const metrics = ref<Metrics>();
const alerts = ref<AlertEntry[]>([]);
const audit = ref<AuditEntry[]>([]);
const memoryCandidates = ref<MemoryCandidate[]>([]);
const memoryHits = ref<MemoryVectorHit[]>([]);
const memoryGovernance = ref<MemoryGovernance>();

const releaseForm = reactive({
  agentId: "travel.weather",
  version: "1",
  trafficPercent: 100,
  actor: "platform-admin",
});
const rollbackForm = reactive({
  agentId: "travel.weather",
  version: "1",
  actor: "platform-admin",
});
const promptReleaseForm = reactive({
  role: "weather_agent",
  promptId: "prompt.travel.weather_agent",
  version: "1",
  actor: "platform-admin",
});
const promptCreateForm = reactive({
  promptId: "",
  version: "",
  role: "supervisor",
  owner: "travel-platform",
  content: "",
  evalRecord: "",
  status: "PUBLISHED",
  actor: "platform-admin",
});
const approvalLookup = reactive({ approvalId: "" });
const approval = ref<ApprovalEntry>();
const decision = reactive({ reason: "" });
/** 审批人令牌只保存在本组件内存，用于四眼原则；不写入存储或日志。 */
const approverToken = ref("");
const operationLookup = reactive({ tool: "book_ticket", operationId: "" });
const operation = ref<OperationEntry>();
const memoryCandidateForm = reactive({
  key: "preferredDepartureCity",
  value: "上海",
  source: "用户主动提交的验收候选",
  expectedVersion: 0,
  expiresAt: "",
});
const memorySearchForm = reactive({ query: "上海", limit: 5 });

function close() {
  approverToken.value = "";
  emit("close");
}

function message(value: unknown) {
  if (value instanceof ApiError) {
    return value.message;
  }
  return "治理接口暂时不可用，请稍后重试。";
}

function stamp(value: number | null | undefined) {
  if (!value) {
    return "—";
  }
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
}

function percent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}

function amount(value: number) {
  return value.toLocaleString("zh-CN");
}

async function loadAll() {
  if (!authenticated.value) {
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    const [
      agentList,
      catalogValue,
      promptList,
      releases,
      metricsValue,
      alertList,
      auditList,
      candidateList,
      governance,
    ] = await Promise.all([
      loadAgents(),
      loadCatalog(),
      loadPrompts(),
      loadPromptReleases(),
      loadMetrics(),
      loadAlerts(),
      loadAudit(),
      loadMemoryCandidates(),
      loadMemoryGovernance(),
    ]);
    agents.value = agentList;
    catalog.value = catalogValue;
    prompts.value = promptList;
    promptReleases.value = releases;
    metrics.value = metricsValue;
    alerts.value = alertList;
    audit.value = auditList;
    memoryCandidates.value = candidateList;
    memoryGovernance.value = governance;
  } catch (failure) {
    error.value = message(failure);
  } finally {
    loading.value = false;
  }
}

async function refreshAgents() {
  catalog.value = await loadCatalog();
  agents.value = await loadAgents();
  audit.value = await loadAudit();
}

async function showAgentReleases(agentId: string) {
  try {
    agentReleases.value = await loadAgentReleases(agentId);
    releaseForm.agentId = agentId;
    rollbackForm.agentId = agentId;
  } catch (failure) {
    error.value = message(failure);
  }
}

async function submitRelease() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const result = await releaseAgent({
      agentId: releaseForm.agentId.trim(),
      version: releaseForm.version.trim(),
      trafficPercent: Number(releaseForm.trafficPercent),
      actor: releaseForm.actor.trim(),
    });
    notice.value = `已发布 ${result.agentId}@${result.version}，灰度 ${result.trafficPercent}%。`;
    await refreshAgents();
    await showAgentReleases(result.agentId);
  } catch (failure) {
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function submitRollback() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const result = await rollbackAgent({
      agentId: rollbackForm.agentId.trim(),
      version: rollbackForm.version.trim(),
      actor: rollbackForm.actor.trim(),
    });
    notice.value = `已回滚 ${result.agentId}@${result.version}。`;
    await refreshAgents();
    await showAgentReleases(result.agentId);
  } catch (failure) {
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function submitPromptRelease() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await releasePrompt({
      role: promptReleaseForm.role.trim(),
      promptId: promptReleaseForm.promptId.trim(),
      version: promptReleaseForm.version.trim(),
      actor: promptReleaseForm.actor.trim(),
    });
    promptReleases.value = await loadPromptReleases();
    notice.value = `已将 ${promptReleaseForm.role} 绑定到 ${promptReleaseForm.version}。`;
  } catch (failure) {
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function submitPromptCreate() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const created = await createPrompt({
      promptId: promptCreateForm.promptId.trim(),
      version: promptCreateForm.version.trim(),
      role: promptCreateForm.role.trim(),
      owner: promptCreateForm.owner.trim(),
      content: promptCreateForm.content,
      evalRecord: promptCreateForm.evalRecord.trim(),
      status: promptCreateForm.status,
      actor: promptCreateForm.actor.trim(),
    });
    prompts.value = await loadPrompts();
    notice.value = `已登记 ${created.promptId}@${created.version}，内容未回显。`;
    promptCreateForm.promptId = "";
    promptCreateForm.version = "";
    promptCreateForm.content = "";
    promptCreateForm.evalRecord = "";
  } catch (failure) {
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function submitMemoryCandidate() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const created = await proposeMemoryCandidate({
      key: memoryCandidateForm.key,
      value: memoryCandidateForm.value.trim(),
      source: memoryCandidateForm.source.trim(),
      expectedVersion: Number(memoryCandidateForm.expectedVersion),
      expiresAt: memoryCandidateForm.expiresAt
        ? new Date(memoryCandidateForm.expiresAt).toISOString()
        : null,
    });
    memoryCandidates.value = await loadMemoryCandidates();
    memoryGovernance.value = await loadMemoryGovernance();
    notice.value = `已提交候选 ${created.candidateId.slice(0, 8)}，状态 ${created.status}；批准前不进入长期偏好。`;
  } catch (failure) {
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function decideCandidate(candidate: MemoryCandidate, value: "APPROVE" | "REJECT") {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await decideMemoryCandidate({
      candidateId: candidate.candidateId,
      decision: value,
      reason: value === "APPROVE" ? "验收人工批准" : "验收人工拒绝",
    });
    memoryCandidates.value = await loadMemoryCandidates();
    memoryGovernance.value = await loadMemoryGovernance();
    notice.value =
      value === "APPROVE"
        ? "候选已批准并写入长期偏好与本地向量索引。"
        : "候选已拒绝，未写入长期偏好。";
  } catch (failure) {
    error.value = message(failure);
    memoryCandidates.value = await loadMemoryCandidates();
  } finally {
    busy.value = false;
  }
}

async function searchMemoryVectors() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    memoryHits.value = await searchMemories({
      query: memorySearchForm.query.trim(),
      limit: Number(memorySearchForm.limit),
    });
    notice.value = `本地哈希向量检索返回 ${memoryHits.value.length} 条；不宣称生产向量库能力。`;
  } catch (failure) {
    memoryHits.value = [];
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function lookupApproval() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  operation.value = undefined;
  try {
    approval.value = await loadApproval(approvalLookup.approvalId.trim());
    operationLookup.tool = approval.value.toolName;
    operationLookup.operationId = approval.value.operationId;
  } catch (failure) {
    approval.value = undefined;
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function submitDecision(value: "APPROVE" | "REJECT") {
  if (!approval.value) {
    return;
  }
  if (!approverToken.value.trim()) {
    error.value = "请填写审批人令牌；审批权限与申请权限必须分开。";
    return;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    approval.value = await decideApproval(
      {
        approvalId: approval.value.approvalId,
        decision: value,
        reason: decision.reason.trim() || (value === "APPROVE" ? "验收批准" : "验收拒绝"),
      },
      approverToken.value.trim(),
    );
    notice.value =
      value === "APPROVE"
        ? `审批已通过，操作状态为 ${approval.value.operationStatus ?? "未执行"}。`
        : "审批已拒绝，未调用下游。";
    metrics.value = await loadMetrics();
    audit.value = await loadAudit();
  } catch (failure) {
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

async function lookupOperation() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    operation.value = await loadOperation(
      operationLookup.tool.trim(),
      operationLookup.operationId.trim(),
    );
  } catch (failure) {
    operation.value = undefined;
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}

function keyboard(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    close();
    return;
  }
  if (event.key !== "Tab" || !panel.value) {
    return;
  }
  const targets = Array.from(
    panel.value.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex="0"]',
    ),
  ).filter(element => element.getClientRects().length > 0);
  const first = targets[0];
  const last = targets.at(-1);
  if (!first || !last) {
    event.preventDefault();
    panel.value.focus();
  } else if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

watch(credentialEpoch, () => {
  approval.value = undefined;
  operation.value = undefined;
  decision.reason = "";
  approverToken.value = "";
  if (authenticated.value) {
    void loadAll();
  }
});

onMounted(() => {
  panel.value?.showModal();
  closeButton.value?.focus();
  void loadAll();
});

onBeforeUnmount(() => {
  panel.value?.close();
  if (previousFocus?.isConnected) {
    previousFocus.focus();
  }
});
</script>

<template>
  <Teleport to="body">
    <dialog
      ref="panel"
      class="gov-panel"
      aria-labelledby="gov-title"
      aria-describedby="gov-description"
      @cancel.prevent="close"
      @keydown="keyboard"
      @click="event => { if (event.target === panel) { close(); } }"
    >
      <div class="gov-shell">
        <header class="gov-header">
          <div class="gov-heading">
            <span class="gov-mark"><Icon name="settings" :size="22" /></span>
            <div>
              <p class="gov-eyebrow">本地真实注册表、审批与指标</p>
              <h2 id="gov-title">平台治理</h2>
            </div>
          </div>
          <div class="gov-header-actions">
            <button
              type="button"
              class="gov-icon-button"
              aria-label="刷新治理数据"
              :disabled="loading"
              @click="loadAll"
            >
              <Icon name="refresh" :size="18" />
            </button>
            <button
              ref="closeButton"
              type="button"
              class="gov-icon-button"
              aria-label="关闭平台治理"
              @click="close"
            >
              <Icon name="close" />
            </button>
          </div>
        </header>

        <nav class="gov-tabs" aria-label="治理分类">
          <button
            v-for="item in tabs"
            :key="item.id"
            type="button"
            :class="{ active: tab === item.id }"
            :aria-current="tab === item.id ? 'page' : undefined"
            @click="tab = item.id"
          >
            {{ item.label }}
            <span v-if="item.id === 'alerts' && metrics" class="gov-tab-count">
              {{ metrics.alertsOpen }}
            </span>
            <span v-else-if="item.id === 'approvals' && metrics" class="gov-tab-count">
              {{ metrics.approvalsPending }}
            </span>
          </button>
        </nav>

        <div class="gov-body">
          <p id="gov-description" class="gov-description">
            数据来自当前实例的数据库和本地下游；提示词正文、签名密钥与令牌不会出现在本面板。
          </p>

          <section v-if="!authenticated" class="gov-empty">
            <span class="gov-empty-icon"><Icon name="key" :size="26" /></span>
            <h3>先连接你的身份</h3>
            <p>请关闭面板并配置访问令牌，再读取当前身份的治理数据。</p>
          </section>

          <template v-else>
            <p v-if="error" class="gov-feedback gov-error" role="alert">{{ error }}</p>
            <p v-else-if="notice" class="gov-feedback gov-success" role="status">{{ notice }}</p>
            <p v-if="loading" class="gov-loading" role="status">
              <span class="gov-spinner"></span>正在读取治理数据…
            </p>

            <section v-if="tab === 'catalog'" class="gov-section" aria-label="Agent 目录">
              <div v-if="catalog" class="gov-tiles">
                <div><span>目录项</span><strong>{{ catalog.total }}</strong></div>
                <div><span>可执行</span><strong>{{ catalog.executable }}</strong></div>
                <div><span>权限过滤后候选</span><strong>{{ catalog.candidatesAfterPermission }}</strong></div>
                <div><span>被权限过滤</span><strong>{{ catalog.filteredByPermission }}</strong></div>
                <div :class="{ alert: catalog.overBudget }">
                  <span>Schema 字节</span>
                  <strong>{{ catalog.schemaBytes }} / {{ catalog.schemaBudgetBytes }}</strong>
                </div>
              </div>
              <p v-if="catalog?.overBudget" class="gov-budget-warning">
                已超过 Schema 注入预算，目录记录压缩/停止事件，不静默截断。
              </p>
              <ul v-if="catalog?.events.length" class="gov-events">
                <li v-for="event in catalog.events" :key="event">{{ event }}</li>
              </ul>

              <h3 class="gov-subtitle">发布与回滚</h3>
              <div class="gov-forms">
                <form class="gov-form" @submit.prevent="submitRelease">
                  <label>发布版本
                    <input v-model="releaseForm.agentId" aria-label="发布 agentId" placeholder="travel.weather" />
                  </label>
                  <label>版本
                    <input v-model="releaseForm.version" aria-label="发布版本号" placeholder="1" />
                  </label>
                  <label>灰度比例
                    <input v-model.number="releaseForm.trafficPercent" type="number" min="0" max="100" aria-label="灰度比例" />
                  </label>
                  <label>操作人
                    <input v-model="releaseForm.actor" aria-label="发布操作人" />
                  </label>
                  <button type="submit" class="gov-button gov-primary" :disabled="busy">发布</button>
                </form>
                <form class="gov-form" @submit.prevent="submitRollback">
                  <label>回滚版本
                    <input v-model="rollbackForm.agentId" aria-label="回滚 agentId" placeholder="travel.weather" />
                  </label>
                  <label>版本
                    <input v-model="rollbackForm.version" aria-label="回滚版本号" placeholder="1" />
                  </label>
                  <label>操作人
                    <input v-model="rollbackForm.actor" aria-label="回滚操作人" />
                  </label>
                  <button type="submit" class="gov-button" :disabled="busy">回滚</button>
                </form>
              </div>
              <div v-if="agentReleases.length" class="gov-release-log">
                <h4>最近发布记录</h4>
                <table class="gov-table">
                  <thead><tr><th>Agent</th><th>版本</th><th>动作</th><th>灰度</th><th>操作人</th><th>时间</th></tr></thead>
                  <tbody>
                    <tr v-for="row in agentReleases" :key="`${row.agentId}-${row.version}-${row.createdAt}`">
                      <td>{{ row.agentId }}</td><td>{{ row.version }}</td><td>{{ row.action }}</td>
                      <td>{{ row.trafficPercent }}%</td><td>{{ row.actor }}</td><td>{{ stamp(row.createdAt) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <h3 class="gov-subtitle">目录项</h3>
              <div class="gov-table-wrap">
                <table class="gov-table">
                  <thead>
                    <tr>
                      <th>Agent</th><th>角色</th><th>状态</th><th>验签</th><th>Owner</th>
                      <th>SLO</th><th>成本/次</th><th>数据级别</th><th>部署单元</th><th>Schema</th><th>发布记录</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="entry in agents" :key="`${entry.agentId}@${entry.version}`">
                      <td>{{ entry.agentId }}@{{ entry.version }}</td>
                      <td>{{ entry.role }}</td>
                      <td>{{ entry.status }}</td>
                      <td :class="entry.signatureVerified ? 'gov-pass' : 'gov-fail'">
                        {{ entry.signatureVerified ? "PASS" : "FAIL" }}
                      </td>
                      <td>{{ entry.owner }}</td>
                      <td>{{ entry.sloMs }} ms</td>
                      <td>${{ entry.costBudget }}</td>
                      <td>{{ entry.dataClass }}</td>
                      <td>{{ entry.deployUnit }}</td>
                      <td>{{ entry.schemaBytes }}</td>
                      <td><button type="button" class="gov-link" @click="showAgentReleases(entry.agentId)">查看</button></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>

            <section v-if="tab === 'prompts'" class="gov-section" aria-label="提示词治理">
              <h3 class="gov-subtitle">角色当前绑定</h3>
              <table class="gov-table">
                <thead><tr><th>角色</th><th>promptId</th><th>版本</th><th>操作人</th><th>更新时间</th></tr></thead>
                <tbody>
                  <tr v-for="row in promptReleases" :key="row.role">
                    <td>{{ row.role }}</td><td>{{ row.promptId }}</td><td>{{ row.version }}</td>
                    <td>{{ row.actor }}</td><td>{{ stamp(row.updatedAt) }}</td>
                  </tr>
                </tbody>
              </table>

              <h3 class="gov-subtitle">登记版本</h3>
              <table class="gov-table">
                <thead><tr><th>promptId</th><th>版本</th><th>角色</th><th>状态</th><th>Owner</th><th>哈希</th><th>评测记录</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-for="row in prompts" :key="`${row.promptId}@${row.version}`">
                    <td>{{ row.promptId }}</td><td>{{ row.version }}</td><td>{{ row.role }}</td>
                    <td>{{ row.status }}</td><td>{{ row.owner }}</td>
                    <td class="gov-mono">{{ row.hash.slice(0, 16) }}…</td>
                    <td>{{ row.evalRecord }}</td><td>{{ stamp(row.createdAt) }}</td>
                  </tr>
                </tbody>
              </table>

              <div class="gov-forms">
                <form class="gov-form" @submit.prevent="submitPromptRelease">
                  <label>角色
                    <select v-model="promptReleaseForm.role" aria-label="绑定角色">
                      <option value="supervisor">supervisor</option>
                      <option value="weather_agent">weather_agent</option>
                      <option value="train_agent">train_agent</option>
                      <option value="itinerary_agent">itinerary_agent</option>
                    </select>
                  </label>
                  <label>promptId
                    <input v-model="promptReleaseForm.promptId" aria-label="绑定 promptId" />
                  </label>
                  <label>版本
                    <input v-model="promptReleaseForm.version" aria-label="绑定版本号" />
                  </label>
                  <label>操作人
                    <input v-model="promptReleaseForm.actor" aria-label="绑定操作人" />
                  </label>
                  <button type="submit" class="gov-button gov-primary" :disabled="busy">绑定发布</button>
                </form>
                <form class="gov-form gov-form-wide" @submit.prevent="submitPromptCreate">
                  <label>promptId
                    <input v-model="promptCreateForm.promptId" required aria-label="新 promptId" placeholder="prompt.travel.weather_agent" />
                  </label>
                  <label>版本
                    <input v-model="promptCreateForm.version" required aria-label="新版本号" placeholder="2" />
                  </label>
                  <label>角色
                    <select v-model="promptCreateForm.role" aria-label="新提示词角色">
                      <option value="supervisor">supervisor</option>
                      <option value="weather_agent">weather_agent</option>
                      <option value="train_agent">train_agent</option>
                      <option value="itinerary_agent">itinerary_agent</option>
                    </select>
                  </label>
                  <label>评测记录
                    <input v-model="promptCreateForm.evalRecord" required aria-label="评测记录" placeholder="eval-20261102" />
                  </label>
                  <label class="gov-field-full">正文（只提交，不回显）
                    <textarea v-model="promptCreateForm.content" required rows="3" aria-label="提示词正文"></textarea>
                  </label>
                  <button type="submit" class="gov-button" :disabled="busy">登记新版本</button>
                </form>
              </div>
            </section>

            <section v-if="tab === 'memory'" class="gov-section" aria-label="记忆治理">
              <div v-if="memoryGovernance" class="gov-tiles">
                <div><span>会话保留</span><strong>{{ memoryGovernance.conversationRetentionHours }} 小时</strong></div>
                <div><span>偏好保留</span><strong>{{ memoryGovernance.preferenceRetentionHours }} 小时</strong></div>
                <div><span>本地嵌入</span><strong>{{ memoryGovernance.embedding }}</strong></div>
                <div><span>有效向量</span><strong>{{ memoryGovernance.activeVectors }}</strong></div>
                <div><span>待审候选</span><strong>{{ memoryGovernance.pendingCandidates }}</strong></div>
                <div><span>墓碑</span><strong>{{ memoryGovernance.tombstones }}</strong></div>
              </div>
              <dl v-if="memoryGovernance" class="gov-facts">
                <dt>嵌入位置</dt><dd>{{ memoryGovernance.embeddingLocation }}</dd>
                <dt>存储</dt><dd>{{ memoryGovernance.storage }}</dd>
                <dt>跨副本</dt><dd>{{ memoryGovernance.crossReplica }}</dd>
                <dt>数据驻留</dt><dd>{{ memoryGovernance.dataResidency }}</dd>
              </dl>

              <h3 class="gov-subtitle">候选提交与审核</h3>
              <form class="gov-form" @submit.prevent="submitMemoryCandidate">
                <label>偏好键
                  <select v-model="memoryCandidateForm.key" aria-label="候选偏好键">
                    <option value="preferredDepartureCity">preferredDepartureCity</option>
                    <option value="preferredSeat">preferredSeat</option>
                    <option value="replyLanguage">replyLanguage</option>
                  </select>
                </label>
                <label>候选值
                  <input v-model="memoryCandidateForm.value" aria-label="候选偏好值" maxlength="100" />
                </label>
                <label>来源
                  <input v-model="memoryCandidateForm.source" aria-label="候选来源" maxlength="200" />
                </label>
                <label>期望版本
                  <input v-model.number="memoryCandidateForm.expectedVersion" type="number" min="0" aria-label="候选期望版本" />
                </label>
                <label>有效期（可选）
                  <input v-model="memoryCandidateForm.expiresAt" type="datetime-local" aria-label="候选有效期" />
                </label>
                <button type="submit" class="gov-button gov-primary" :disabled="busy">提交候选</button>
              </form>

              <div v-if="memoryCandidates.length" class="gov-release-log">
                <h4>候选记录</h4>
                <table class="gov-table">
                  <thead><tr><th>候选</th><th>键 / 值</th><th>来源</th><th>状态</th><th>版本</th><th>操作</th></tr></thead>
                  <tbody>
                    <tr v-for="row in memoryCandidates" :key="row.candidateId">
                      <td>{{ row.candidateId.slice(0, 8) }}</td>
                      <td>{{ row.key }} / {{ row.value }}</td>
                      <td>{{ row.source }}</td>
                      <td>{{ row.status }}</td>
                      <td>{{ row.appliedVersion ?? row.expectedVersion }}</td>
                      <td>
                        <button v-if="row.status === 'PENDING'" type="button" class="gov-button" :disabled="busy" @click="decideCandidate(row, 'APPROVE')">批准</button>
                        <button v-if="row.status === 'PENDING'" type="button" class="gov-text-button" :disabled="busy" @click="decideCandidate(row, 'REJECT')">拒绝</button>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <h3 class="gov-subtitle">本地向量检索</h3>
              <form class="gov-form" @submit.prevent="searchMemoryVectors">
                <label>检索词
                  <input v-model="memorySearchForm.query" aria-label="记忆检索词" maxlength="200" />
                </label>
                <label>数量
                  <input v-model.number="memorySearchForm.limit" type="number" min="1" max="20" aria-label="记忆检索数量" />
                </label>
                <button type="submit" class="gov-button" :disabled="busy">检索</button>
              </form>
              <div v-if="memoryHits.length" class="gov-release-log">
                <h4>检索结果 · {{ memoryHits[0]?.embedding }}</h4>
                <table class="gov-table">
                  <thead><tr><th>偏好键</th><th>值</th><th>版本</th><th>来源</th><th>分数</th><th>数据分级</th></tr></thead>
                  <tbody>
                    <tr v-for="hit in memoryHits" :key="hit.key">
                      <td>{{ hit.key }}</td><td>{{ hit.value }}</td><td>{{ hit.version }}</td>
                      <td>{{ hit.source }}</td><td>{{ hit.score }}</td><td>{{ hit.dataClass }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-else-if="error" class="gov-muted">检索失败时不展示旧结果。</p>
            </section>

            <section v-if="tab === 'metrics' && metrics" class="gov-section" aria-label="指标">
              <div class="gov-tiles">
                <div><span>任务总数</span><strong>{{ metrics.tasks }}</strong></div>
                <div><span>运行中</span><strong>{{ metrics.running }}</strong></div>
                <div><span>已完成</span><strong>{{ metrics.completed }}</strong></div>
                <div><span>失败率</span><strong>{{ percent(metrics.failureRate) }}</strong></div>
                <div><span>P95 耗时</span><strong>{{ Math.round(metrics.p95DurationMs) }} ms</strong></div>
                <div><span>模型调用</span><strong>{{ amount(metrics.modelCalls) }}</strong></div>
                <div><span>Prompt tokens</span><strong>{{ amount(metrics.promptTokens) }}</strong></div>
                <div><span>Completion tokens</span><strong>{{ amount(metrics.completionTokens) }}</strong></div>
                <div><span>工具调用</span><strong>{{ amount(metrics.toolCalls) }}</strong></div>
                <div><span>失败工具调用</span><strong>{{ amount(metrics.failedToolCalls) }}</strong></div>
                <div><span>限流事件</span><strong>{{ amount(metrics.rateLimitedEvents) }}</strong></div>
                <div><span>队列拒绝</span><strong>{{ amount(metrics.queueRejections) }}</strong></div>
                <div><span>待审批</span><strong>{{ metrics.approvalsPending }}</strong></div>
                <div><span>已决策审批</span><strong>{{ metrics.approvalsDecided }}</strong></div>
              </div>
              <h3 class="gov-subtitle">按状态</h3>
              <ul class="gov-states">
                <li v-for="(count, state) in metrics.byState" :key="state">
                  <span>{{ state }}</span><strong>{{ count }}</strong>
                </li>
              </ul>
              <p class="gov-footnote">采集时间 {{ stamp(metrics.collectedAt) }}；数值来自当前数据库，没有样本时保持 0。</p>
            </section>

            <section v-if="tab === 'alerts'" class="gov-section" aria-label="告警">
              <table class="gov-table">
                <thead><tr><th>规则</th><th>级别</th><th>状态</th><th>实际值</th><th>阈值</th><th>说明</th><th>更新时间</th></tr></thead>
                <tbody>
                  <tr v-for="row in alerts" :key="row.alertId">
                    <td>{{ row.kind }}</td><td>{{ row.severity }}</td>
                    <td :class="row.status === 'OPEN' ? 'gov-fail' : 'gov-pass'">{{ row.status }}</td>
                    <td>{{ row.metricValue }}</td><td>{{ row.thresholdValue }}</td>
                    <td>{{ row.message }}</td><td>{{ stamp(row.updatedAt) }}</td>
                  </tr>
                  <tr v-if="!alerts.length"><td colspan="7" class="gov-muted">还没有采集到告警规则。</td></tr>
                </tbody>
              </table>
              <p class="gov-footnote">告警由真实任务指标和待审批数量触发；规则命中后保留 OPEN，恢复后记为 RESOLVED。</p>
            </section>

            <section v-if="tab === 'approvals'" class="gov-section" aria-label="审批与外部操作">
              <form class="gov-form gov-form-wide" @submit.prevent="lookupApproval">
                <label class="gov-field-full">审批编号
                  <input v-model="approvalLookup.approvalId" required aria-label="审批编号" placeholder="从运行详情或订单回执中获取" />
                </label>
                <button type="submit" class="gov-button gov-primary" :disabled="busy">读取审批</button>
              </form>

              <article v-if="approval" class="gov-card">
                <div class="gov-card-head">
                  <h4>{{ approval.toolName }} · {{ approval.status }}</h4>
                  <span class="gov-badge" :class="{ 'gov-badge-live': approval.status === 'PENDING' }">{{ approval.operationStatus || "未执行" }}</span>
                </div>
                <dl class="gov-dl">
                  <div><dt>审批编号</dt><dd class="gov-mono">{{ approval.approvalId }}</dd></div>
                  <div><dt>运行</dt><dd class="gov-mono">{{ approval.runId }}</dd></div>
                  <div><dt>操作号</dt><dd class="gov-mono">{{ approval.operationId }}</dd></div>
                  <div><dt>参数指纹</dt><dd class="gov-mono">{{ approval.requestHash.slice(0, 24) }}…</dd></div>
                  <div><dt>申请人</dt><dd>{{ approval.requestedBy }}</dd></div>
                  <div><dt>所需权限</dt><dd>{{ approval.requiredPermission }}</dd></div>
                  <div><dt>申请时间</dt><dd>{{ stamp(approval.requestedAt) }}</dd></div>
                  <div><dt>过期时间</dt><dd>{{ stamp(approval.expiresAt) }}</dd></div>
                  <div><dt>决策人</dt><dd>{{ approval.decidedBy || "—" }}</dd></div>
                  <div><dt>决策时间</dt><dd>{{ stamp(approval.decidedAt) }}</dd></div>
                  <div><dt>原因</dt><dd>{{ approval.reason || "—" }}</dd></div>
                </dl>
                <p v-if="approval.operationResponse" class="gov-response">{{ approval.operationResponse }}</p>
                <form v-if="approval.status === 'PENDING'" class="gov-decide" @submit.prevent="submitDecision('APPROVE')">
                  <label class="gov-field-full">审批人令牌（需 approve_book_ticket；只保存在本面板内存）
                    <input v-model="approverToken" type="password" autocomplete="off" spellcheck="false" aria-label="审批人令牌" placeholder="输入审批人访问令牌" />
                  </label>
                  <label class="gov-field-full">决策原因
                    <input v-model="decision.reason" aria-label="审批决策原因" placeholder="核对车次、乘客后批准" />
                  </label>
                  <div class="gov-decide-actions">
                    <button type="submit" class="gov-button gov-primary" :disabled="busy">批准并执行</button>
                    <button type="button" class="gov-button gov-destructive" :disabled="busy" @click="submitDecision('REJECT')">拒绝</button>
                  </div>
                </form>
              </article>

              <h3 class="gov-subtitle">账本查询</h3>
              <form class="gov-form gov-form-wide" @submit.prevent="lookupOperation">
                <label>工具
                  <input v-model="operationLookup.tool" aria-label="账本工具" />
                </label>
                <label class="gov-field-grow">操作号
                  <input v-model="operationLookup.operationId" required aria-label="账本操作号" placeholder="book:…" />
                </label>
                <button type="submit" class="gov-button" :disabled="busy">查询账本</button>
              </form>
              <article v-if="operation" class="gov-card">
                <div class="gov-card-head">
                  <h4>{{ operation.tool }} · {{ operation.status }}</h4>
                  <span class="gov-badge">{{ operation.submitAttempts }} 次提交 / {{ operation.lookupAttempts }} 次查询</span>
                </div>
                <dl class="gov-dl">
                  <div><dt>操作号</dt><dd class="gov-mono">{{ operation.operationId }}</dd></div>
                  <div><dt>参数指纹</dt><dd class="gov-mono">{{ operation.parameterHash.slice(0, 24) }}…</dd></div>
                </dl>
                <p v-if="operation.response" class="gov-response">{{ operation.response }}</p>
              </article>
            </section>

            <section v-if="tab === 'audit'" class="gov-section" aria-label="注册表审计">
              <table class="gov-table">
                <thead><tr><th>#</th><th>动作</th><th>对象</th><th>结论</th><th>详情</th><th>操作人</th><th>时间</th></tr></thead>
                <tbody>
                  <tr v-for="row in audit" :key="row.auditId">
                    <td>{{ row.auditId }}</td><td>{{ row.action }}</td><td>{{ row.subject }}</td>
                    <td :class="row.outcome === 'PASS' ? 'gov-pass' : 'gov-fail'">{{ row.outcome }}</td>
                    <td>{{ row.detail }}</td><td>{{ row.actor }}</td><td>{{ stamp(row.createdAt) }}</td>
                  </tr>
                </tbody>
              </table>
            </section>
          </template>
        </div>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.gov-panel { position:fixed; inset:0; margin:auto; border:0; padding:0; width:min(1120px,calc(100vw - 64px)); max-width:none; height:min(820px,calc(100dvh - 48px)); max-height:none; border-radius:18px; background:#fafbf9; color:#202725; font-family:inherit; font-size:14px; line-height:1.6; box-shadow:0 24px 80px #10231b40; }
.gov-panel::backdrop { background:#1328204a; backdrop-filter:blur(2px); }
.gov-shell { display:flex; flex-direction:column; height:100%; }
.gov-header { display:flex; justify-content:space-between; align-items:center; gap:16px; padding:22px 26px 18px; border-bottom:1px solid #e6eae7; background:#fff; border-radius:18px 18px 0 0; }
.gov-heading { display:flex; align-items:center; gap:13px; }
.gov-mark { display:grid; place-items:center; width:44px; height:44px; border:1px solid #dce7e0; border-radius:14px; color:#087e68; background:#f2f7f3; }
.gov-eyebrow { margin:0 0 2px; font-size:11px; color:#68716c; letter-spacing:.04em; }
.gov-heading h2 { margin:0; font-size:22px; line-height:1.3; letter-spacing:-.03em; font-weight:650; }
.gov-header-actions { display:flex; gap:8px; }
.gov-icon-button { border:0; display:grid; place-items:center; width:38px; height:38px; border-radius:50%; color:#68716c; background:#f4f6f3; cursor:pointer; }
.gov-icon-button:hover { background:#e9eeea; }
.gov-tabs { display:flex; gap:4px; padding:0 22px; background:#fff; border-bottom:1px solid #e6eae7; overflow-x:auto; }
.gov-tabs button { position:relative; border:0; background:transparent; padding:12px 14px; font:inherit; font-size:13px; color:#68716c; cursor:pointer; white-space:nowrap; }
.gov-tabs button.active { color:#087e68; font-weight:600; }
.gov-tabs button.active::after { content:""; position:absolute; left:12px; right:12px; bottom:-1px; height:2px; background:#087e68; }
.gov-tab-count { display:inline-block; min-width:18px; margin-left:5px; padding:0 5px; border-radius:9px; background:#eef3ef; color:#35624d; font-size:10px; line-height:18px; text-align:center; }
.gov-body { flex:1; overflow:auto; padding:22px 26px 30px; }
.gov-description { margin:0 0 18px; color:#68716c; font-size:12px; }
.gov-feedback { font-size:12px; padding:10px 13px; margin:0 0 15px; border-radius:9px; }
.gov-error { background:#fff0ec; color:#9b3e2b; }
.gov-success { background:#eaf4ee; color:#35624d; }
.gov-loading { display:flex; align-items:center; gap:9px; color:#68716c; font-size:12px; }
.gov-spinner { width:15px; height:15px; border:2px solid #dce6dc; border-top-color:#087e68; border-radius:50%; animation:gov-spin 1s linear infinite; }
@keyframes gov-spin { to { transform:rotate(360deg); } }
.gov-empty { text-align:center; padding:60px 18px; color:#68716c; }
.gov-empty h3 { color:#202725; margin:14px 0 6px; font-size:17px; }
.gov-empty-icon { display:inline-grid; place-items:center; width:54px; height:54px; background:#edf3ed; border-radius:50%; color:#087e68; }
.gov-tiles { display:grid; grid-template-columns:repeat(auto-fit,minmax(130px,1fr)); gap:10px; margin:0 0 16px; }
.gov-tiles div { background:#fff; border:1px solid #e6eae7; border-radius:12px; padding:12px 14px; }
.gov-tiles span { display:block; color:#68716c; font-size:11px; }
.gov-tiles strong { display:block; margin-top:3px; font-size:19px; font-weight:650; letter-spacing:-.02em; }
.gov-tiles div.alert { border-color:#e7bfb4; background:#fff7f4; }
.gov-budget-warning { margin:0 0 12px; padding:10px 13px; border-radius:9px; background:#fff4e6; color:#8a5518; font-size:12px; }
.gov-events { margin:0 0 18px; padding:10px 13px 10px 30px; background:#f4f6f3; border-radius:9px; color:#4c5952; font-size:11px; }
.gov-subtitle { font-size:14px; font-weight:600; margin:22px 0 10px; }
.gov-table-wrap { overflow-x:auto; }
.gov-table { width:100%; border-collapse:collapse; background:#fff; border:1px solid #e6eae7; border-radius:10px; overflow:hidden; font-size:11.5px; }
.gov-table th, .gov-table td { padding:8px 10px; text-align:left; border-bottom:1px solid #eef1ee; vertical-align:top; }
.gov-table th { background:#f6f8f5; color:#5d6962; font-weight:600; white-space:nowrap; }
.gov-table tr:last-child td { border-bottom:0; }
.gov-table td { overflow-wrap:anywhere; }
.gov-mono { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:10.5px; }
.gov-pass { color:#2f6b4f; font-weight:600; }
.gov-fail { color:#a34031; font-weight:600; }
.gov-muted { color:#68716c; }
.gov-forms { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:12px; margin:12px 0 8px; }
.gov-form { display:grid; grid-template-columns:repeat(auto-fit,minmax(120px,1fr)); gap:8px; align-items:end; background:#fff; border:1px solid #e6eae7; border-radius:12px; padding:14px; }
.gov-form label { display:flex; flex-direction:column; gap:4px; font-size:11px; color:#5d6962; }
.gov-form input, .gov-form select, .gov-form textarea { min-height:34px; box-sizing:border-box; padding:6px 9px; border:1px solid #dce2dc; border-radius:7px; background:#fff; color:#202725; font:inherit; font-size:12px; }
.gov-form textarea { resize:vertical; min-height:64px; }
.gov-field-full { grid-column:1 / -1; }
.gov-field-grow { flex:1; }
.gov-form-wide { grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); }
.gov-button { display:inline-flex; align-items:center; justify-content:center; min-height:36px; padding:7px 14px; border:1px solid #dce2dd; border-radius:8px; background:#fff; color:#47554b; font:inherit; font-size:12px; cursor:pointer; }
.gov-button:hover { background:#f0f4ef; }
.gov-button:disabled { opacity:.5; cursor:default; }
.gov-primary { background:#273c31; border-color:#273c31; color:#fff; }
.gov-primary:hover { background:#365443; }
.gov-destructive { background:#a34031; border-color:#a34031; color:#fff; }
.gov-destructive:hover { background:#8d3225; }
.gov-link { border:0; background:transparent; color:#087e68; font:inherit; font-size:11px; cursor:pointer; padding:0; }
.gov-link:hover { text-decoration:underline; }
.gov-release-log h4 { margin:16px 0 8px; font-size:12px; }
.gov-card { margin:14px 0 0; background:#fff; border:1px solid #e6eae7; border-radius:12px; padding:16px; }
.gov-card-head { display:flex; justify-content:space-between; align-items:center; gap:10px; margin-bottom:10px; }
.gov-card-head h4 { margin:0; font-size:14px; }
.gov-badge { display:inline-block; padding:2px 8px; border-radius:20px; background:#f2f4f1; color:#68716c; font-size:10px; }
.gov-badge-live { background:#fff4e6; color:#8a5518; }
.gov-dl { display:grid; grid-template-columns:repeat(auto-fit,minmax(240px,1fr)); gap:6px 18px; margin:0 0 10px; font-size:11px; }
.gov-dl div { display:flex; gap:7px; min-width:0; }
.gov-dl dt { color:#68716c; white-space:nowrap; }
.gov-dl dd { margin:0; overflow-wrap:anywhere; }
.gov-response { margin:10px 0 0; padding:10px; border-radius:8px; background:#f6f8f5; color:#4c5952; font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:10.5px; white-space:pre-wrap; overflow-wrap:anywhere; }
.gov-decide { margin-top:12px; display:grid; grid-template-columns:1fr; gap:8px; }
.gov-decide-actions { display:flex; gap:8px; justify-content:flex-end; }
.gov-states { list-style:none; margin:0; padding:0; display:flex; flex-wrap:wrap; gap:8px; }
.gov-states li { display:flex; gap:8px; align-items:center; background:#fff; border:1px solid #e6eae7; border-radius:9px; padding:7px 11px; font-size:11px; color:#5d6962; }
.gov-states strong { color:#202725; }
.gov-footnote { color:#68716c; font-size:11px; margin:14px 0 0; }
@media (max-width:700px) { .gov-panel { width:100vw; height:100dvh; border-radius:0; } .gov-header { border-radius:0; padding:18px; } .gov-body { padding:16px 14px 24px; } .gov-form { grid-template-columns:1fr 1fr; } .gov-form input, .gov-form select, .gov-form textarea { font-size:16px; } }
@media (prefers-reduced-motion:reduce) { .gov-spinner { animation:none; } }
</style>
