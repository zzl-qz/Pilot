import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './components/App.jsx';
import { setMarkdownNotifier, setMarkdownContext } from './lib/markdown-render.js';
import './styles.css';
import './shell-vanilla.css';
import './styles-addendum.css';

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

createRoot(document.getElementById('root')).render(<App />);
