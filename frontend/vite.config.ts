import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: false,
    cssCodeSplit: false,
    rolldownOptions: {
      output: {
        entryFileNames: "assets/supervisor.js",
        assetFileNames: "assets/supervisor.[ext]",
        codeSplitting: false,
      },
    },
  },
});
