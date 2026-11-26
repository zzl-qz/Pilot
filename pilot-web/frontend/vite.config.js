import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

export function isEntryStatic(id, getModuleInfo, cache, visiting = new Set()) {
  if (cache.has(id)) return cache.get(id);
  if (visiting.has(id)) return false;
  visiting.add(id);
  const info = getModuleInfo(id);
  let staticHit = Boolean(info?.isEntry);
  if (!staticHit && info) {
    for (const importer of info.importers) {
      if (isEntryStatic(importer, getModuleInfo, cache, visiting)) {
        staticHit = true;
        break;
      }
    }
  }
  visiting.delete(id);
  if (staticHit || visiting.size === 0) cache.set(id, staticHit);
  return staticHit;
}

/**
 * 前端工程配置。
 *
 * 正常启动由 Maven 或 IDE 直接运行后端时准备前端，同源直连 /api。
 * 在 pilot-server 模块执行：
 *     mvn -pl pilot-server spring-boot:run        # 开发：自动检查依赖，按需构建前端
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
  resolve: {
    alias: [
      { find: /^pdfjs-dist$/, replacement: 'pdfjs-dist/build/pdf.min.mjs' }
    ]
  },
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
      input: fileURLToPath(new URL('./index.html', import.meta.url)),
      output: {
        manualChunks(id, { getModuleInfo }) {
          if (!id.includes('node_modules')) return;
          if (/[\\/](react|react-dom|scheduler)[\\/]/.test(id)) return 'vendor-react';
          if (/[\\/](zustand|immer|dompurify|use-stick-to-bottom)[\\/]/.test(id)) return 'vendor-state';
          if (/[\\/](lucide-react)[\\/]/.test(id)) return 'vendor-icons';
          if (/[\\/]@astryxdesign[\\/]theme-neutral[\\/]/.test(id)) return 'vendor-theme';
          if (/[\\/]@stylexjs[\\/]/.test(id)) return 'vendor-stylex';
          if (/[\\/](@react-aria|@react-stately|@react-types|@internationalized)[\\/]/.test(id)) return 'vendor-aria';
          if (/[\\/]@astryxdesign[\\/]core[\\/]|[\\/](@formatjs|intl-messageformat)[\\/]/.test(id)) {
            const cache = manualChunkCache.get(getModuleInfo) ?? new Map();
            manualChunkCache.set(getModuleInfo, cache);
            return isEntryStatic(id, getModuleInfo, cache) ? 'vendor-ui' : 'vendor-ui-deferred';
          }
        }
      }
    }
  }
});

const manualChunkCache = new WeakMap();
