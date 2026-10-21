import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    watch: {
      // 编辑器/工具保存时先写临时目录再改名，Windows 上 chokidar 会去 watch
      // 那个瞬间存在的 *.tmpdir 并抛 EBUSY，直接把 dev server 打死（见过两次）
      ignored: ['**/*.tmpdir/**', '**/*.tmpdir', '**/.~*']
    },
    // changeOrigin: false —— 让 Spring 看到浏览器侧的 Host（127.0.0.1:5173），
    // OAuth 的 redirect_uri 才会指回 5173，而不是没有前端静态文件的 8080。
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/oauth': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      // 只代理 Spring 的 OAuth 回调（/login/oauth2/code/gitlab），不要整段 '/login'：
      // 那会把 SPA 的登录路由一起吞掉，未登录跳转 /login 时直接拿到后端 404。
      // 生产 nginx 用的是 `location /login/`（带斜杠），同样是只放行回调。
      '/login/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      '/actuator': { target: 'http://127.0.0.1:8080', changeOrigin: false }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
