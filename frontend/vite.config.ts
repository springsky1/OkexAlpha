import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/* 开发期：vite dev(5173) 代理到本机 Spring Boot(18080)，后端零改动 */
const BACKEND = 'http://127.0.0.1:18080'

export default defineConfig({
  plugins: [vue()],
  base: '/',
  server: {
    port: 5173,
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/actuator': { target: BACKEND, changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    emptyOutDir: true,
    target: 'es2022'
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['tests/**/*.test.ts', 'src/**/*.test.ts']
  }
})
