<script setup lang="ts">
import { computed } from "vue";
import type { AcceptanceSnapshot } from "../acceptance";
import type { Evidence } from "../types";
import { readable, renderAnswer, labels, agentName } from "../presentation";
import { redactAcceptanceText } from "../acceptance";
import EvidenceCards from "./EvidenceCards.vue";
import AcceptanceHttpTraces from "./AcceptanceHttpTraces.vue";
const props = defineProps<{ snapshot: AcceptanceSnapshot }>();
// 展示与审计分开：原始字段仍在内存和主动导出中；页面统一消除内部码、JSON块及堆栈。
function display(value: unknown): string {
  return readable(typeof value === "string" ? redactAcceptanceText(value) : "")
    .replace(/```[\s\S]*?```/g, "（原始记录请在本地导出中核对）")
    .replace(/\{[\s\S]*\}/g, "（结构化原始记录请在本地导出中核对）")
    .replace(/^\s*(?:at\s+.+|Caused by:.+|(?:[\w.$]+)?(?:Error|Exception)(?::.*)?)$/gm, "")
    .replace(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g, "运行策略提示")
    .trim();
}
function status(value: string | undefined) {
  return labels[value ?? ""] ?? ({ submitting: "提交中", running: "执行中", ready: "已返回", error: "请求失败", uncertain: "结果待确认", restore: "等待恢复" }[value ?? ""] ?? "已记录");
}
function safeValue(value: unknown): unknown {
  if (typeof value === "string") { return display(value); }
  if (Array.isArray(value)) { return value.map(safeValue); }
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, safeValue(entry)]));
  }
  return value;
}
const evidence = computed(() => (props.snapshot.result?.evidence ?? []).map((item) => ({ ...item, source: display(item.source), data: safeValue(item.data) as Evidence["data"] })));
const events = computed(() => (props.snapshot.result?.events ?? []).map((event) => display(event) || "该条原始记录仅在本地导出中保留。"));
const actionLabels: Record<string, string> = { MESSAGE: "追加输入", SWITCH: "切换任务", CANCEL: "停止任务" };
/** 计划中的任务数；缺失或格式不符时返回 0，页面只展示可核对数量。 */
const planTasks = computed(() => {
  const tasks = props.snapshot.detail?.plan?.tasks;
  return Array.isArray(tasks) ? tasks.length : 0;
});
/** 计划决策文本；没有写入计划时明确显示“未写入”。 */
const planDecision = computed(() => String(props.snapshot.detail?.plan?.decision ?? "") || "未写入");
/** 用量来源；区分实测 usage 与未采集。 */
const usageSource = computed(() => props.snapshot.detail?.usage.source ?? "未采集");
</script>
<template>
  <div class="acceptance-snapshot-details">
    <dl><dt>会话编号</dt><dd>{{ snapshot.payload.sessionId }}</dd><dt>请求编号</dt><dd>{{ snapshot.payload.requestId }}</dd><dt>任务编号</dt><dd>{{ snapshot.runId || '未取得任务号' }}</dd><dt>任务状态</dt><dd>{{ status(snapshot.result?.state || snapshot.phase) }} · {{ status(snapshot.result?.status || snapshot.phase) }}</dd><dt>任务版本</dt><dd>{{ snapshot.result?.revision ?? '未返回' }}</dd><dt>截止时间</dt><dd>{{ snapshot.result?.deadline ? new Date(snapshot.result.deadline).toLocaleString() : '未返回' }}</dd><dt>模型调用</dt><dd>{{ snapshot.result?.modelCalls ?? '未返回' }}</dd><template v-if="snapshot.result?.replacedBy"><dt>切换后任务</dt><dd>{{ snapshot.result.replacedBy }}</dd></template></dl>
    <h5>本轮输入</h5><p class="acceptance-input">{{ display(snapshot.payload.question) || '没有可展示的输入文本。' }}</p>
    <p v-for="(input, index) in snapshot.inputs" :key="index" class="acceptance-input"><strong>补充 {{ index + 1 }}</strong> {{ display(input) || '原始文本可在本地导出中核对。' }}</p>
    <h5>回答</h5><div class="prose acceptance-answer" v-html="renderAnswer(display(snapshot.result?.answer) || '此刻暂无可展示的回答。')" />
    <p v-if="snapshot.result?.agents.length" class="acceptance-agents">参与协作：{{ snapshot.result.agents.map(name => display(agentName(name))).join('、') }}</p>
    <h5>HTTP请求</h5><AcceptanceHttpTraces :traces="snapshot.traces" />
    <details v-if="events.length" class="acceptance-events"><summary>执行事件 · {{ events.length }} 条</summary><ol><li v-for="(event, index) in events" :key="index">{{ event }}</li></ol><p>按实际返回顺序展示；不代表完整调用图或内部推理。</p></details><p v-else class="acceptance-muted">此副本没有执行事件。</p>
    <h5>查询证据</h5><EvidenceCards v-if="evidence.length" :evidence="evidence" /><p v-else class="acceptance-muted">此副本没有查询证据。</p>
    <details v-if="snapshot.detail" class="acceptance-events"><summary>运行详情证据 · {{ snapshot.detail.agentPath }}</summary><dl><dt>运行编号</dt><dd>{{ snapshot.detail.runId }}</dd><dt>父 / 根运行</dt><dd>{{ snapshot.detail.parentRunId || '无（根运行）' }} / {{ snapshot.detail.rootRunId }}</dd><dt>Agent</dt><dd>{{ snapshot.detail.agentRole }} · {{ snapshot.detail.agentVersion }}</dd><dt>代际 / 版本</dt><dd>{{ snapshot.detail.generation }} / {{ snapshot.detail.revision }}（已消费 {{ snapshot.detail.consumedRevision }}）</dd><dt>模型预算</dt><dd>{{ snapshot.detail.modelCalls }} / {{ snapshot.detail.maxModelCalls }} 次</dd><dt>计划</dt><dd>{{ planDecision }} · {{ planTasks }} 个任务</dd><dt>子运行 / 工具 / 委派</dt><dd>{{ snapshot.detail.children.length }} / {{ snapshot.detail.toolCalls.length }} / {{ snapshot.detail.delegations.length }}</dd><dt>用量</dt><dd>{{ usageSource }} · 输入 {{ snapshot.detail.usage.promptTokens }} · 输出 {{ snapshot.detail.usage.completionTokens }}</dd><dt>span</dt><dd>{{ snapshot.detail.spans.length }} 段</dd><dt>压缩 / 召回</dt><dd>{{ snapshot.detail.compactions.length }} / {{ snapshot.detail.recalls.length }}</dd><dt>权限复核</dt><dd>{{ snapshot.detail.permissions.evidenceRecheckPassed ? '通过' : '存在被过滤工具' }}（证据 {{ snapshot.detail.permissions.evidenceCount }} 条）</dd><dt>提示词版本</dt><dd>{{ snapshot.detail.promptVersions.length }} 个角色</dd><dt>内容信任</dt><dd>{{ (snapshot.detail.contentTrust ?? []).map(item => `${item.level} ${item.count}`).join(' · ') || '旧记录未标记' }}</dd></dl><p>检查点白名单：{{ Object.keys(snapshot.detail.checkpoints).join('、') || '无' }}</p><ol v-if="snapshot.detail.toolCalls.length"><li v-for="call in snapshot.detail.toolCalls.slice(0, 20)" :key="call.runId + call.callId">{{ display(call.agentPath) }} · {{ display(call.tool) }} · {{ display(call.status) }} · {{ call.durationMs ?? '—' }} ms · {{ call.resultBytes }} 字节</li></ol></details>
    <details v-if="snapshot.config" class="acceptance-events"><summary>配置证据 · {{ snapshot.config.mode }}</summary><dl><dt>模式</dt><dd>{{ snapshot.config.mode }}（数据库 {{ snapshot.config.databaseType }}）</dd><dt>超时 / 步数</dt><dd>{{ snapshot.config.timeoutSeconds }} 秒 / {{ snapshot.config.maxSteps }} 步</dd><dt>模型调用预算</dt><dd>{{ snapshot.config.maxModelCalls }} 次</dd><dt>并发</dt><dd>全局 {{ snapshot.config.maxConcurrentRequests }} · 每用户 {{ snapshot.config.maxConcurrentPerUser }}</dd><dt>速率</dt><dd>任务 {{ snapshot.config.tasksPerUserMinute }}/分钟 · 模型调用 {{ snapshot.config.modelCallsPerMinute }}/分钟 · token {{ snapshot.config.modelTokensPerMinute }}/分钟</dd><dt>多 Agent</dt><dd>{{ snapshot.config.multiAgentMode }} · 上限 {{ snapshot.config.multiAgentMaxAgents }} 个 / 深度 {{ snapshot.config.multiAgentMaxDepth }}</dd><dt>校验开关</dt><dd>事实校验 {{ snapshot.config.factCheckEnabled ? '开启' : '关闭' }} · 语义校验 {{ snapshot.config.semanticVerifierEnabled ? '开启' : '关闭' }} · 上下文召回 {{ snapshot.config.contextRecallEnabled ? '开启' : '关闭' }} · 循环检测 {{ snapshot.config.loopDetectionEnabled ? '开启' : '关闭' }} · 缺失条件策略 {{ snapshot.config.summaryMissingConditionPolicy }}</dd><dt>业务规则</dt><dd>{{ snapshot.config.businessRulesEnabled ? '开启' : '关闭' }} · {{ snapshot.config.businessRuleCount }} 条</dd><dt>认证身份</dt><dd>{{ snapshot.config.authIdentities.join('；') || '未配置' }}</dd></dl><p>配置证据已脱敏，不包含令牌。</p></details>
    <details v-if="snapshot.lastInstruction"><summary>最近一次指令</summary><dl><dt>动作</dt><dd>{{ actionLabels[snapshot.lastInstruction.action] || '运行操作' }}</dd><dt>指令编号</dt><dd>{{ snapshot.lastInstruction.instructionId }}</dd><dt>目标任务</dt><dd>{{ snapshot.lastInstruction.runId }}</dd><dt>预期版本</dt><dd>{{ snapshot.lastInstruction.expectedRevision }}</dd><dt>指令内容</dt><dd>{{ display(snapshot.lastInstruction.message) }}</dd></dl></details>
    <details v-if="snapshot.probes.length"><summary>验收请求与重放 · {{ snapshot.probes.length }} 条</summary><section v-for="(probe, index) in snapshot.probes" :key="index" class="acceptance-probe"><h5>{{ index + 1 }} · {{ display(probe.kind) || '请求记录' }}</h5><dl><dt>时间</dt><dd>{{ probe.time }}</dd><dt>HTTP</dt><dd>{{ probe.httpStatus ?? '连接中断' }}</dd><dt>任务编号</dt><dd>{{ probe.runId || '未返回' }}</dd><template v-if="'question' in probe.request"><dt>会话编号</dt><dd>{{ probe.request.sessionId }}</dd><dt>请求编号</dt><dd>{{ probe.request.requestId }}</dd><dt>请求内容</dt><dd>{{ display(probe.request.question) }}</dd></template><template v-else><dt>指令动作</dt><dd>{{ actionLabels[probe.request.action] || '运行操作' }}</dd><dt>指令编号</dt><dd>{{ probe.request.instructionId }}</dd><dt>预期版本</dt><dd>{{ probe.request.expectedRevision }}</dd><dt>指令内容</dt><dd>{{ display(probe.request.message) }}</dd></template><dt>实际反馈</dt><dd>{{ display(probe.outcome) || '原始反馈保留在本地导出中。' }}</dd></dl></section></details>
    <p class="acceptance-muted">完整原始记录仅在主动导出的本地文件中供审计核对，页面不展示原始JSON、内部错误码或堆栈。</p>
  </div>
</template>
<style scoped>
.acceptance-snapshot-details{font-size:12px;color:#5d6d54;line-height:1.7}.acceptance-snapshot-details dl{display:grid;grid-template-columns:82px minmax(0,1fr);gap:5px 10px;margin:14px 0}.acceptance-snapshot-details dt{color:#88977e}.acceptance-snapshot-details dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap}.acceptance-snapshot-details h5{font-size:12px;color:#41553a;margin:18px 0 8px}.acceptance-input,.acceptance-answer{padding:12px 14px;background:#f3f6ef;border-radius:7px;overflow-wrap:anywhere;white-space:pre-wrap}.acceptance-answer{max-height:350px;overflow:auto;white-space:normal}.acceptance-agents{font-size:11px;color:#788d69}.acceptance-muted,.acceptance-events>p{color:#8a967f;font-size:11px}.acceptance-events{border:1px solid #e1e9d9;border-radius:7px;padding:10px;margin:12px 0}.acceptance-events ol{padding-left:22px}.acceptance-events li{padding:5px 0;white-space:pre-wrap;overflow-wrap:anywhere}.acceptance-snapshot-details summary{min-height:26px;cursor:pointer}.acceptance-probe{border-top:1px solid #e7eddf;margin-top:12px;padding:0 8px}
</style>
