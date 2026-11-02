<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from "vue";
import Icon from "./components/Icon.vue";
import MessageCard from "./components/MessageCard.vue";
import AcceptancePanel from "./components/AcceptancePanel.vue";
import GovernancePanel from "./components/GovernancePanel.vue";
import MemoryPanel from "./components/MemoryPanel.vue";
import {
  activeTurn,
  authenticated,
  credentialEpoch,
  current,
  instruct,
  newSession,
  selectedId,
  selectSession,
  sessions,
  setToken,
  storageWarning,
  submit,
} from "./store";
import type { Mode, Action, Turn } from "./types";

const mode = ref<Mode>("async");
const draft = ref("");
/** 受控验收提示；页面显式展示，提交记录会标注 CONTROLLED。 */
const acceptanceHint = ref("");
const drafts = reactive<Record<string,string>>({});
const caseSessions = reactive<Record<string,string>>({});
const acceptanceOpen = ref(false);
const memoryOpen = ref(false);
const governanceOpen = ref(false);
const actionType = ref<Action>("SWITCH");
const actionTurn = ref<Turn>();
const actionTitles: Record<Action,string> = {MESSAGE:"补充说明",SWITCH:"切换到新任务",CANCEL:"停止任务"};
const actionNotes: Record<Action,string> = {
  MESSAGE:"把这句话加入原任务，服务端会在执行边界处理。是补充条件还是修改要求由模型结合上下文判断，原期限和预算保持不变。",
  SWITCH:"在同一会话创建替代任务。已受理的子助手按后端自身状态继续，已完成动作不会被撤销。",
  CANCEL:"请求服务端立即停止当前任务，不需要等待版本对齐。",
};
const composing = ref(false);
const validation = ref("");
const sidebar = ref(false);
const authOpen = ref(false);
const secret = ref("");
const authError = ref("");
const switchOpen = ref(false);
const switchDraft = ref("");
const switchError = ref("");
const messages = ref<HTMLElement>();
const textarea = ref<HTMLTextAreaElement>();
const tokenInput = ref<HTMLInputElement>();
const switchInput = ref<HTMLTextAreaElement>();
const authTrigger = ref<HTMLButtonElement>();
const pending = computed(() => activeTurn.value);
const needsInput = computed(
  () => pending.value?.result?.state === "NEEDS_INPUT",
);
const running = computed(() => pending.value?.result?.state === "RUNNING");
const occupied = computed(
  () =>
    pending.value?.busy ||
    pending.value?.phase === "submitting" ||
    running.value ||
    pending.value?.phase === "uncertain" ||
    pending.value?.phase === "restore",
);
const cancellable = computed(
  () =>
    pending.value?.runId &&
    ["RUNNING", "NEEDS_INPUT"].includes(pending.value.result?.state || ""),
);
const examples = [
  {
    icon: "compass",
    title: "规划一次短途旅行",
    text: "明天从杭州到上海玩一天，看看天气和火车票，规划行程",
    note: "让天气、车票和行程助手一起协作",
  },
  {
    icon: "sun",
    title: "出发前，看看天气",
    text: "杭州明天的天气怎么样",
    note: "查询天气，查看来源与日期",
  },
  {
    icon: "train",
    title: "找到合适的车次",
    text: "帮我看看杭州到上海的火车票",
    note: "查看时刻、席别与演示余票",
  },
];

async function openAuth() {
  acceptanceOpen.value = false;
  memoryOpen.value = false;
  governanceOpen.value = false;
  secret.value = "";
  authError.value = "";
  authOpen.value = true;
  await nextTick();
  tokenInput.value?.focus();
}
function closeAuth() {
  secret.value = "";
  authOpen.value = false;
  authTrigger.value?.focus();
}
function saveAuth() {
  if (!secret.value.trim()) {
    authError.value = "请输入访问令牌。";
    return;
  }
  setToken(secret.value);
  closeAuth();
  void nextTick(() => textarea.value?.focus());
}
function clearAuth() {
  setToken("");
  closeAuth();
}
async function send() {
  validation.value = "";
  if (!draft.value.trim()) {
    validation.value = "请输入问题后再发送。";
    return;
  }
  if (draft.value.length > 2000) {
    validation.value = "问题最多 2000 字，请缩短后再发送。";
    return;
  }
  if (!authenticated.value) {
    await openAuth();
    return;
  }
  const value = draft.value;
  const originatingSession = selectedId.value;
  if (needsInput.value && pending.value) {
    if (
      (await instruct(pending.value, "MESSAGE", value.trim())) &&
      selectedId.value === originatingSession &&
      draft.value === value
    ) {
      draft.value = "";
    }
    return;
  }
  if (occupied.value) {
    validation.value = "当前任务尚未结束，请等待、停止或切换任务。";
    return;
  }
  draft.value = "";
  const submitted = await submit(
    value,
    mode.value,
    acceptanceHint.value.trim() || undefined,
  );
  if (submitted && submitted.phase !== "error") {
    acceptanceHint.value = "";
  }
  if (
    submitted?.phase === "error" &&
    !submitted.runId &&
    selectedId.value === originatingSession &&
    !draft.value
  ) {
    draft.value = value;
  }
}
function keyboard(event: KeyboardEvent) {
  if (
    event.key === "Enter" &&
    !event.shiftKey &&
    !event.isComposing &&
    !composing.value &&
    event.keyCode !== 229
  ) {
    event.preventDefault();
    void send();
  }
}
function chooseSession(value: string) {
  selectSession(value);
  sidebar.value = false;
  switchOpen.value = false;
  validation.value = "";
}
function createSession() {
  newSession();
  sidebar.value = false;
  switchOpen.value = false;
  validation.value = "";
  void nextTick(() => textarea.value?.focus());
}
async function chooseExample(text: string) {
  draft.value = text;
  await nextTick();
  textarea.value?.focus();
}
async function openAction(action: Action) {
  actionType.value = action;
  actionTurn.value = pending.value;
  switchDraft.value = "";
  switchError.value = "";
  switchOpen.value = true;
  await nextTick();
  switchInput.value?.focus();
}
async function applySwitch() {
  if (!switchDraft.value.trim() || switchDraft.value.length > 2000) {
    switchError.value = "请输入 1–2000 字的指令内容。";
    return;
  }
  const target = actionTurn.value;
  if (!target) {
    switchError.value = "当前任务已变化，请关闭后重新查询。";
    return;
  }
  if (await instruct(target, actionType.value, switchDraft.value.trim())) {
    switchOpen.value = false;
  } else if (switchOpen.value && actionTurn.value === target) {
    switchError.value = target.error || "当前任务状态已变化，请关闭后查询最新结果。输入已保留。";
  }
}
function trapFocus(event: KeyboardEvent) {
  if (event.key !== "Tab") {
    return;
  }
  const elements = (
    event.currentTarget as HTMLElement
  ).querySelectorAll<HTMLElement>(
    'button:not(:disabled), input, textarea, select, [tabindex="0"]',
  );
  const first = elements[0];
  const last = elements[elements.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
function prepareCase(caseId: string, question: string) {
  newSession();
  caseSessions[selectedId.value] = caseId;
  draft.value = question;
  acceptanceOpen.value = false;
  sidebar.value = false;
  void nextTick(() => textarea.value?.focus());
}
watch(selectedId, (next, previous) => {
  if (previous) {
    drafts[previous] = draft.value;
  }
  draft.value = drafts[next] ?? "";
  validation.value = "";
}, { flush: "sync" });
let hadCredential = false;
watch(credentialEpoch, () => {
  if (hadCredential) {
    for (const key of Object.keys(drafts)) {
      delete drafts[key];
    }
    for (const key of Object.keys(caseSessions)) {
      delete caseSessions[key];
    }
    draft.value = "";
  }
  hadCredential = authenticated.value;
  actionTurn.value = undefined;
  switchOpen.value = false;
});
watch(
  () =>
    current.value?.turns
      .map(
        (turn) => `${turn.phase}:${turn.result?.state}:${turn.result?.answer}`,
      )
      .join("|"),
  async () => {
    const nearBottom =
      !messages.value ||
      messages.value.scrollHeight -
        messages.value.scrollTop -
        messages.value.clientHeight <
        180;
    await nextTick();
    if (nearBottom && messages.value) {
      messages.value.scrollTop = messages.value.scrollHeight;
    }
  },
);
watch(draft, () => {
  validation.value = "";
  drafts[selectedId.value] = draft.value;
  nextTick(() => {
    if (textarea.value) {
      textarea.value.style.height = "auto";
      textarea.value.style.height = `${Math.min(textarea.value.scrollHeight, 160)}px`;
    }
  });
});
</script>

<template>
  <div class="app-shell">
    <button
      v-if="sidebar"
      class="nav-scrim"
      aria-label="关闭会话导航"
      @click="sidebar = false"
    ></button>
    <aside class="sidebar" :class="{ open: sidebar }" aria-label="会话导航">
      <div class="brand">
        <span class="brand-mark">S<span></span></span
        ><span>Supervisor<span class="brand-sub">你的多助手工作空间</span></span
        ><button
          class="icon-button mobile-only"
          aria-label="关闭导航"
          @click="sidebar = false"
        >
          <Icon name="close" />
        </button>
      </div>
      <button class="new-chat" @click="createSession">
        <Icon name="plus" />新对话<span>⌘</span>
      </button>
      <div class="workspace-nav">
        <button @click="acceptanceOpen = true; sidebar = false"><Icon name="clipboard" :size="18"/>验收场景<span class="nav-count">43</span></button>
        <button @click="governanceOpen = true; sidebar = false"><Icon name="settings" :size="18"/>平台治理</button>
        <button @click="memoryOpen = true; sidebar = false"><Icon name="book" :size="18"/>显式偏好</button>
      </div>
      <div class="nav-caption">
        最近对话<span>{{ sessions.length }}</span>
      </div>
      <nav class="session-list">
        <button
          v-for="session in [...sessions].reverse()"
          :key="session.id"
          class="session-item"
          :class="{ selected: selectedId === session.id }"
          :aria-current="selectedId === session.id ? 'page' : undefined"
          @click="chooseSession(session.id)"
        >
          <Icon name="chat" :size="17" /><span>{{ session.title }}</span
          ><span
            v-if="session.turns.at(-1)?.result?.state === 'RUNNING'"
            class="tiny-dot"
          ></span>
        </button>
      </nav>
      <div class="sidebar-note">
        <Icon name="info" :size="16" />
        <p>模型由服务端管理。<br />刷新后配置令牌可恢复任务。</p>
      </div>
      <button class="sidebar-account" @click="openAuth">
        <span class="account-avatar"><Icon name="key" :size="18" /></span
        ><span
          >{{ authenticated ? "访问令牌已配置" : "配置访问令牌"
          }}<small>{{
            authenticated ? "仅当前页面有效" : "连接本地 Supervisor"
          }}</small></span
        ><Icon name="chevron" :size="15" />
      </button>
    </aside>
    <main class="main-panel">
      <header class="topbar">
        <div class="topbar-title">
          <button
            class="icon-button mobile-only"
            aria-label="打开会话导航"
            @click="sidebar = true"
          >
            <Icon name="menu" /></button
          ><span class="product-name">Supervisor<span class="header-divider">/</span><small>{{ current?.turns.length ? current.title : "新对话" }}</small></span
          ><span class="environment-tag"
            ><span class="tiny-dot"></span>示例数据</span
          >
        </div>
        <div class="topbar-actions">
          <button class="icon-button acceptance-shortcut" aria-label="打开平台治理" @click="governanceOpen = true"><Icon name="settings" :size="19"/></button>
          <button class="icon-button acceptance-shortcut" aria-label="打开43项验收场景" @click="acceptanceOpen = true"><Icon name="clipboard" :size="19"/></button>
          <label class="mode-picker"
            ><span class="sr-only">调用模式</span
            ><select v-model="mode" :disabled="!!occupied || !!needsInput">
              <option value="async">异步模式</option>
              <option value="sync">同步模式</option>
            </select></label
          ><button ref="authTrigger" class="auth-button" @click="openAuth">
            <Icon :name="authenticated ? 'check' : 'key'" :size="16" />{{
              authenticated ? "令牌已配置" : "配置令牌"
            }}
          </button>
        </div>
      </header>
      <div v-if="storageWarning" class="storage-warning" role="alert">
        {{ storageWarning }}
      </div>
      <section
        ref="messages"
        class="messages"
        :class="{ empty: !current?.turns.length }"
        aria-label="对话消息"
      >
        <div v-if="!current?.turns.length" class="welcome">
          <span class="welcome-eyebrow"
            ><span></span>SUPERVISOR WORKSPACE</span
          >
          <h1>今天，想解决什么？</h1>
          <p class="welcome-subtitle">说出你的目标，一起查询、规划、核对每一步。</p>
          <div class="suggestions">
            <button
              v-for="example in examples"
              :key="example.title"
              class="suggestion"
              @click="chooseExample(example.text)"
            >
              <span class="suggestion-icon"
                ><Icon :name="example.icon" :size="21" /></span
              ><strong>{{ example.title }}</strong
              ><span>{{ example.note }}</span
              ><Icon name="chevron" :size="15" />
            </button>
          </div>
          <p class="welcome-note">
            天气与车票均使用本地示例数据，适合体验与验收。
          </p>
        </div>
        <div v-else class="conversation">
          <MessageCard
            v-for="turn in current.turns"
            :key="turn.key"
            :turn="turn"
          />
        </div>
      </section>
      <div class="composer-region">
        <div v-if="caseSessions[selectedId]" class="case-context"><Icon name="clipboard" :size="15"/>正在验收 {{ caseSessions[selectedId] }}<button @click="acceptanceOpen = true">查看步骤与证据</button></div>
        <div v-if="cancellable" class="run-toolbar">
          <span role="status"
            ><span class="thinking-dot" v-if="running"></span
            >{{
              needsInput
                ? "当前任务正在等待你的补充"
                : "任务正在执行，结果会自动更新"
            }}</span
          >
          <div class="run-actions">
            <button class="text-button" :disabled="pending?.busy" @click="openAction('MESSAGE')">补充说明</button>
            <button
              class="text-button"
              :disabled="pending?.busy"
              @click="openAction('SWITCH')"
            >
              <Icon name="switch" :size="15" />切换任务</button
            ><button
              class="text-button stop-button"
              :disabled="pending?.busy"
              @click="pending && instruct(pending, 'CANCEL')"
            >
              <Icon name="stop" :size="14" />{{
                pending?.busy ? "处理中…" : "停止"
              }}
            </button>
          </div>
        </div>
        <div
          v-else-if="pending?.phase === 'submitting' && mode === 'sync'"
          class="composer-hint"
        >
          同步等待中，任务号返回后可操作；异步模式可在运行中停止。
        </div>
        <form
          class="composer"
          :class="{ invalid: validation || draft.length > 2000 }"
          @submit.prevent="send"
        >
          <label class="sr-only" for="question">{{
            needsInput ? "补充回答" : "输入问题"
          }}</label>
          <textarea
            id="question"
            ref="textarea"
            v-model="draft"
            :placeholder="
              needsInput ? '回答上方问题，继续当前任务…' : '向 Supervisor 提问…'
            "
            rows="1"
            :aria-invalid="!!validation || draft.length > 2000"
            aria-describedby="input-help"
            @keydown="keyboard"
            @compositionstart="composing = true"
            @compositionend="composing = false"
          ></textarea>
          <details class="hint-control">
            <summary>受控触发（验收用，可选）</summary>
            <input
              v-model="acceptanceHint"
              type="text"
              maxlength="40"
              placeholder="例如 BOOK_TICKET、SPAWN_CHILD、UNKNOWN_TOOL_1"
              aria-label="受控验收提示"
            />
            <small>
              仅当实例开启受控触发且提示命中白名单时生效；该任务详情会标注
              triggerMode=CONTROLLED。
            </small>
          </details>
          <div class="composer-bottom">
            <span class="composer-context"
              ><Icon :name="needsInput ? 'chat' : 'compass'" :size="15" />{{
                needsInput ? "补充当前任务" : "天气 · 车票 · 行程"
              }}</span
            >
            <div class="composer-controls">
              <span class="char-count" :class="{ over: draft.length > 2000 }"
                >{{ draft.length }}<span> / 2000</span></span
              ><button
                class="send-button"
                type="submit"
                :disabled="(!!occupied && !needsInput) || !!pending?.busy"
                :aria-label="needsInput ? '发送补充回答' : '发送问题'"
              >
                <Icon name="arrow" :size="20" />
              </button>
            </div>
          </div>
        </form>
        <p
          id="input-help"
          class="input-help"
          :class="{ 'validation-error': validation || draft.length > 2000 }"
          role="status"
        >
          {{
            validation ||
            (draft.length > 2000
              ? "问题最多 2000 字，请缩短后再发送。"
              : "Enter 发送 · Shift + Enter 换行 · 天气与车票为示例数据")
          }}
        </p>
      </div>
    </main>
    <AcceptancePanel v-if="acceptanceOpen" :active-turn="pending" @close="acceptanceOpen = false" @prepare="prepareCase"/>
    <GovernancePanel v-if="governanceOpen" @close="governanceOpen = false"/>
    <MemoryPanel v-if="memoryOpen" @close="memoryOpen = false"/>
    <div v-if="authOpen" class="modal-scrim" @click.self="closeAuth">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="auth-title"
        @keydown="trapFocus"
        @keydown.esc="closeAuth"
      >
        <button
          class="icon-button modal-close"
          aria-label="关闭认证设置"
          @click="closeAuth"
        >
          <Icon name="close" /></button
        ><span class="modal-icon"><Icon name="key" :size="24" /></span>
        <h2 id="auth-title">连接你的 Supervisor</h2>
        <p>
          输入服务启动时配置的访问令牌。它只保存在当前页面内存中，刷新后需要重新输入。
        </p>
        <form @submit.prevent="saveAuth">
          <label for="access-token">访问令牌</label
          ><input
            id="access-token"
            ref="tokenInput"
            v-model="secret"
            type="password"
            autocomplete="off"
            spellcheck="false"
            placeholder="输入访问令牌"
          />
          <p v-if="authError" class="validation-error" role="alert">
            {{ authError }}
          </p>
          <div class="auth-privacy">
            <Icon name="check" :size="15" />不写入本地存储、日志或源码
          </div>
          <button type="submit" class="primary-button">
            保存并连接<Icon name="chevron" :size="16" />
          </button>
        </form>
        <button
          v-if="authenticated"
          class="text-button clear-token"
          @click="clearAuth"
        >
          <Icon name="logout" :size="16" />清除当前令牌
        </button>
      </section>
    </div>
    <div v-if="switchOpen" class="modal-scrim" @click.self="switchOpen = false">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="switch-title"
        @keydown="trapFocus"
        @keydown.esc="switchOpen = false"
      >
        <button
          class="icon-button modal-close"
          aria-label="关闭任务指令"
          @click="switchOpen = false"
        >
          <Icon name="close" />
        </button>
        <h2 id="switch-title">{{ actionTitles[actionType] }}</h2>
        <p>{{ actionNotes[actionType] }}</p>
        <form @submit.prevent="applySwitch">
          <label for="switch-question">指令内容</label
          ><textarea
            id="switch-question"
            ref="switchInput"
            v-model="switchDraft"
            rows="4"
            placeholder="你现在想查询什么？"
          ></textarea>
          <p v-if="switchError" class="validation-error" role="alert">
            {{ switchError }}
          </p>
          <button
            class="primary-button"
            :disabled="actionTurn?.busy"
            type="submit"
          >
            {{ actionTurn?.busy ? "正在提交…" : "确认发送指令"
            }}<Icon name="switch" :size="16" />
          </button>
        </form>
      </section>
    </div>
  </div>
</template>
