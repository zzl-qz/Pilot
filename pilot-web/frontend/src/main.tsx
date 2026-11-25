import { createRoot } from 'react-dom/client';
import App from './components/App.jsx';
import { useStore } from './store.js';
import { setMarkdownNotifier, setMarkdownContext } from './lib/content/markdown-render.js';
import './styles.css';
import './shell-vanilla.css';
import './styles-addendum.css';
import './workbench.css';

setMarkdownNotifier((text) => {
  useStore.getState().showNotice(text);
});

setMarkdownContext({
  getWorkspace: () => useStore.getState().workspace,
  openFile: (path, line) => useStore.getState().openPreview(path, line)
});

const root = document.getElementById('root');
if (!root) throw new Error('找不到应用挂载节点');
createRoot(root).render(<App />);
