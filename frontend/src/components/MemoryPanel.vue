<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import Icon from "./Icon.vue";
import { authenticated, credentialEpoch } from "../store";
import { departureCities, formatMemoryTime, memoryKeys, memoryLabels, seatOptions, useMemoryPanel } from "../memory";
import type { MemoryItem } from "../memory";

const emit = defineEmits<{ close: [] }>();
const panel = ref<HTMLDialogElement>();
const closeButton = ref<HTMLButtonElement>();
const deleteButton = ref<HTMLButtonElement>();
const confirmation = ref<HTMLElement>();
const pendingDelete = ref<MemoryItem | null>(null);
const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
const { activeItems, nextCursor, loading, loaded, busyKey, pending, error, notice, drafts,
  currentItem, known, hasExpired, touch, read, save, remove } = useMemoryPanel();

function close() {
  emit("close");
}
function keyboard(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    if (pendingDelete.value && !pending.value) {
      pendingDelete.value = null;
    } else {
      close();
    }
    return;
  }
  if (event.key !== "Tab" || !panel.value) {
    return;
  }
  const focusRoot = pendingDelete.value ? confirmation.value : panel.value;
  const targets = Array.from((focusRoot ?? panel.value).querySelectorAll<HTMLElement>(
    'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]',
  )).filter(element => element.getClientRects().length > 0);
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
async function askDelete(item: MemoryItem | undefined) {
  if (!item || pending.value) {
    return;
  }
  pendingDelete.value = { ...item };
  await nextTick();
  deleteButton.value?.focus();
}
async function confirmDelete() {
  const item = pendingDelete.value;
  if (!item || pending.value) {
    return;
  }
  const epoch = credentialEpoch.value;
  await remove(item);
  if (epoch !== credentialEpoch.value) {
    return;
  }
  pendingDelete.value = null;
  await nextTick();
  closeButton.value?.focus();
}
watch(credentialEpoch, () => { pendingDelete.value = null; }, { flush: "sync" });
watch(loaded, value => {
  if (!value) {
    pendingDelete.value = null;
  }
}, { flush: "sync" });
onMounted(() => {
  panel.value?.showModal();
  closeButton.value?.focus();
  if (authenticated.value) {
    void read();
  }
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
    <dialog ref="panel" class="memory-panel" aria-labelledby="memory-title" aria-describedby="memory-description"
      @cancel.prevent="close" @keydown="keyboard" @click="event => { if (event.target === panel) { close(); } }">
      <div class="memory-shell">
        <header class="memory-header" :inert="Boolean(pendingDelete)">
          <div class="memory-heading">
            <span class="memory-mark"><Icon name="settings" :size="22" /></span>
            <div><p class="memory-eyebrow">让下一次对话更顺手</p><h2 id="memory-title">我的偏好</h2></div>
          </div>
          <button ref="closeButton" type="button" class="memory-icon-button" aria-label="关闭偏好面板" @click="close"><Icon name="close" /></button>
        </header>
        <div class="memory-body" :inert="Boolean(pendingDelete)">
          <p id="memory-description" class="memory-description">保存常用的出发城市和席别。只有你主动保存的内容才会成为长期偏好，聊天内容不会自动写入。</p>
          <section v-if="!authenticated" class="memory-empty" aria-live="polite">
            <span class="memory-empty-icon"><Icon name="key" :size="26" /></span>
            <h3>先连接你的身份</h3><p>请关闭面板并配置访问令牌，再读取属于当前身份的偏好。</p>
          </section>
          <template v-else>
            <div class="memory-toolbar">
              <div><span class="memory-dot"></span> 当前身份 · {{ loaded ? `${activeItems.length} 项有效偏好` : '尚未读取' }}</div>
              <button type="button" class="memory-text-button" :disabled="pending" @click="read()"><Icon name="refresh" :size="15" />{{ loading ? '读取中…' : '重新读取' }}</button>
            </div>
            <p v-if="error" class="memory-feedback memory-error" role="alert">{{ error }}</p>
            <p v-else-if="notice" class="memory-feedback memory-success" role="status">{{ notice }}</p>
            <div v-if="loading && !loaded" class="memory-empty" role="status"><span class="memory-loading"></span><h3>正在读取偏好</h3><p>以服务端当前有效值为准。</p></div>
            <div v-else-if="!loaded" class="memory-empty"><Icon name="book" :size="28" /><h3>等待读取偏好</h3><p>读取成功后即可查看和编辑。</p><button type="button" class="memory-button memory-primary" :disabled="pending" @click="read()">读取偏好</button></div>
            <template v-else>
              <p v-if="!activeItems.length" class="memory-empty-hint"><Icon name="info" :size="17" />{{ nextCursor ? '当前页没有有效偏好，请继续读取下一页。' : '还没有有效偏好。选择常用城市或席别后，主动保存即可。' }}</p>
              <article v-for="key in memoryKeys" :key="key" class="memory-card" :aria-labelledby="`memory-heading-${key}`" :aria-busy="busyKey === key">
                <div class="memory-card-heading">
                  <h3 :id="`memory-heading-${key}`"><Icon :name="key === 'preferredDepartureCity' ? 'compass' : 'train'" :size="19" />{{ memoryLabels[key] }}</h3>
                  <span class="memory-badge" :class="{ 'memory-badge-active': currentItem(key) }">{{ currentItem(key) ? '已保存' : hasExpired(key) ? '已过期' : '未设置' }}</span>
                </div>
                <div v-if="currentItem(key)" class="memory-current">
                  <p class="memory-current-label">当前有效值</p><strong>{{ currentItem(key)?.value }}</strong>
                  <dl><div><dt>版本</dt><dd>{{ currentItem(key)?.version }}</dd></div>
                    <div><dt>来源</dt><dd>{{ currentItem(key)?.source === 'explicit-user-confirmation' ? '用户显式确认' : currentItem(key)?.source }}</dd></div>
                    <div class="memory-expiry"><dt>有效期至</dt><dd>{{ formatMemoryTime(currentItem(key)!.expiresAt) }}</dd></div></dl>
                </div>
                <p v-else class="memory-no-value">{{ hasExpired(key) ? '上次读取的偏好已经过期，请重新读取后再编辑。' : known(key) ? '当前没有有效值。' : '请读取剩余页，以确认是否已有保存的偏好。' }}</p>
                <form class="memory-form" @submit.prevent="save(key)">
                  <label :for="`memory-value-${key}`">{{ currentItem(key) ? '修改为' : '设置为' }}</label>
                  <select :id="`memory-value-${key}`" v-model="drafts[key].value" :disabled="pending || !known(key) || hasExpired(key)" :aria-describedby="drafts[key].error ? `memory-error-${key}` : undefined" @change="touch(key)">
                    <option value="" disabled>{{ key === 'preferredDepartureCity' ? '选择出发城市' : '选择席别' }}</option>
                    <option v-for="value in key === 'preferredDepartureCity' ? departureCities : seatOptions" :key="value" :value="value">{{ value }}</option>
                  </select>
                  <label :for="`memory-expiry-${key}`">有效期 <span>可选 · 当前设备本地时间</span></label>
                  <input :id="`memory-expiry-${key}`" v-model="drafts[key].expiresLocal" type="datetime-local" step="1" :disabled="pending || !known(key) || hasExpired(key)" :aria-describedby="`memory-expiry-help-${key}`" @input="touch(key)" />
                  <p :id="`memory-expiry-help-${key}`" class="memory-field-help">设置未来时间；留空将使用服务端默认有效期。{{ currentItem(key) ? '已有值会显示当前有效期，清空可改用默认值。' : '' }}</p>
                  <p v-if="drafts[key].error" :id="`memory-error-${key}`" class="memory-inline-error" role="alert">{{ drafts[key].error }}</p>
                  <button v-if="drafts[key].conflict" type="button" class="memory-text-button memory-conflict" :disabled="pending" @click="read()"><Icon name="refresh" :size="15" />读取最新版本，保留输入</button>
                  <div class="memory-card-actions">
                    <button type="submit" class="memory-button memory-primary" :disabled="pending || !known(key) || drafts[key].conflict || hasExpired(key) || !drafts[key].value">{{ busyKey === key ? '处理中…' : `保存${memoryLabels[key]}` }}</button>
                    <button v-if="currentItem(key)" type="button" class="memory-button memory-delete" :disabled="pending || drafts[key].conflict" @click="askDelete(currentItem(key))">删除</button>
                  </div>
                </form>
              </article>
              <button v-if="nextCursor" type="button" class="memory-button memory-next" :disabled="pending" @click="read(true)">{{ loading ? '正在读取…' : '读取下一页' }}<Icon name="chevron" :size="16" /></button>
              <p class="memory-footnote">偏好按当前身份隔离。更换令牌会立即清空本面板的数据与草稿；本面板不在浏览器中持久保存记录。</p>
            </template>
          </template>
        </div>
        <section v-if="pendingDelete" ref="confirmation" class="memory-confirm" role="alertdialog" aria-modal="true" aria-labelledby="memory-delete-title" aria-describedby="memory-delete-description">
          <h3 id="memory-delete-title">删除这项偏好？</h3>
          <p id="memory-delete-description">将删除{{ memoryLabels[pendingDelete.key] }}“<strong>{{ pendingDelete.value }}</strong>”（版本 {{ pendingDelete.version }}）。之后的对话不再使用这项已保存的偏好。</p>
          <div><button type="button" class="memory-button" :disabled="pending" @click="pendingDelete = null">保留偏好</button><button ref="deleteButton" type="button" class="memory-button memory-destructive" :disabled="pending" @click="confirmDelete">{{ pending ? '正在删除…' : '确认删除' }}</button></div>
        </section>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.memory-panel { --mp-ink:#202725; --mp-muted:#68716c; --mp-line:#e6eae7; --mp-accent:#087e68; position:fixed; inset:0 0 0 auto; margin:0; border:0; padding:0; width:min(540px,100vw); max-width:none; height:100dvh; max-height:none; background:#fafbf9; color:var(--mp-ink); box-shadow:-16px 0 60px #172d241a; font-family:inherit; font-size:14px; line-height:1.65; }
.memory-panel::backdrop { background:#1328204a; backdrop-filter:blur(2px); }
.memory-shell { display:flex; flex-direction:column; min-height:100%; position:relative; }
.memory-header { display:flex; justify-content:space-between; align-items:center; gap:16px; padding:26px 28px 23px; border-bottom:1px solid var(--mp-line); background:#fff; }
.memory-heading { display:flex; align-items:center; gap:13px; }.memory-mark { display:grid; place-items:center; width:45px; height:45px; border:1px solid #dce7e0; border-radius:14px; color:var(--mp-accent); background:#f2f7f3; }
.memory-eyebrow { margin:0 0 2px; font-size:11px; letter-spacing:.04em; color:var(--mp-muted); }.memory-heading h2 { margin:0; font-size:23px; line-height:1.35; letter-spacing:-.04em; font-weight:650; }
.memory-panel button,.memory-panel input,.memory-panel select { font:inherit; }.memory-panel button { cursor:pointer; }.memory-panel button:disabled { cursor:default; opacity:.45; }.memory-panel :is(button,input,select):focus-visible { outline:3px solid #50b69e; outline-offset:3px; }
.memory-icon-button { border:0; display:grid; place-items:center; width:38px; height:38px; border-radius:50%; color:var(--mp-muted); background:#f4f6f3; }.memory-icon-button:hover { background:#e9eeea; }
.memory-body { padding:25px 28px 32px; }.memory-description { margin:0 0 23px; color:var(--mp-muted); font-size:13px; line-height:1.85; }.memory-toolbar { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:17px; font-size:12px; color:var(--mp-muted); }.memory-dot { display:inline-block; width:6px; height:6px; border-radius:50%; background:var(--mp-accent); margin-right:5px; vertical-align:middle; }
.memory-text-button { display:inline-flex; align-items:center; justify-content:center; gap:5px; border:0; background:transparent; color:var(--mp-accent); padding:7px 3px; font-size:12px!important; white-space:nowrap; }.memory-text-button:hover { text-decoration:underline; }
.memory-feedback { font-size:12px; padding:11px 13px; margin:0 0 16px; border-radius:9px; line-height:1.7; }.memory-error { background:#fff0ec; color:#9b3e2b; }.memory-success { background:#eaf4ee; color:#35624d; }
.memory-empty { text-align:center; padding:45px 18px; border:1px dashed #dce4dd; border-radius:15px; color:var(--mp-muted); }.memory-empty h3 { color:var(--mp-ink); font-weight:600; font-size:17px; margin:14px 0 6px; }.memory-empty p { max-width:310px; margin:0 auto 14px; font-size:13px; }.memory-empty-icon { display:inline-grid; place-items:center; width:54px; height:54px; background:#edf3ed; border-radius:50%; color:var(--mp-accent); }.memory-empty-hint { display:flex; align-items:flex-start; gap:8px; font-size:12px; color:var(--mp-muted); margin:0 0 18px; }.memory-empty-hint svg { flex:none; margin-top:3px; }
.memory-card { padding:21px; background:#fff; border:1px solid var(--mp-line); border-radius:15px; margin:0 0 17px; box-shadow:0 2px 5px #1c312503; }.memory-card-heading { display:flex; justify-content:space-between; align-items:center; gap:12px; }.memory-card-heading h3 { display:flex; align-items:center; gap:8px; font-size:15px; font-weight:600; margin:0; }.memory-card-heading h3 svg { color:#5d7568; }.memory-badge { background:#f2f4f1; color:var(--mp-muted); padding:2px 8px; border-radius:20px; font-size:10px; }.memory-badge-active { color:#35624d; background:#edf5ef; }
.memory-current { background:#f6f8f5; padding:13px 15px; border-radius:9px; margin:17px 0 19px; }.memory-current-label { color:var(--mp-muted); font-size:11px; margin:0; }.memory-current strong { font-size:20px; font-weight:600; }.memory-current dl { margin:9px 0 0; display:grid; grid-template-columns:auto 1fr; gap:5px 20px; font-size:11px; }.memory-current dl div { display:flex; gap:8px; }.memory-current dt { color:var(--mp-muted); white-space:nowrap; }.memory-current dd { margin:0; overflow-wrap:anywhere; }.memory-current .memory-expiry { grid-column:1 / -1; }.memory-no-value { color:var(--mp-muted); font-size:12px; padding:11px 0; margin:6px 0 10px; }
.memory-form { display:flex; flex-direction:column; }.memory-form label { font-size:12px; margin:0 0 6px; color:#48544c; }.memory-form label span { color:var(--mp-muted); font-size:10px; margin-left:4px; }.memory-form input,.memory-form select { width:100%; min-height:42px; box-sizing:border-box; padding:9px 11px; border:1px solid #dce2dc; border-radius:8px; background:#fff; color:var(--mp-ink); font-size:13px; }.memory-form select { margin:0 0 17px; }.memory-form input:disabled,.memory-form select:disabled { background:#f5f6f4; color:#727a74; }.memory-field-help { color:var(--mp-muted); font-size:10px; margin:6px 0 15px; line-height:1.65; }.memory-inline-error { font-size:12px; margin:0 0 13px; line-height:1.75; color:#a13c2e; }.memory-conflict { align-self:flex-start; margin:0 0 9px; }
.memory-card-actions { display:flex; justify-content:space-between; align-items:center; gap:12px; }.memory-button { display:inline-flex; align-items:center; justify-content:center; gap:7px; min-height:39px; border:1px solid #dce2dd; background:#fff; color:#47554b; padding:8px 14px; border-radius:8px; font-size:12px!important; line-height:1.5; }.memory-button:hover { background:#f0f4ef; }.memory-primary { background:#273c31; color:#fff; border-color:#273c31; }.memory-primary:hover { background:#365443; }.memory-delete { border:0; color:#9e5145; background:transparent; padding-right:4px; }.memory-delete:hover { background:#fcf2ef; }.memory-next { width:100%; margin:0 0 12px; }.memory-footnote { color:var(--mp-muted); font-size:11px; line-height:1.8; margin:19px 2px 0; }
.memory-confirm { position:fixed; z-index:2; bottom:20px; right:20px; width:min(458px,calc(100vw - 40px)); box-sizing:border-box; padding:22px; background:#fff; border:1px solid #e4dcd3; border-radius:13px; box-shadow:0 8px 40px #1c2c2838; }.memory-confirm h3 { font-size:17px; margin:0 0 10px; font-weight:600; }.memory-confirm p { font-size:13px; color:#647067; line-height:1.8; margin:0 0 18px; overflow-wrap:anywhere; }.memory-confirm > div { display:flex; justify-content:flex-end; gap:9px; }.memory-destructive { background:#a34031; border-color:#a34031; color:#fff; }.memory-destructive:hover { background:#8d3225; }.memory-loading { width:24px; height:24px; display:inline-block; border:2px solid #dce6dc; border-top-color:var(--mp-accent); border-radius:50%; animation:memory-spin 1s linear infinite; }@keyframes memory-spin { to { transform:rotate(360deg); } }
@media (max-width:600px) { .memory-panel { width:100vw; }.memory-header { padding:21px 20px; }.memory-body { padding:21px 18px 30px; }.memory-card { padding:18px; }.memory-form input,.memory-form select { font-size:16px; }.memory-button { min-height:44px; }.memory-current dl { grid-template-columns:1fr; }.memory-current .memory-expiry { grid-column:auto; }.memory-confirm { bottom:12px; right:12px; width:calc(100vw - 24px); } }
@media (prefers-reduced-motion:reduce) { .memory-loading { animation:none; } }
</style>
