import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期：vite dev server 把 /api 反代到本机后端（容器映射在 8080）
// 生产期：nginx 承担同样的反代职责（见 client/nginx.conf），所以前端代码里
// 所有请求都写相对路径 /api/**，两种环境零改动。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
