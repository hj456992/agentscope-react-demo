<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 });
const paths: Record<string, string> = {
  activity: "M3 12h4l3-8 4 16 3-8h4",
  clipboard: "M9 4H5v17h14V4h-4M9 2h6v4H9zM8 11h8M8 15h6",
  settings: "M4 7h16M4 17h16M8 4v6m8 4v6",
  download: "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5",
  plus: "M12 5v14M5 12h14",
  arrow: "M12 19V5m-6 6 6-6 6 6",
  menu: "M4 6h16M4 12h16M4 18h16",
  close: "m6 6 12 12M6 18 18 6",
  chevron: "m9 5 7 7-7 7",
  chat: "M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l-2 2v-9.5A8.5 8.5 0 0 1 10.5 4h2a8.5 8.5 0 0 1 8.5 7.5Z",
  key: "M14 10a5 5 0 1 0-4 4l3 3h3v3h4v-4l-6-6Z",
  check: "m5 12 4 4L19 6",
  stop: "M7 7h10v10H7z",
  refresh:
    "M20 7v5h-5M4 17v-5h5M5.2 8a7 7 0 0 1 11.6-3L20 8M4 16l3.2 3A7 7 0 0 0 18.8 16",
  sun: "M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0",
  train:
    "M6 16h12V5c0-2-12-2-12 0v11Zm0-6h12M8 16l-2 4m10-4 2 4M9 13h.01M15 13h.01",
  compass: "m16 8-3 5-5 3 3-5 5-3ZM22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0",
  info: "M12 11v6m0-10h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0",
  switch: "M4 7h15l-4-4m4 14H4l4 4M19 7l-4 4M4 17l4-4",
  copy: "M9 9h12v12H9zM15 5V2H2v13h3",
  logout: "M9 4H4v16h5m5-13 5 5-5 5M9 12h10",
  book: "M12 6c-3-3-7-3-10-1v15c3-2 7-2 10 0 3-2 7-2 10 0V5c-3-2-7-2-10 1Zm0 0v14",
};
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.info" />
  </svg>
</template>
