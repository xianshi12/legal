import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    /** 5173 被占用时自动改用 5174…，避免 dev 直接失败 */
    strictPort: false,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        /** 避免开发时代理先于 axios 断开长耗时接口（如合同审查 LLM） */
        proxyTimeout: 180000,
      },
    },
  },
})
