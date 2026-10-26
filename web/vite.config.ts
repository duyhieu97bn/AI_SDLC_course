import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// Khi chạy `npm run dev`, chuyển tiếp API và media sang stack Docker Compose (Nginx cổng 8443)
const backend = process.env.CAMHUB_DEV_BACKEND ?? 'https://localhost:8443'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true, secure: false },
      '/media': { target: backend, changeOrigin: true, secure: false },
    },
  },
  build: {
    sourcemap: true,
    // Chunk hls.js (~600 kB) chỉ tải khi cần dự phòng HLS, không ảnh hưởng lần tải đầu
    chunkSizeWarningLimit: 700,
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
