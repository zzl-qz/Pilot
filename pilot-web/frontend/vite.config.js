import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

/**
 * 前端工程配置。
 *
 * 唯一正规运行方式：前端由后端 Maven 构建一并带起，不独立启动 dev server。
 * 在 pilot-server 模块执行：
 *     mvn -pl pilot-server spring-boot:run        # 开发：自动 npm install + build，同源直连 /api
 *     mvn package                                 # 打包：前端产物随 jar 一起发布
 * 纯后端迭代可用 -DskipFrontend=true 跳过前端构建（见 pilot-server/pom.xml）。
 *
 * dev:ui 仅用于纯前端热更新调试（npm run dev:ui → http://localhost:5173 代理到 9999），
 * 它不是运行整个应用的正常方式，请勿用它来启动项目。
 *
 * 构建产物直接输出到 Spring Boot 静态目录（不清空，保留 vendor/ 等旧资源），
 * index.html 覆盖为 React 入口 —— 后端无需任何改动。
 */
export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:9999', changeOrigin: true }
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: false,
    assetsDir: 'assets',
    rollupOptions: {
      input: fileURLToPath(new URL('./index.html', import.meta.url))
    }
  }
});
