import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时把平台 API 代理到本地 mmab-server 骨架（127.0.0.1:8443，无 TLS、无鉴权）。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    proxy: {
      '/__mmab': 'http://127.0.0.1:8443',
    },
  },
  build: {
    chunkSizeWarningLimit: 1500,
  },
})
