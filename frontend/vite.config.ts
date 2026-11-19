import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: "src/components.d.ts",
      resolvers: [
        ElementPlusResolver({
          importStyle: process.env.VITEST ? false : "css",
        }),
      ],
    }),
  ],
  // Lazy routes receive injected component imports after Vite's initial scan.
  // Prebundle these paths up front so the first navigation does not reload the page.
  optimizeDeps: {
    include: [
      "element-plus/es",
      ...[
        "base",
        "alert",
        "button",
        "checkbox",
        "checkbox-group",
        "config-provider",
        "dialog",
        "form",
        "form-item",
        "input",
        "pagination",
        "popconfirm",
        "skeleton",
        "table",
        "table-column",
        "tag",
      ].map((name) => `element-plus/es/components/${name}/style/css`),
    ],
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.BACKEND_URL || "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    clearMocks: true,
    exclude: ["e2e/**", "node_modules/**"],
  },
});
