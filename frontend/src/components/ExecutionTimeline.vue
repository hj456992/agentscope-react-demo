<script setup lang="ts">
import { computed } from 'vue';
import { readable, agentName } from '../presentation';
import Icon from './Icon.vue';
const props = defineProps<{ events: string[]; running?: boolean }>();
const entries = computed(() => props.events.map((event, index) => {
  const text = readable(event).replace(/\{[^\n]*\}/g, '（参数内容已省略）').replace(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g, '运行策略提示');
  const warning = /循环|纠错|告警|拒绝|上限|失败|LOOP|限流/.test(event);
  const type = /压缩|摘要|召回/.test(event) ? '上下文' : /计划|门槛|协作/.test(event) ? '协作' : /工具|query_|search_/.test(event) ? '工具' : '运行';
  return { index, text: text.replace(/weather_agent|train_agent|itinerary_agent|supervisor/g, name => agentName(name)), warning, type };
}));
</script>
<template>
  <details class="execution-timeline">
    <summary><Icon name="activity" :size="16"/><span>执行过程</span><span class="event-count">{{ entries.length }} 条记录</span><Icon name="chevron" :size="13"/></summary>
    <div class="timeline-body">
      <p class="timeline-note">来自任务实际返回的事件，按发生顺序展示。</p>
      <ol v-if="entries.length">
        <li v-for="entry in entries" :key="entry.index" :class="{warning:entry.warning}"><span class="event-number">{{ entry.index + 1 }}</span><div><small>{{ entry.type }}</small><p>{{ entry.text }}</p></div></li>
      </ol>
      <p v-else class="muted">{{ running ? '任务已受理，等待第一条执行记录。' : '本轮没有返回可展示的执行事件。' }}</p>
      <p class="timeline-note">参与助手表示曾参与；事件不代表完整调用图、推理内容或已执行计划。</p>
    </div>
  </details>
</template>
