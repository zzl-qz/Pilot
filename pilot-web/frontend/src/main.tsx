import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './components/App.jsx';
import { setMarkdownNotifier, setMarkdownContext } from './lib/markdown-render.js';
import './styles.css';
import './shell-vanilla.css';
import './styles-addendum.css';
import './workbench.css';

setMarkdownNotifier((text) => {
  // 延迟注入：避免渲染引擎与 store 循环依赖
  import('./store.js').then(({ useStore }) => useStore.getState().showNotice(text));
});

import('./store.js').then(({ useStore }) => {
  setMarkdownContext({
    getWorkspace: () => useStore.getState().workspace,
    openFile: (path) => useStore.getState().openPreview(path)
  });
});

const root = document.getElementById('root');
if (!root) throw new Error('找不到应用挂载节点');
createRoot(root).render(<App />);
