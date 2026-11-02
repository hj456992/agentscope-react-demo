<script setup lang="ts">
import { computed, ref } from "vue";
import type { ConfigEvidence, RunDetail, Turn } from "../types";
import { agentName, labels, renderAnswer } from "../presentation";
import {
  authenticated,
  loadConfigEvidence,
  loadRunDetail,
  refresh,
  recoverSubmission,
} from "../store";
import Icon from "./Icon.vue";
import ExecutionTimeline from "./ExecutionTimeline.vue";
import ReplayPanel from "./ReplayPanel.vue";
import EvidenceCards from "./EvidenceCards.vue";
const props = defineProps<{ turn: Turn }>();
const confirmed = ref(false);
const run = computed(() => props.turn.result);
const detail = ref<RunDetail>();
const config = ref<ConfigEvidence>();
const detailLoading = ref(false);
const detailError = ref("");

/** 按需读取运行详情和配置证据；只在页面展示，不写入会话存储。 */
async function loadEvidence() {
  if (!props.turn.runId || detailLoading.value) {
    return;
  }
  detailLoading.value = true;
  detailError.value = "";
  try {
    detail.value = await loadRunDetail(props.turn.runId);
    config.value = await loadConfigEvidence();
  } catch (error) {
    detailError.value =
      error instanceof Error ? error.message : "运行详情读取失败。";
  } finally {
    detailLoading.value = false;
  }
}

function evidenceToggle(event: Event) {
  const element = event.target as HTMLDetailsElement | null;
  if (element?.open && !detail.value && !detailLoading.value) {
    void loadEvidence();
  }
}

function pretty(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}
const working = computed(
  () =>
    props.turn.phase === "submitting" ||
    (run.value?.state === "RUNNING" && !props.turn.paused),
);
const title = computed(() => {
  if (props.turn.phase === "submitting") {
    return props.turn.mode === "sync" ? "同步等待中" : "正在提交";
  }
  if (props.turn.phase === "error" && !props.turn.runId) {
    return "请求未受理";
  }
  if (props.turn.paused) {
    return "等待恢复";
  }
  return (
    labels[run.value?.status || ""] ||
    labels[run.value?.state || ""] ||
    "待恢复"
  );
});
</script>
<template>
  <article class="turn">
    <div v-if="turn.visible" class="user-row">
      <div class="user-bubble">
        {{ turn.payload.question || run?.originalQuery }}
      </div>
    </div>
    <div class="assistant-row">
      <div class="assistant-avatar" aria-hidden="true">S<span></span></div>
      <div class="assistant-content">
        <div class="message-byline">
          <strong>Supervisor</strong
          ><span
            class="status-label"
            :class="{ warning: turn.paused || run?.status === 'PARTIAL' }"
            >{{ title }}</span
          >
        </div>
        <template v-if="turn.phase === 'restore'"
          ><p class="muted">
            {{
              turn.runId
                ? "重新配置令牌后，从服务端恢复这条任务。"
                : "上次提交结果待确认。请先配置原身份令牌，再恢复原请求。"
            }}
          </p></template
        >
        <div v-if="working" class="working" role="status">
          <span class="thinking-dot"></span
          >{{
            turn.phase === "submitting"
              ? turn.mode === "sync"
                ? "正在等待完整回答…"
                : "正在创建任务…"
              : "正在查询资料并整理回答…"
          }}
        </div>
        <div
          v-if="run && run.state !== 'RUNNING'"
          class="prose"
          v-html="
            renderAnswer(
              run.answer || '本轮未返回可展示的回答，请查看任务状态。',
            )
          "
        ></div>
        <p v-if="run?.state === 'NEEDS_INPUT'" class="input-needed">
          <Icon name="chat" :size="16" />请在下方输入框回答，继续当前任务。
        </p>
        <div v-if="turn.inputs.length" class="supplements">
          <p v-for="(input, index) in turn.inputs" :key="index">
            <strong>你的补充</strong> {{ input }}
          </p>
        </div>
        <EvidenceCards v-if="run?.evidence?.length" :evidence="run.evidence" />
        <div v-if="run?.agents?.length" class="agent-list">
          <span>参与协作</span
          ><span v-for="agent in run.agents" :key="agent" class="agent-chip"
            ><span class="tiny-dot"></span>{{ agentName(agent) }}</span
          >
        </div>
        <p v-if="turn.instructionNotice" class="instruction-notice" role="status">{{ turn.instructionNotice }}</p>
        <ExecutionTimeline v-if="run" :events="run.events || []" :running="run.state === 'RUNNING'"/>
        <div v-if="turn.error" class="error-banner" role="alert">
          <Icon name="info" :size="18" /><span>{{ turn.error }}</span>
        </div>
        <button
          v-if="turn.paused && turn.runId && authenticated"
          class="text-button"
          :disabled="turn.busy"
          @click="refresh(turn)"
        >
          <Icon name="refresh" :size="16" />恢复查询
        </button>
        <div
          v-if="
            !turn.runId &&
            (turn.phase === 'restore' || turn.phase === 'uncertain') &&
            authenticated
          "
          class="recover-box"
        >
          <label
            ><input
              v-model="confirmed"
              type="checkbox"
            />我已确认当前令牌属于上次提交的身份</label
          >
          <button
            class="text-button"
            :disabled="!confirmed || turn.busy"
            @click="recoverSubmission(turn)"
          >
            <Icon name="refresh" :size="16" />恢复原请求
          </button>
          <small>复用原请求编号，不自动另建一轮。</small>
        </div>
        <details class="task-details">
          <summary>
            <Icon name="info" :size="14" />任务与请求详情<span v-if="turn.runId"
              >{{ turn.mode === "sync" ? "同步" : "异步" }} ·
              {{ turn.runId.slice(0, 8) }}</span
            >
          </summary>
          <div class="detail-body">
            <dl>
              <dt>会话编号</dt>
              <dd>{{ turn.payload.sessionId }}</dd>
              <dt>请求编号</dt>
              <dd>{{ run?.requestId || turn.payload.requestId }}</dd>
              <dt>任务编号</dt>
              <dd>{{ turn.runId || "尚未返回" }}</dd>
              <template v-if="run"
                ><dt>任务版本</dt>
                <dd>{{ run.revision }}</dd>
                <dt>任务状态</dt>
                <dd>{{ labels[run.state] || run.state }}</dd>
                <dt>模型调用</dt>
                <dd>{{ run.modelCalls }} 次</dd>
                <dt>截止时间</dt>
                <dd>
                  {{
                    new Date(run.deadline).toLocaleString("zh-CN", {
                      hour12: false,
                    })
                  }}
                </dd></template
              >
              <dt>调用模式</dt>
              <dd>
                {{
                  turn.mode === "sync"
                    ? "同步等待完整结果"
                    : "异步受理并查询进度"
                }}
              </dd>
              <dt>认证信息</dt>
              <dd>已隐藏，不记录令牌</dd>
            </dl>
            <button v-if="turn.runId && authenticated" class="text-button" :disabled="turn.busy" @click="refresh(turn)"><Icon name="refresh" :size="14"/>查询最新状态</button>
            <ReplayPanel v-if="run && turn.visible" :turn="turn"/>
            <div v-if="turn.traces.length" class="request-history">
              <div
                v-for="(trace, index) in turn.traces"
                :key="index"
                class="request-line"
              >
                <time>{{ trace.time }}</time
                ><b
                  :class="{
                    'http-error': trace.status === null || trace.status >= 400,
                  }"
                  >{{ trace.status ?? "中断" }}</b
                ><code>{{ trace.method }} {{ trace.path }}</code
                ><span>{{ trace.ms }} ms</span>
              </div>
            </div>
            <details
              v-if="turn.runId"
              class="run-evidence"
              @toggle="evidenceToggle"
            >
              <summary>
                运行详情与配置证据<span>{{
                  detail ? "已加载" : "打开后按需读取"
                }}</span>
              </summary>
              <div class="evidence-toolbar">
                <button
                  class="text-button"
                  :disabled="!authenticated || detailLoading"
                  @click="loadEvidence"
                >
                  <Icon name="refresh" :size="14" />{{
                    detail ? "刷新证据" : "加载证据"
                  }}
                </button>
                <span v-if="detailLoading" class="muted">正在读取…</span>
                <span v-if="detailError" class="evidence-error">{{
                  detailError
                }}</span>
              </div>
              <template v-if="detail">
                <dl class="evidence-grid">
                  <dt>运行编号</dt>
                  <dd>{{ detail.runId }}</dd>
                  <dt>会话编号</dt>
                  <dd>{{ detail.sessionId }}</dd>
                  <dt>父任务</dt>
                  <dd>{{ detail.parentRunId || "无（根运行）" }}</dd>
                  <dt>根运行</dt>
                  <dd>{{ detail.rootRunId }}</dd>
                  <dt>Agent 路径 / 角色</dt>
                  <dd>{{ detail.agentPath }} / {{ detail.agentRole }}</dd>
                  <dt>Agent 版本</dt>
                  <dd>{{ detail.agentVersion }}</dd>
                  <dt>代际 / 版本</dt>
                  <dd>{{ detail.generation }} / {{ detail.revision }}</dd>
                  <dt>触发方式</dt>
                  <dd>
                    {{ detail.triggerMode }}
                    <template v-if="detail.acceptanceHint"
                      >· {{ detail.acceptanceHint }}</template
                    >
                    <template v-if="detail.recovered">· 进程恢复接管</template>
                  </dd>
                  <dt>首次事件 / 完成</dt>
                  <dd>
                    {{
                      detail.firstEventAt
                        ? new Date(detail.firstEventAt).toLocaleString("zh-CN", {
                            hour12: false,
                          })
                        : "—"
                    }}
                    /
                    {{
                      detail.completedAt
                        ? new Date(detail.completedAt).toLocaleString("zh-CN", {
                            hour12: false,
                          })
                        : "—"
                    }}
                  </dd>
                  <dt>请求指纹</dt>
                  <dd class="fingerprint">{{ detail.requestFingerprint }}</dd>
                  <dt>预算</dt>
                  <dd>
                    {{ detail.modelCalls }} / {{ detail.maxModelCalls }} 次模型调用
                  </dd>
                  <dt>权限复核</dt>
                  <dd>
                    {{
                      detail.permissions.evidenceRecheckPassed
                        ? "通过"
                        : "存在被过滤工具"
                    }}（证据 {{ detail.permissions.evidenceCount }} 条）
                  </dd>
                  <dt>内容信任</dt>
                  <dd>
                    {{
                      (detail.contentTrust ?? [])
                        .map(item => `${item.level} ${item.count}`)
                        .join(" · ") || "旧记录未标记"
                    }}
                  </dd>
                </dl>
                <details class="evidence-block">
                  <summary>计划 · {{ detail.plan.decision || "未写入" }}</summary>
                  <pre>{{ pretty(detail.plan) }}</pre>
                </details>
                <details class="evidence-block">
                  <summary>
                    路由 · {{ detail.routing.decision }} ·
                    {{ detail.routing.confidence }}
                  </summary>
                  <p class="muted">{{ detail.routing.reason }}</p>
                  <table v-if="detail.routing.candidates.length">
                    <thead>
                      <tr>
                        <th>候选</th>
                        <th>目录工具</th>
                        <th>有权限</th>
                        <th>已选择</th>
                        <th>理由</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="candidate in detail.routing.candidates"
                        :key="candidate.role"
                      >
                        <td>{{ candidate.role }}</td>
                        <td>{{ candidate.catalogTools.join(", ") }}</td>
                        <td>{{ candidate.permitted ? "是" : "否" }}</td>
                        <td>{{ candidate.selected ? "是" : "否" }}</td>
                        <td>{{ candidate.reason }} / {{ candidate.confidence }}</td>
                      </tr>
                    </tbody>
                  </table>
                </details>
                <details class="evidence-block">
                  <summary>子运行 · {{ detail.children.length }}</summary>
                  <table v-if="detail.children.length">
                    <thead>
                      <tr>
                        <th>路径</th>
                        <th>状态</th>
                        <th>任务书</th>
                        <th>操作号</th>
                        <th>代际</th>
                        <th>调用</th>
                        <th>截止时间</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="child in detail.children.slice(0, 50)"
                        :key="child.runId"
                      >
                        <td>{{ child.agentPath }}</td>
                        <td>
                          {{ child.state }} / {{ child.agentStatus
                          }}<template v-if="child.recovered"> · 已恢复</template>
                        </td>
                        <td>
                          <template v-if="child.taskBrief"
                            >{{ child.taskBrief.objective
                            }}<br /><small
                              >批次 {{ child.taskBrief.batch }} · 依赖
                              {{ child.taskBrief.dependsOn.join(",") || "无" }} ·
                              继承 {{ child.taskBrief.inheritedMessages }} 条 /
                              {{ child.taskBrief.inheritedBytes }} 字节</small
                            ></template
                          >
                          <template v-else>—</template>
                        </td>
                        <td class="fingerprint">
                          {{ child.spawnOperationId || "—" }}
                        </td>
                        <td>{{ child.generation }}</td>
                        <td>{{ child.modelCalls }}</td>
                        <td>{{ new Date(child.deadline).toLocaleString("zh-CN", { hour12: false }) }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="muted">没有子运行。</p>
                </details>
                <details class="evidence-block">
                  <summary>工具调用配对 · {{ detail.toolCalls.length }}</summary>
                  <table v-if="detail.toolCalls.length">
                    <thead>
                      <tr>
                        <th>Agent</th>
                        <th>工具</th>
                        <th>操作号</th>
                        <th>状态</th>
                        <th>配对/复用</th>
                        <th>失败分类</th>
                        <th>耗时</th>
                        <th>结果字节</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="call in detail.toolCalls.slice(0, 50)"
                        :key="call.runId + call.callId"
                      >
                        <td>{{ call.agentPath }}</td>
                        <td>{{ call.tool }}</td>
                        <td class="fingerprint">{{ call.operationId || "—" }}</td>
                        <td>{{ call.status }}</td>
                        <td>
                          {{ call.paired ? "已配对" : "待配对" }} /
                          {{ call.reused ? "复用" : "新执行" }}
                        </td>
                        <td>
                          {{ call.failureClass || "—"
                          }}<small v-if="call.providerCode"
                            >· {{ call.providerCode }}</small
                          ><small v-if="call.retryAfterMs !== null"
                            >· {{ call.retryAfterMs }}ms</small
                          >
                        </td>
                        <td>{{ call.durationMs ?? "—" }} ms</td>
                        <td>{{ call.resultBytes }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="muted">没有工具调用记录。</p>
                </details>
                <details class="evidence-block">
                  <summary>委派回执 · {{ detail.delegations.length }}</summary>
                  <table v-if="detail.delegations.length">
                    <thead>
                      <tr>
                        <th>操作号</th>
                        <th>子运行</th>
                        <th>子路径</th>
                        <th>状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="item in detail.delegations.slice(0, 50)"
                        :key="item.receiptKey"
                      >
                        <td>{{ item.operationId }}</td>
                        <td>{{ item.childRunId || "—" }}</td>
                        <td>{{ item.childAgentPath || "—" }}</td>
                        <td>{{ item.childStatus || "—" }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="muted">没有委派回执。</p>
                </details>
                <details class="evidence-block">
                  <summary>用量 · {{ detail.usage.source }}</summary>
                  <pre>{{ pretty(detail.usage) }}</pre>
                </details>
                <details class="evidence-block">
                  <summary>span · {{ detail.spans.length }}</summary>
                  <table v-if="detail.spans.length">
                    <thead>
                      <tr>
                        <th>类型</th>
                        <th>名称</th>
                        <th>状态</th>
                        <th>耗时</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="span in detail.spans.slice(0, 50)"
                        :key="span.spanId"
                      >
                        <td>{{ span.kind }}</td>
                        <td>{{ span.name }}</td>
                        <td>{{ span.status }}</td>
                        <td>{{ span.durationMs ?? "—" }} ms</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="muted">没有 span。</p>
                </details>
                <details class="evidence-block">
                  <summary>
                    压缩 · {{ detail.compactions.length }} / 召回 ·
                    {{ detail.recalls.length }}
                  </summary>
                  <pre>{{ pretty({ compactions: detail.compactions, recalls: detail.recalls }) }}</pre>
                </details>
                <details class="evidence-block">
                  <summary>
                    权限 · 目录 {{ detail.permissions.directoryTools.length }} / 执行
                    {{ detail.permissions.executionTools.length }} / 证据
                    {{ detail.permissions.evidenceCount }}
                  </summary>
                  <pre>{{ pretty(detail.permissions) }}</pre>
                </details>
                <details class="evidence-block">
                  <summary>
                    指令回执 · {{ detail.instructions.length }} / 纠错 ·
                    {{ detail.corrections.length }}
                  </summary>
                  <table v-if="detail.instructions.length">
                    <thead>
                      <tr>
                        <th>动作</th>
                        <th>期望版本</th>
                        <th>结果版本</th>
                        <th>终态</th>
                        <th>指令编号</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="item in detail.instructions"
                        :key="item.instructionId"
                      >
                        <td>{{ item.action }}</td>
                        <td>{{ item.expectedRevision }}</td>
                        <td>{{ item.resultRevision }}</td>
                        <td>{{ item.resultState }}</td>
                        <td class="fingerprint">{{ item.instructionId }}</td>
                      </tr>
                    </tbody>
                  </table>
                  <p v-else class="muted">没有指令回执。</p>
                  <pre v-if="detail.corrections.length">{{
                    pretty(detail.corrections)
                  }}</pre>
                </details>
                <details class="evidence-block">
                  <summary>时间线 · {{ detail.timeline.length }}</summary>
                  <ol class="timeline-list">
                    <li v-for="item in detail.timeline" :key="item.sequence">
                      <time>{{
                        item.at
                          ? new Date(item.at).toLocaleTimeString("zh-CN", {
                              hour12: false,
                            })
                          : "时间未知"
                      }}</time>
                      {{ item.text }}
                    </li>
                  </ol>
                </details>
                <details class="evidence-block">
                  <summary>提示词版本 · {{ detail.promptVersions.length }}</summary>
                  <table>
                    <thead>
                      <tr>
                        <th>角色</th>
                        <th>提示词</th>
                        <th>版本</th>
                        <th>哈希</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="prompt in detail.promptVersions" :key="prompt.role">
                        <td>{{ prompt.role }}</td>
                        <td>{{ prompt.promptId }}</td>
                        <td>{{ prompt.version }} / {{ prompt.source }}</td>
                        <td class="fingerprint">{{ prompt.hash.slice(0, 16) }}…</td>
                      </tr>
                    </tbody>
                  </table>
                </details>
                <details class="evidence-block">
                  <summary>检查点（白名单）</summary>
                  <pre>{{ pretty(detail.checkpoints) }}</pre>
                </details>
                <details v-if="config" class="evidence-block">
                  <summary>实际配置（脱敏）</summary>
                  <pre>{{ pretty(config) }}</pre>
                </details>
              </template>
            </details>
          </div>
        </details>
      </div>
    </div>
  </article>
</template>
<style scoped>
.run-evidence {
  margin-top: 12px;
  border-top: 1px dashed #dfe6d8;
  padding-top: 10px;
  font-size: 12px;
}
.run-evidence > summary {
  cursor: pointer;
  min-height: 28px;
  color: #43553c;
}
.run-evidence > summary span {
  color: #8a9782;
  font-size: 11px;
  margin-left: 8px;
}
.evidence-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 8px 0;
}
.evidence-error {
  color: #a34d40;
}
.evidence-grid {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 5px 10px;
  margin: 10px 0;
}
.evidence-grid dt {
  color: #88977e;
}
.evidence-grid dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.evidence-block {
  border: 1px solid #e5ebdd;
  border-radius: 7px;
  padding: 8px 10px;
  margin: 8px 0;
  background: #fbfcf8;
}
.evidence-block > summary {
  cursor: pointer;
  min-height: 24px;
  color: #475a3f;
}
.evidence-block table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 6px;
  font-size: 11px;
}
.evidence-block th,
.evidence-block td {
  text-align: left;
  border-bottom: 1px solid #eef2e8;
  padding: 5px 6px;
  overflow-wrap: anywhere;
}
.evidence-block th {
  color: #7c8a72;
  font-weight: 600;
}
.evidence-block pre {
  margin: 6px 0 0;
  padding: 8px;
  background: #f2f5ed;
  border-radius: 6px;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 11px;
}
.fingerprint {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
