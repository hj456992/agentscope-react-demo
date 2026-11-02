<script setup lang="ts">
import type { Trace } from "../types";
import { readable } from "../presentation";
defineProps<{ traces: Trace[] }>();
</script>
<template>
  <div v-if="traces.length" class="acceptance-http-list">
    <div v-for="(trace, index) in traces" :key="index" class="acceptance-http-row">
      <time>{{ readable(trace.time) }}</time>
      <strong :class="{ 'is-error': trace.status === null || trace.status >= 400 }">{{ trace.status ?? '连接中断' }}</strong>
      <span class="acceptance-http-endpoint">{{ /^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)$/.test(trace.method) ? trace.method : '请求' }} {{ readable(trace.path).replace(/\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b/g, '运行策略提示') }}</span>
      <span>{{ trace.ms }} 毫秒</span>
    </div>
  </div>
  <p v-else class="acceptance-http-empty">此副本没有HTTP追踪记录。</p>
</template>
<style scoped>
.acceptance-http-list{border:1px solid #e2e8dd;border-radius:7px;overflow:hidden;margin:10px 0}.acceptance-http-row{display:grid;grid-template-columns:74px 54px minmax(0,1fr) auto;gap:8px;padding:9px 10px;font-size:10px;align-items:start;border-bottom:1px solid #edf0e9;color:#7b8872}.acceptance-http-row:last-child{border-bottom:0}.acceptance-http-row strong{color:#4a7959}.acceptance-http-row strong.is-error{color:#a26355}.acceptance-http-endpoint{overflow-wrap:anywhere;color:#52644b}.acceptance-http-empty{font-size:11px;color:#8a967f}@media(max-width:600px){.acceptance-http-row{grid-template-columns:60px 50px minmax(0,1fr)}.acceptance-http-row>span:last-child{grid-column:3}}
</style>
