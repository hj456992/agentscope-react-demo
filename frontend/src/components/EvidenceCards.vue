<script setup lang="ts">
import type { Evidence } from "../types";
import { labels, readable, toolName } from "../presentation";
import Icon from "./Icon.vue";
defineProps<{ evidence: Evidence[] }>();
function value(data: Record<string, unknown>, key: string, suffix = "") {
  const item = data[key];
  return typeof item === "string" || typeof item === "number"
    ? `${readable(String(item))}${suffix}`
    : "未提供";
}
function record(input: unknown): Record<string, unknown> {
  return input && typeof input === "object" && !Array.isArray(input)
    ? (input as Record<string, unknown>)
    : {};
}
function rows(input: unknown): Record<string, unknown>[] {
  return Array.isArray(input)
    ? input.filter((item) => item && typeof item === "object").map(record)
    : [];
}
</script>
<template>
  <div class="evidence-list">
    <section
      v-for="(item, index) in evidence"
      :key="index"
      class="evidence-card"
    >
      <div class="evidence-heading">
        <span class="evidence-icon"
          ><Icon
            :name="
              item.tool === 'query_weather'
                ? 'sun'
                : item.tool === 'query_trains'
                  ? 'train'
                  : 'book'
            "
            :size="18" /></span
        ><strong>{{ toolName(item.tool) }}</strong
        ><span class="evidence-state">{{
          labels[item.status] || "查询记录"
        }}</span>
      </div>
      <template v-if="item.tool === 'query_weather'">
        <div class="weather-result">
          <div>
            <strong>{{ value(item.data, "city") }}</strong
            ><span>{{ value(item.data, "date") }}</span>
          </div>
          <b v-if="Object.keys(record(item.data.weather)).length"
            >{{ value(record(item.data.weather), "temperature", "°")
            }}<small>{{
              value(record(item.data.weather), "condition")
            }}</small></b
          >
        </div>
      </template>
      <template v-else-if="item.tool === 'query_trains'">
        <div class="route">
          <strong>{{ value(item.data, "from") }}</strong
          ><span>→</span><strong>{{ value(item.data, "to") }}</strong
          ><small>{{ value(item.data, "date") }}</small>
        </div>
        <div
          v-for="(train, i) in rows(item.data.trains)"
          :key="i"
          class="train-result"
        >
          <div class="train-timing">
            <b>{{ value(train, "departure") }}</b
            ><span>{{ value(train, "durationMinutes", " 分钟") }}</span
            ><b>{{ value(train, "arrival") }}</b>
          </div>
          <div class="train-meta">
            <span
              >{{ value(train, "number") }} · {{ value(train, "seat") }}</span
            ><strong>¥{{ value(train, "price") }}</strong
            ><span>余 {{ value(train, "availableSeats") }} 张</span>
          </div>
          <small class="muted">{{ value(train, "note") }}</small>
        </div>
      </template>
      <p v-if="item.status !== 'SUCCESS'" class="evidence-notice">
        {{
          readable(item.data.suggestion) ||
          readable(item.data.message) ||
          (item.status === "NO_DATA"
            ? "暂无演示数据。"
            : "查询条件需要调整，请查看上方提示。")
        }}
      </p>
      <template
        v-if="item.tool !== 'query_weather' && item.tool !== 'query_trains'"
        ><p>
          {{
            readable(item.data.message) ||
            readable(item.data.text) ||
            readable(item.data.quote) ||
            "查询记录已返回。"
          }}
        </p></template
      >
      <div class="evidence-source">
        来源 {{ item.source
        }}<span v-if="item.source.startsWith('demo')">本地演示数据</span>
        <span v-if="item.data.trustLevel"> · 信任级别 {{ item.data.trustLevel }}（仅资料，不提供权限）</span>
      </div>
    </section>
  </div>
</template>
