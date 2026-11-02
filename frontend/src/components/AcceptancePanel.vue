<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { Turn } from "../types";
import {
  authenticated,
  credentialEpoch,
  diagnosticTraces,
  loadConfigEvidence,
  loadRunDetail,
  loadRunSnapshot,
} from "../store";
import {
  acceptanceCases, acceptanceCategories, acceptanceStatuses, acceptanceProcedures,
  acceptanceRecords, acceptanceMemory, persistAcceptanceIdentifiers, safeAcceptanceCopy,
  redactAcceptanceText, type AcceptanceSnapshot, type AcceptanceStatus, type AcceptanceRecord,
} from "../acceptance";
import type { ConfigEvidence, RunDetail } from "../types";
import Icon from "./Icon.vue";
import { labels } from "../presentation";
import AcceptanceSnapshotDetails from "./AcceptanceSnapshotDetails.vue";
import AcceptanceHttpTraces from "./AcceptanceHttpTraces.vue";

const props = defineProps<{ activeTurn?: Turn }>();
const emit = defineEmits<{ close: []; prepare: [id: string, question: string] }>();
const dialog = ref<HTMLDialogElement>();
const detail = ref<HTMLElement>();
const search = ref("");
const category = ref("");
const statusFilter = ref<AcceptanceStatus | "">("");
const selected = computed(() => acceptanceCases.find((item) => item.id === acceptanceMemory.selectedId) ?? acceptanceCases[0]!);
const record = computed(() => acceptanceRecords[selected.value.id]!);
const draftStatus = ref<AcceptanceStatus>(record.value.status);
const question = ref(selected.value.question);
const relatedRunId = ref("");
const feedback = ref("");
const selectedProcedures = computed(() => acceptanceProcedures.filter((item) => selected.value.procedures.includes(item.id)));
const filtered = computed(() => {
  const needle = search.value.trim().toLocaleLowerCase();
  return acceptanceCases.filter((item) => (!category.value || item.category === category.value) &&
    (!statusFilter.value || acceptanceRecords[item.id]?.status === statusFilter.value) &&
    (!needle || [item.id, item.title, item.operation, item.prerequisites, item.evidence, item.expected].join(" ").toLocaleLowerCase().includes(needle)));
});
const counts = computed(() => acceptanceStatuses.map((status) => ({ status, count: acceptanceCases.filter((item) => acceptanceRecords[item.id]?.status === status).length })));
const sampleCount = computed(() => Object.values(acceptanceRecords).reduce((total, item) => total + item.snapshots.length, 0));
let previousFocus: HTMLElement | null = null;

function selectCase(id: string) {
  acceptanceMemory.selectedId = id;
  persistAcceptanceIdentifiers();
}
watch(selected, (item) => {
  draftStatus.value = record.value.status;
  question.value = item.question;
  feedback.value = "";
  void nextTick(() => detail.value?.scrollTo({ top: 0 }));
});
watch(credentialEpoch, () => {
  draftStatus.value = "未执行";
  question.value = selected.value.question;
  feedback.value = "认证已变化，本面板中的旧身份记录已清空。";
}, { flush: "sync" });
function prepare() {
  if (question.value.length > 2000) {
    feedback.value = "草稿不能超过2000个UTF-16单元，请缩短后再准备。";
    return;
  }
  emit("prepare", selected.value.id, question.value);
}
/** 读取运行详情与配置证据；任一接口失败都不阻止快照落盘，但要在反馈中说明缺项。 */
async function loadEvidence(runId: string): Promise<{ detail?: RunDetail; config?: ConfigEvidence; warning: string }> {
  let warning = "";
  const [detail, config] = await Promise.all([
    loadRunDetail(runId).catch((error: unknown) => {
      warning = `运行详情未读取：${error instanceof Error ? error.message : "未知错误"}`;
      return undefined;
    }),
    loadConfigEvidence().catch((error: unknown) => {
      warning += `${warning ? "；" : ""}配置证据未读取：${error instanceof Error ? error.message : "未知错误"}`;
      return undefined;
    }),
  ]);
  return {
    detail: detail ? safeAcceptanceCopy(detail) : undefined,
    config: config ? safeAcceptanceCopy(config) : undefined,
    warning,
  };
}

/** 把快照写入当前用例；重复关联保留原快照，不覆盖历史证据。 */
function appendSnapshot(snapshot: AcceptanceSnapshot, warning: string) {
  record.value.snapshots.push(snapshot);
  record.value.reviewedEvidence = false;
  record.value.allBranchesReviewed = false;
  if (record.value.status === "未执行") {
    record.value.status = "执行中";
    draftStatus.value = "执行中";
  }
  persistAcceptanceIdentifiers();
  feedback.value = `已关联副本 ${snapshot.id}。副本保留此刻数据，后续请再次关联；不会自动给出验收结论。${warning ? `（${warning}）` : ""}`;
}

async function capture() {
  const turn = props.activeTurn;
  if (!authenticated.value || !turn || (!turn.traces.length && !turn.runId && !turn.result)) {
    feedback.value = "请先以当前身份执行真实请求，再关联当前任务。";
    return;
  }
  const evidence = turn.runId
    ? await loadEvidence(turn.runId)
    : { warning: "当前任务没有 runId，无法读取运行详情。" };
  const snapshot = safeAcceptanceCopy({
    id: `S-${crypto.randomUUID().slice(0, 8)}`, capturedAt: new Date().toISOString(),
    payload: turn.payload, runId: turn.runId, phase: turn.phase, result: turn.result,
    traces: turn.traces, inputs: turn.inputs, probes: turn.probes ?? [], lastInstruction: turn.lastInstruction,
    detail: evidence.detail, config: evidence.config,
  });
  appendSnapshot(snapshot, evidence.warning);
}

/** 按服务端返回的任务编号补录任意历史任务，不再要求任务仍在 activeTurn 中。 */
async function captureByRunId() {
  const runId = relatedRunId.value.trim();
  if (!authenticated.value) {
    feedback.value = "请先配置认证令牌。";
    return;
  }
  if (!/^[A-Za-z0-9-]{8,64}$/.test(runId)) {
    feedback.value = "请输入服务端返回的任务编号。";
    return;
  }
  const traceStart = diagnosticTraces.value.length;
  try {
    const result = await loadRunSnapshot(runId);
    const evidence = await loadEvidence(runId);
    const detail = evidence.detail;
    const snapshot = safeAcceptanceCopy<AcceptanceSnapshot>({
      id: `S-${crypto.randomUUID().slice(0, 8)}`, capturedAt: new Date().toISOString(),
      payload: {
        sessionId: detail?.sessionId ?? "",
        question: result.originalQuery ?? "",
        history: [],
        requestId: detail?.requestId ?? result.requestId,
      },
      runId, phase: "restore", result,
      traces: diagnosticTraces.value.slice(traceStart), inputs: [], probes: [],
      detail, config: evidence.config,
    });
    appendSnapshot(snapshot, evidence.warning);
  } catch (error) {
    feedback.value = `任务 ${runId} 读取失败：${error instanceof Error ? error.message : "未知错误"}`;
  }
}
function captureDiagnostic() {
  record.value.diagnosticTraces = safeAcceptanceCopy(diagnosticTraces.value);
  feedback.value = "已记录当前身份的独立接口HTTP追踪。HTTP记录不能单独支持通过结论。";
}
function addEvidence() {
  record.value.manualEvidence.push({ id: `M-${crypto.randomUUID().slice(0, 8)}`, source: "", observation: "" });
}
function addBranch() {
  record.value.branches.push({ id: crypto.randomUUID(), label: "", status: "未执行", conclusion: "", evidenceIds: "" });
}
function concrete(value: string) {
  return value.replace(/HTTP\s*\d{3}|\b200\b|通过|成功|正常|已验证|\s|[，。；,:：.!]/gi, "").length >= 8;
}
function passError(item: AcceptanceRecord): string {
  if (!concrete(item.conclusion)) { return "请填写具体结论与实际观察，不能只填HTTP 200或“通过”。"; }
  const sources = new Set<string>();
  for (const snapshot of item.snapshots) {
    if (snapshot.result && (snapshot.result.evidence.length > 0 || snapshot.result.events.length > 0)) {
      sources.add(snapshot.id);
    }
  }
  for (const evidence of item.manualEvidence) {
    if (evidence.source.trim() && concrete(evidence.observation)) { sources.add(evidence.id); }
  }
  if (!sources.size) { return "通过必须有真实任务证据/事件，或带来源与具体观察的人工证据。只有HTTP状态不够。"; }
  if (!item.branches.length) { return "请添加至少一个实际验证分支；多分支问题须逐个记录。"; }
  for (const branch of item.branches) {
    const refs = branch.evidenceIds.split(/[\s,，;；]+/).filter(Boolean);
    if (!branch.label.trim() || branch.status !== "通过" || !concrete(branch.conclusion) || !refs.some((id) => sources.has(id))) {
      return "每个分支都需名称、具体结论、有效证据编号并通过；有未触发或缺口请选部分验证或阻塞。";
    }
  }
  if (!item.allBranchesReviewed || !item.reviewedEvidence) { return "请人工核对全部预期分支及证据。未触发分支不能当作通过。"; }
  return "";
}
function save() {
  const item = record.value;
  if (draftStatus.value !== "未执行" && !item.conclusion.trim()) {
    feedback.value = "请先填写结论；执行中也需要注明正在验证的分支。";
    return;
  }
  if (draftStatus.value === "通过") {
    const reason = passError(item);
    if (reason) { feedback.value = reason; return; }
  }
  if (item.manualEvidence.some((entry) => !entry.source.trim() || !entry.observation.trim())) {
    feedback.value = "请补全人工证据的来源和实际观察，或移除空白项。";
    return;
  }
  const clean = safeAcceptanceCopy(item);
  clean.status = draftStatus.value;
  clean.updatedAt = new Date().toISOString();
  acceptanceRecords[selected.value.id] = clean;
  feedback.value = "记录已保存到当前页面内存。需要留存时请导出本地文件；刷新与身份变化会清空正文。";
  persistAcceptanceIdentifiers();
}
watch(record, (item) => {
  if (item.status === "通过" && passError(item)) {
    item.status = "部分验证";
    draftStatus.value = "部分验证";
  }
}, { deep: true });
function removeSnapshot(id: string) {
  record.value.snapshots = record.value.snapshots.filter((item) => item.id !== id);
  record.value.reviewedEvidence = false;
  record.value.allBranchesReviewed = false;
  persistAcceptanceIdentifiers();
}
function exportRecords(format: "json" | "md") {
  const now = new Date().toISOString();
  const records = acceptanceCases.map((item) => {
    const saved = safeAcceptanceCopy(acceptanceRecords[item.id]!);
    if (saved.status === "通过" && passError(saved)) { saved.status = "部分验证"; }
    return { ...item, record: saved };
  });
  const summary = acceptanceStatuses.map((status) => ({ status, count: records.filter((item) => item.record.status === status).length }));
  const report = safeAcceptanceCopy({
    version: "v1.2", exportedAt: now, source: "docs/验收用例.md", total: 43,
    notice: "真实页面验收人工记录。状态不代表后端状态；HTTP成功不等于机制通过。Console由验收人员独立检查。正文仅在内存及用户导出的本地文件。", summary, snapshotCount: sampleCount.value,
    cases: records, procedures: acceptanceProcedures,
  });
  let content: string;
  if (format === "json") {
    content = JSON.stringify(report, null, 2);
  } else {
    const lines = ["# Supervisor 页面验收记录 v1.2", "", `导出时间：${now}`, "", report.notice, "", `43项；关联快照 ${report.snapshotCount} 份。`, "", ...summary.map((item) => `- ${item.status}：${item.count}`)];
    for (const item of report.cases) {
      lines.push("", `## ${item.id} ${item.title}`, "", `状态：${item.record.status}`, "", `前提：${item.prerequisites}`, "", `操作：${item.operation}`, "", `预期：${item.expected}`, "", `必要证据：${item.evidence}`, "", "实际记录（完整保留请求字段、任务快照、分支与人工记录）：", "", "```json", JSON.stringify(item.record, null, 2).replace(/`/g, "\\u0060"), "```");
    }
    lines.push("", "## 公共操作步骤", "");
    for (const procedure of acceptanceProcedures) { lines.push(`### ${procedure.id} ${procedure.title}`, "", procedure.content, ""); }
    content = lines.join("\n");
  }
  const blob = new Blob([redactAcceptanceText(content)], { type: format === "json" ? "application/json;charset=utf-8" : "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `supervisor-acceptance-${now.slice(0, 19).replace(/:/g, "-")}.${format}`;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  feedback.value = "已生成本地导出文件。未执行与未触发项保留原状态。";
}
function close() { emit("close"); }
function cancel(event: Event) { event.preventDefault(); close(); }
onMounted(async () => {
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  await nextTick();
  dialog.value?.showModal();
  dialog.value?.querySelector<HTMLButtonElement>(".acceptance-close")?.focus();
});
onBeforeUnmount(() => { dialog.value?.close(); previousFocus?.focus(); });
</script>

<template>
  <dialog ref="dialog" class="acceptance-dialog" aria-labelledby="acceptance-title" @cancel="cancel" @click="($event.target === dialog) && close()">
    <div class="acceptance-shell">
      <header class="acceptance-header">
        <div class="acceptance-heading"><span class="acceptance-mark"><Icon name="clipboard" :size="22" /></span><div><h2 id="acceptance-title">验收场景</h2><p>43 项问题 · 真实操作与证据记录</p></div></div>
        <div class="acceptance-header-actions"><button class="acceptance-quiet" @click="exportRecords('json')"><Icon name="download" :size="16" /> JSON</button><button class="acceptance-quiet" @click="exportRecords('md')">Markdown</button><button class="acceptance-close acceptance-icon" aria-label="关闭验收场景" @click="close"><Icon name="close" /></button></div>
      </header>
      <div class="acceptance-summary" aria-label="验收记录汇总"><span v-for="count in counts" :key="count.status"><i :class="['acceptance-dot', { 'is-pass': count.status === '通过', 'is-fail': count.status === '失败' }]" />{{ count.status }} <strong>{{ count.count }}</strong></span><small>快照 {{ sampleCount }} 份 · 当前页面样本</small></div>
      <p v-if="acceptanceMemory.notice" class="acceptance-notice" role="status">{{ acceptanceMemory.notice }}</p>
      <div class="acceptance-body">
        <aside class="acceptance-sidebar" aria-label="场景目录">
          <label class="acceptance-search"><Icon name="clipboard" :size="17" /><input v-model="search" type="search" placeholder="搜索编号、问题或关键词" aria-label="搜索验收场景" /></label>
          <div class="acceptance-filters"><select v-model="category" aria-label="分类"><option value="">全部分类</option><option v-for="group in acceptanceCategories" :key="group.id" :value="group.id">{{ group.id }} · {{ group.title }}</option></select><select v-model="statusFilter" aria-label="筛选验收状态"><option value="">全部状态</option><option v-for="status in acceptanceStatuses" :key="status">{{ status }}</option></select></div>
          <p class="acceptance-list-count">{{ filtered.length }} / 43 项</p>
          <nav class="acceptance-case-list"><button v-for="item in filtered" :key="item.id" :aria-current="selected.id === item.id ? 'true' : undefined" :class="['acceptance-case', { 'is-selected': selected.id === item.id }]" @click="selectCase(item.id)"><span class="acceptance-case-top"><b>{{ item.id }}</b><small>{{ acceptanceRecords[item.id]?.status }}</small></span><span>{{ item.title }}</span></button><p v-if="!filtered.length" class="acceptance-empty">没有匹配场景，请调整搜索或筛选条件。</p></nav>
        </aside>
        <main ref="detail" class="acceptance-detail" :aria-label="`${selected.id} 场景详情`">
          <div class="acceptance-detail-title"><span class="acceptance-id">{{ selected.id }}</span><span class="acceptance-status">{{ record.status }}</span><h3>{{ selected.title }}</h3><p>选择只显示说明；准备会话后，由你确认并发送每一步真实请求。</p></div>
          <section class="acceptance-section"><h4>前提与观察边界</h4><p class="acceptance-boundary">{{ selected.prerequisites }}</p><h4>操作步骤</h4><p>{{ selected.operation }}</p><details v-for="procedure in selectedProcedures" :key="procedure.id" class="acceptance-procedure"><summary>{{ procedure.id }} · {{ procedure.title }}</summary><p>{{ procedure.content }}</p></details><h4>预期与通过判据</h4><p>{{ selected.expected }}</p><h4>必要证据</h4><p>{{ selected.evidence }}</p></section>
          <section class="acceptance-section"><h4>准备第一步</h4><label class="acceptance-field"><span>待发送草稿</span><textarea v-model="question" rows="3" :placeholder="selected.question ? '' : '本项以接口、源码或部署记录核对为主；可留空创建验收会话。'" /></label><div class="acceptance-action-row"><button class="acceptance-primary" @click="prepare"><Icon name="plus" :size="17" /> 新建会话并填入草稿</button><small>不会自动发送，不会批量执行后续步骤</small></div></section>
          <section class="acceptance-section"><div class="acceptance-section-heading"><h4>关联真实证据 <small>{{ record.snapshots.length }} 份任务快照</small></h4><button class="acceptance-secondary" :disabled="!authenticated || !activeTurn" @click="capture">关联当前任务</button></div><p class="acceptance-help">每次关联保存当前任务的独立副本，包括请求、版本、期限、回答、证据、事件及HTTP。后续变化请再次关联。</p><p v-if="!activeTurn" class="acceptance-help">当前没有可关联任务。先在会话中执行真实请求，再打开本面板。</p><p v-if="record.recoveredIds.length" class="acceptance-help">已恢复 {{ record.recoveredIds.length }} 个关联标识，正文未恢复。请以当前身份重新查询任务后关联，不沿用旧结果。</p>
            <details v-for="snapshot in record.snapshots" :key="snapshot.id" class="acceptance-snapshot"><summary><strong>{{ snapshot.id }}</strong> · {{ labels[snapshot.result?.status || ''] || '已关联任务' }}<span>{{ snapshot.capturedAt }}</span></summary><AcceptanceSnapshotDetails :snapshot="snapshot" /><button class="acceptance-text-button" @click="removeSnapshot(snapshot.id)">移除此副本</button></details>
            <div class="acceptance-inline-field"><label for="acceptance-run-id">按任务编号补录</label><input id="acceptance-run-id" v-model="relatedRunId" type="text" maxlength="64" placeholder="粘贴服务端返回的 runId" /><button class="acceptance-secondary" :disabled="!authenticated || !relatedRunId.trim()" @click="captureByRunId">关联指定任务</button></div><p class="acceptance-help">用于关联历史 turn、子运行或已不在当前会话中的任务；读取当前身份可见的任务快照、运行详情与脱敏配置。</p>
            <div class="acceptance-action-row"><button class="acceptance-secondary" :disabled="!diagnosticTraces.length" @click="captureDiagnostic">记录独立接口HTTP（{{ diagnosticTraces.length }}）</button><button class="acceptance-secondary" @click="addEvidence">添加人工证据</button></div><details v-if="record.diagnosticTraces.length" class="acceptance-snapshot"><summary>已记录独立接口追踪 {{ record.diagnosticTraces.length }} 条</summary><AcceptanceHttpTraces :traces="record.diagnosticTraces" /></details>
            <div v-for="(evidence, index) in record.manualEvidence" :key="evidence.id" class="acceptance-editor-card"><div class="acceptance-card-heading"><b>{{ evidence.id }} · 人工核对</b><button class="acceptance-text-button" @click="record.manualEvidence.splice(index, 1)">移除</button></div><label class="acceptance-field"><span>来源 / 截图或日志位置</span><input v-model="evidence.source" placeholder="真实文件与行号、截图路径、检查时间或请求标识" /></label><label class="acceptance-field"><span>实际观察</span><textarea v-model="evidence.observation" rows="3" placeholder="写出实际字段、行为与预期差异；不要只填HTTP 200" /></label></div>
          </section>
          <section class="acceptance-section"><div class="acceptance-section-heading"><h4>分支与样本</h4><button class="acceptance-secondary" @click="addBranch">添加验证分支</button></div><p class="acceptance-help">P1三种动作、不同身份、故障类型和开关对照分别记录。未触发可选部分验证，缺少能力或观测可选阻塞。</p><div v-for="(branch, index) in record.branches" :key="branch.id" class="acceptance-editor-card"><div class="acceptance-card-heading"><b>分支 {{ index + 1 }}</b><button class="acceptance-text-button" @click="record.branches.splice(index, 1)">移除</button></div><div class="acceptance-grid"><label class="acceptance-field"><span>分支 / 样本名称</span><input v-model="branch.label" placeholder="例如：MESSAGE · 运行中同号重放" /></label><label class="acceptance-field"><span>实际状态</span><select v-model="branch.status"><option v-for="status in acceptanceStatuses" :key="status">{{ status }}</option></select></label></div><label class="acceptance-field"><span>实际结论 / 未触发原因</span><textarea v-model="branch.conclusion" rows="2" /></label><label class="acceptance-field"><span>证据编号</span><input v-model="branch.evidenceIds" placeholder="关联上方S-或M-编号，以逗号分隔" /></label></div></section>
          <section class="acceptance-section"><h4>人工执行记录</h4><p class="acceptance-help">仅记录身份别名；不要输入真实令牌或密钥。Console由实际检查人员记录，页面HTTP追踪不能代替Console。</p><div class="acceptance-grid"><label class="acceptance-field"><span>实例与真实模式依据</span><input v-model="record.instance" placeholder="实例地址、进程/启动记录位置" /></label><label class="acceptance-field"><span>执行人 / 身份别名</span><input v-model="record.operator" placeholder="例如：验收人员 / 天气用户B" /></label></div><label class="acceptance-field"><span>实际配置、开关与预算</span><textarea v-model="record.configuration" rows="2" placeholder="按实际环境记录，不填写凭据" /></label><label class="acceptance-field"><span>实际操作、输入与时序</span><textarea v-model="record.steps" rows="3" /></label><label class="acceptance-field"><span>Console检查</span><textarea v-model="record.console" rows="2" placeholder="写明检查时间、真实报错/无报错及截图；未检查请如实记录" /></label><label class="acceptance-field"><span>结论与预期/实际差异（必填）</span><textarea v-model="record.conclusion" rows="3" placeholder="哪些分支已证实，哪些未触发；对应什么实际证据" /></label><label class="acceptance-field"><span>缺陷根因与修改文件</span><textarea v-model="record.defect" rows="2" /></label><label class="acceptance-field"><span>原场景与相邻场景复测</span><textarea v-model="record.retest" rows="2" placeholder="复测步骤、实际结果、快照编号；没有复测请写明" /></label><label class="acceptance-field"><span>截图 / 日志位置</span><input v-model="record.artifact" /></label><label class="acceptance-checkbox"><input v-model="record.reviewedEvidence" type="checkbox" />我已人工核对证据来源、实际回答和过程，未以HTTP 200或模型自述替代验证</label><label class="acceptance-checkbox"><input v-model="record.allBranchesReviewed" type="checkbox" />本项全部目标与分支均已真实触发并有证据；有缺口时不勾选</label><div class="acceptance-save-row"><label class="acceptance-field"><span>前端验收状态</span><select v-model="draftStatus"><option v-for="status in acceptanceStatuses" :key="status">{{ status }}</option></select></label><button class="acceptance-primary" @click="save"><Icon name="check" :size="17" />保存本项记录</button></div><p v-if="record.updatedAt" class="acceptance-help">最近保存 {{ new Date(record.updatedAt).toLocaleString() }} · 正文仅在内存</p></section>
          <div class="acceptance-feedback" role="status" aria-live="polite">{{ feedback || '当前记录仅在页面内存。刷新或更换凭据会清空正文；需要保留请主动导出。' }}</div>
        </main>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.acceptance-dialog{width:min(1180px,calc(100vw - 48px));height:min(900px,calc(100dvh - 48px));max-width:none;max-height:none;padding:0;border:1px solid #dedfdd;border-radius:18px;color:#242b29;background:#fff;box-shadow:0 24px 100px #15251d38;font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC",sans-serif;overflow:hidden}
.acceptance-dialog::backdrop{background:#12201961;backdrop-filter:blur(3px)}
.acceptance-shell{height:100%;display:flex;flex-direction:column}.acceptance-header{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:22px 26px;border-bottom:1px solid #e8eae7}.acceptance-heading{display:flex;align-items:center;gap:12px}.acceptance-heading h2{font-size:19px;margin:0;letter-spacing:.02em}.acceptance-heading p{color:#7a827d;font-size:12px;margin:2px 0 0}.acceptance-mark{display:grid;place-items:center;width:42px;height:42px;background:#edf5f0;color:#397259;border-radius:12px}.acceptance-header-actions,.acceptance-action-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.acceptance-summary{display:flex;align-items:center;gap:19px;flex-wrap:wrap;padding:12px 26px;background:#fafbf9;border-bottom:1px solid #e8eae7;font-size:12px;color:#68736c}.acceptance-summary span{display:flex;align-items:center;gap:6px}.acceptance-summary strong{font-size:13px;color:#25352b}.acceptance-summary small{margin-left:auto;color:#849087}.acceptance-dot{width:6px;height:6px;background:#a7b0a9;border-radius:50%}.acceptance-dot.is-pass{background:#3b8a64}.acceptance-dot.is-fail{background:#be6557}.acceptance-notice{margin:0;padding:8px 26px;color:#5e6e5f;background:#f0f5ed;font-size:12px}.acceptance-body{display:grid;grid-template-columns:290px minmax(0,1fr);min-height:0;flex:1}.acceptance-sidebar{min-width:0;background:#f9faf8;border-right:1px solid #e6e9e3;display:flex;flex-direction:column;min-height:0;padding:18px 12px 0}.acceptance-search{display:flex;align-items:center;gap:8px;background:white;border:1px solid #e0e5de;border-radius:9px;padding:0 10px;color:#7c867d}.acceptance-search input{border:0;min-width:0;width:100%;background:transparent;padding:11px 0;outline-offset:3px}.acceptance-filters{display:flex;gap:8px;margin-top:10px}.acceptance-filters select{min-width:0;width:50%;font-size:12px;padding:9px 6px}.acceptance-list-count{margin:12px 8px 7px;font-size:11px;color:#8a938c}.acceptance-case-list{overflow:auto;padding:0 0 18px}.acceptance-case{width:100%;border:1px solid transparent;text-align:left;display:block;border-radius:10px;background:transparent;padding:12px 13px;margin-bottom:4px;font:inherit;line-height:1.6;color:#545f57;cursor:pointer}.acceptance-case:hover{background:#eef1eb}.acceptance-case.is-selected{background:#e9f1eb;border-color:#dce7dd;color:#2b4c38}.acceptance-case-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:3px}.acceptance-case-top b{font-size:12px;letter-spacing:.04em}.acceptance-case-top small{font-size:10px;color:#7b897e}.acceptance-case>span:last-child{font-size:12px}.acceptance-detail{min-width:0;overflow:auto;padding:28px 34px 20px}.acceptance-detail-title{padding-bottom:22px;border-bottom:1px solid #edf0ea}.acceptance-id{font-size:12px;font-weight:700;letter-spacing:.08em;color:#357655}.acceptance-status{font-size:11px;margin-left:10px;padding:3px 7px;border-radius:5px;background:#f1f3ef;color:#738070}.acceptance-detail-title h3{font-size:23px;line-height:1.45;margin:12px 0 9px;letter-spacing:-.02em}.acceptance-detail-title p,.acceptance-help{font-size:12px;color:#818a80;margin:8px 0 13px}.acceptance-section{padding:22px 0;border-bottom:1px solid #edf0ea}.acceptance-section h4{font-size:13px;margin:18px 0 8px;color:#364438;font-weight:650}.acceptance-section h4:first-child{margin-top:0}.acceptance-section p{white-space:pre-wrap;overflow-wrap:anywhere}.acceptance-section>p:not(.acceptance-help){font-size:13px;margin:6px 0 14px;color:#5b655a}.acceptance-section .acceptance-boundary{background:#f5f7f2;border-left:3px solid #b8cbb5;padding:12px 14px;border-radius:0 7px 7px 0;color:#66725f;font-size:12px}.acceptance-section-heading{display:flex;gap:12px;align-items:center;justify-content:space-between;margin-bottom:9px}.acceptance-section-heading h4{margin:0}.acceptance-section h4 small{font-size:11px;font-weight:400;color:#8a9386;margin-left:8px}.acceptance-procedure{background:#fafbf8;border:1px solid #e7ece2;border-radius:8px;margin:10px 0;padding:10px 12px;font-size:12px}.acceptance-procedure p{line-height:1.9;color:#63705d}.acceptance-dialog summary{cursor:pointer;min-height:24px}.acceptance-field{display:flex;flex-direction:column;gap:6px;margin:12px 0;color:#6a7668;font-size:12px}.acceptance-dialog input,.acceptance-dialog textarea,.acceptance-dialog select{font-family:inherit;color:#334030;font-size:13px}.acceptance-field input,.acceptance-field textarea,.acceptance-field select,.acceptance-filters select{background:#fff;border:1px solid #dee5d9;border-radius:8px;padding:10px 12px;line-height:1.6;box-sizing:border-box;max-width:100%}.acceptance-field textarea{resize:vertical;min-height:68px}.acceptance-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.acceptance-action-row small{color:#8a9386;font-size:11px}.acceptance-inline-field{display:flex;align-items:center;gap:9px;flex-wrap:wrap;margin:12px 0;font-size:12px;color:#6a7668}.acceptance-inline-field label{flex-shrink:0}.acceptance-inline-field input{flex:1 1 240px;min-width:0;background:#fff;border:1px solid #dee5d9;border-radius:8px;padding:9px 12px;line-height:1.5;box-sizing:border-box}.acceptance-dialog button{font-family:inherit;cursor:pointer;transition:background .15s}.acceptance-primary,.acceptance-secondary,.acceptance-quiet,.acceptance-icon{display:inline-flex;align-items:center;justify-content:center;gap:7px;border-radius:8px;font-size:12px;min-height:39px;line-height:1.4;padding:9px 13px;box-sizing:border-box}.acceptance-primary{background:#2f5544;color:#fff;border:1px solid #2f5544}.acceptance-primary:hover{background:#234334}.acceptance-secondary{background:#fff;color:#53624e;border:1px solid #dce4d6}.acceptance-secondary:hover{background:#f4f7ef}.acceptance-quiet,.acceptance-icon{border:1px solid transparent;background:transparent;color:#6c7967}.acceptance-icon{width:39px;padding:0}.acceptance-quiet:hover,.acceptance-icon:hover{background:#f0f4eb}.acceptance-dialog button:disabled{opacity:.45;cursor:not-allowed}.acceptance-dialog :is(button,input,select,textarea,summary):focus-visible{outline:2px solid #46866a;outline-offset:3px}.acceptance-snapshot{border:1px solid #e1e7db;border-radius:9px;padding:12px 14px;font-size:12px;margin:12px 0;background:#fcfdfb}.acceptance-snapshot>summary span{display:block;color:#94a08b;font-size:10px;margin-left:15px}.acceptance-snapshot dl{display:grid;grid-template-columns:80px 1fr;gap:5px 10px;font-size:11px}.acceptance-snapshot dt{color:#8a957f}.acceptance-snapshot dd{margin:0;overflow-wrap:anywhere;color:#52604b}.acceptance-snapshot-answer{max-height:180px;overflow:auto;padding:12px;background:#f5f7f1;border-radius:6px}.acceptance-snapshot pre{font-size:11px;line-height:1.7;background:#f2f5ed;border-radius:6px;padding:12px;overflow:auto;max-height:330px;white-space:pre-wrap;overflow-wrap:anywhere}.acceptance-text-button{border:0;background:transparent;padding:6px 0;color:#7e8b70;font-size:11px;min-height:30px}.acceptance-text-button:hover{color:#2f5544}.acceptance-editor-card{border:1px solid #e2e8db;border-radius:10px;padding:13px 15px;background:#fdfefb;margin:12px 0}.acceptance-card-heading{display:flex;align-items:center;justify-content:space-between;font-size:11px;color:#647557}.acceptance-card-heading .acceptance-text-button{min-height:22px;padding:0}.acceptance-checkbox{display:flex;gap:9px;align-items:flex-start;font-size:12px;color:#78846c;margin:13px 0}.acceptance-checkbox input{accent-color:#3c7053;margin-top:4px;flex-shrink:0}.acceptance-save-row{display:flex;align-items:flex-end;gap:12px;margin-top:18px}.acceptance-save-row .acceptance-field{margin:0;min-width:160px}.acceptance-feedback{position:sticky;bottom:-20px;background:#f1f6edee;border:1px solid #e0e9d8;padding:11px 14px;border-radius:8px;font-size:12px;color:#5c7050;backdrop-filter:blur(8px);margin-top:20px}.acceptance-empty{font-size:12px;padding:16px;color:#84907a}
@media(max-width:760px){.acceptance-dialog{width:100vw;height:100dvh;max-height:100dvh;border-radius:0;border:0}.acceptance-header{padding:15px 16px;gap:8px}.acceptance-heading h2{font-size:17px}.acceptance-heading p{font-size:10px}.acceptance-mark{display:none}.acceptance-header-actions{gap:2px}.acceptance-quiet{font-size:10px;padding:7px}.acceptance-summary{gap:10px;padding:10px 16px}.acceptance-summary small{display:none}.acceptance-body{grid-template-columns:minmax(0,1fr);grid-template-rows:auto minmax(0,1fr)}.acceptance-sidebar{padding:10px 14px;border-right:0;border-bottom:1px solid #e0e7d8;max-height:230px}.acceptance-search{flex-shrink:0}.acceptance-search input{padding:8px 0}.acceptance-filters{margin-top:6px;flex-shrink:0}.acceptance-filters select{padding:6px;min-height:36px}.acceptance-list-count{display:none}.acceptance-case-list{display:flex;gap:7px;overflow-x:auto;overflow-y:hidden;padding:9px 0;flex-shrink:0}.acceptance-case{width:180px;flex-shrink:0;margin:0;padding:8px 10px;min-height:75px}.acceptance-case>span:last-child{font-size:11px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.acceptance-detail{padding:22px 18px 18px}.acceptance-detail-title h3{font-size:20px}.acceptance-grid{grid-template-columns:1fr;gap:0}.acceptance-primary,.acceptance-secondary,.acceptance-icon{min-height:44px}.acceptance-notice{padding:8px 16px}.acceptance-section-heading{align-items:flex-start}.acceptance-save-row{align-items:stretch;flex-wrap:wrap}.acceptance-feedback{bottom:-18px}.acceptance-text-button{min-height:44px}}
@media(prefers-reduced-motion:reduce){.acceptance-dialog button{transition:none}}
</style>
