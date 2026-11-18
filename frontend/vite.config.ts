import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, strictPort: true, proxy: { '/api': { target: process.env.BACKEND_URL || 'http://127.0.0.1:8080', changeOrigin: true } } },
  test: { environment: 'jsdom', clearMocks: true },
})
