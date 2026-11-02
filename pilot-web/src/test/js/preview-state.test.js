import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
globalThis.window = { fetch: async () => { throw Error('unexpected network'); }, location: { search: '' } };
const { useStore } = await import('../../../frontend/src/store.js');
const { api } = await import('../../../frontend/src/api.js');
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function reset() {
  useStore.setState({ sessionId: 's1', sessionEpoch: 1, workspace: '/work', _previewRequest: 0, _previewData: null, _previewLoading: false });
  useStore.getState().setUi({ inspectorOpen: false, reviewExpanded: true, previewPath: '' });
}
test('打开文件立即展示预览，最后一次选择优先于慢响应', async () => {
  reset(); const a = deferred(), b = deferred();
  api.file = (_, path) => path === 'a.txt' ? a.promise : b.promise;
  const first = useStore.getState().openPreview('a.txt');
  assert.equal(useStore.getState().ui.inspectorOpen, true);
  assert.equal(useStore.getState().ui.reviewExpanded, false);
  const second = useStore.getState().openPreview('b.txt');
  b.resolve({ content: 'B' }); await second;
  a.resolve({ content: 'A' }); await first;
  assert.equal(useStore.getState()._previewData.content, 'B');
  assert.equal(useStore.getState().ui.previewPath, 'b.txt');
});
test('切换工作区后旧响应与旧错误均不覆盖当前界面', async () => {
  reset(); const request = deferred(); api.file = () => request.promise;
  const operation = useStore.getState().openPreview('a.txt');
  useStore.setState({ workspace: '/other', sessionEpoch: 2, _previewData: { content: 'new' } });
  request.resolve({ content: 'old' }); await operation;
  assert.equal(useStore.getState()._previewData.content, 'new');
  reset(); const failure = deferred(); api.file = () => failure.promise;
  const stale = useStore.getState().openPreview('a.txt');
  useStore.getState().setUi({ notice: 'current' }); useStore.setState({ sessionEpoch: 3 });
  failure.reject(Error('old failure')); await stale;
  assert.equal(useStore.getState().ui.notice, 'current');
});
test('首条消息发送前可以浏览文件且不创建会话', async () => {
  reset(); useStore.setState({ sessionId: null });
  let called;
  api.workspaceFile = async (root, path) => { called = [root, path]; return { content: '草稿工作区' }; };
  await useStore.getState().openPreview('源码/测试 文件.md');
  assert.deepEqual(called, ['/work', '源码/测试 文件.md']);
  assert.equal(useStore.getState().sessionId, null);
  assert.equal(useStore.getState()._previewData.content, '草稿工作区');
});
test('空路径不打开面板，也不清除现有内容', async () => {
  reset(); await useStore.getState().openPreview('  ');
  assert.equal(useStore.getState().ui.inspectorOpen, false);
});

test('切换失败保持原工作区，弹窗调用方可以捕获失败', async () => {
  reset(); api.switchWorkspace = async () => { throw Error('目录已移走'); };
  await assert.rejects(useStore.getState().switchWorkspace('/missing', { throwOnError: true }), /目录已移走/);
  assert.equal(useStore.getState().workspace, '/work');
  await useStore.getState().switchWorkspace('/missing'); // 普通菜单调用不产生未处理的 rejection。
  assert.match(useStore.getState().ui.notice.text, /目录已移走/);
});

test('草稿切换工作区清空旧预览，迟到响应不能恢复旧文件', async () => {
  reset(); useStore.setState({ sessionId: null });
  const pending = deferred(); api.workspaceFile = () => pending.promise;
  const operation = useStore.getState().openPreview('a.txt');
  await useStore.getState().switchWorkspace('/next');
  assert.equal(useStore.getState()._previewLoading, false);
  assert.equal(useStore.getState().ui.previewPath, '');
  pending.resolve({ content: 'old workspace' }); await operation;
  assert.equal(useStore.getState()._previewData, null);
});

test('新任务不会展示上一个对话的预览内容', () => {
  reset(); useStore.setState({ _previewData: { content: 'old' } });
  useStore.getState().setUi({ previewPath: 'old.txt' });
  useStore.getState().startNewSession();
  assert.equal(useStore.getState()._previewData, null);
  assert.equal(useStore.getState().ui.previewPath, '');
});
