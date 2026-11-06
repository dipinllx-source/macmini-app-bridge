import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时把平台 API 代理到本地 mmab-server（127.0.0.1:8443）。注意：代理会改变 Origin，写接口会被服务端拒绝；
// 联调请用 mmab-server 直接提供 dist/（mmab.web.console-static-dir）。
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
