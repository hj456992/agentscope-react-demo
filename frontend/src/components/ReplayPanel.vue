<script setup lang="ts">
import { computed, ref } from 'vue';
import { probeReplay } from '../store';
import type { Turn, InstructionPayload } from '../types';
const props = defineProps<{turn:Turn}>();
const kind = ref<'request'|'request-conflict'|'instruction'|'instruction-conflict'|'stale'>('request');
const changed = ref('');
const isInstruction = computed(() => kind.value.startsWith('instruction') || kind.value === 'stale');
const conflict = computed(() => kind.value.endsWith('conflict'));
const validation = ref('');
const staleId = ref(crypto.randomUUID());
const staleRequest = computed<InstructionPayload>(() => ({
  runId: props.turn.runId || '', instructionId: staleId.value,
  expectedRevision: (props.turn.result?.revision ?? 0) - 1,
  action: 'MESSAGE', message: '补充：仍按原出发城市查询。',
}));
async function execute() {
  validation.value = '';
  if (conflict.value && (!changed.value.trim() || changed.value.length > 2000)) {
    validation.value = '请输入1–2000字的不同内容。';
    return;
  }
  const original = isInstruction.value ? props.turn.lastInstruction?.message : props.turn.payload.question;
  if (conflict.value && changed.value.trim() === original) {
    validation.value = '内容与原请求相同，请改为不同内容后再检查冲突。';
    return;
  }
  await probeReplay(props.turn, kind.value, changed.value, kind.value === 'stale' ? staleRequest.value : undefined);
  if (kind.value === 'stale') {
    staleId.value = crypto.randomUUID();
  }
}
</script>
<template>
  <details class="replay-panel">
    <summary>幂等与版本验收操作</summary>
    <p class="muted">只重放当前身份已读取的任务。使用原编号，记录真实响应；不会自动判通过。</p>
    <form @submit.prevent="execute">
      <label>验收动作<select v-model="kind" :disabled="turn.busy" aria-label="重放验收动作"><option value="request">重放原对话请求</option><option value="request-conflict">对话同号异参</option><option value="instruction">重放上一条指令</option><option value="instruction-conflict">指令同号异参</option><option value="stale">使用旧版本补充</option></select></label>
      <dl class="replay-preview"><dt>原编号</dt><dd>{{ kind === 'stale' ? staleRequest.instructionId : isInstruction ? turn.lastInstruction?.instructionId || '尚无指令记录' : turn.payload.requestId }}</dd><dt>原内容</dt><dd>{{ kind === 'stale' ? staleRequest.message : isInstruction ? turn.lastInstruction?.message || '尚无指令内容' : turn.payload.question }}</dd><template v-if="isInstruction"><dt>请求预期版本</dt><dd>{{ kind === 'stale' ? staleRequest.expectedRevision : turn.lastInstruction?.expectedRevision }}</dd><dt>当前版本</dt><dd>{{ turn.result?.revision }}</dd></template></dl>
      <label v-if="conflict">更改后的内容<textarea v-model="changed" rows="2" aria-label="冲突检查的新内容" placeholder="保持原编号，仅更改内容"/></label>
      <p v-if="kind === 'stale' && (turn.result?.revision ?? 0) <= 1" class="muted">尚无更早版本，请先实际补充或修改任务。</p>
      <p v-if="validation" class="validation-error" role="alert">{{ validation }}</p>
      <button class="secondary-button" type="submit" :disabled="turn.busy || !turn.result || !turn.visible || (kind === 'stale' && (turn.result?.revision ?? 0) <= 1) || (isInstruction && !turn.lastInstruction && kind !== 'stale')">{{ turn.busy ? '正在核对…' : '确认发送验收请求' }}</button>
    </form>
    <ol class="probe-results" v-if="turn.probes.length">
      <li v-for="(probe,index) in turn.probes" :key="index"><strong>HTTP {{ probe.httpStatus ?? '连接中断' }}</strong><time>{{ probe.time }}</time><p>{{ probe.outcome }}</p><details><summary>查看本次请求字段</summary><dl><template v-for="(value,key) in probe.request" :key="key"><dt>{{ key }}</dt><dd>{{ key === 'history' ? '空历史，由服务端装配' : value }}</dd></template></dl></details></li>
    </ol>
  </details>
</template>
