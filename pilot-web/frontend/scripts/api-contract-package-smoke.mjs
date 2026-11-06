// 对真实生产 Jar 调用前端 API 包装器；随机端口、独立数据和工作区，不连接模型。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import './register-typescript.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-api-contract-'));
const directory = join(output, '中文 空格工作区'); await mkdir(directory);
// 与服务端登记目录的写法一致；macOS /var 与 /private/var 以及 Windows 目录别名均由标准接口解析。
const workspace = await realpath(directory);
await writeFile(join(workspace, '中文 文件.txt'), '生产 API 回归');
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const child = spawn(java, ['--enable-native-access=ALL-UNNAMED', '-jar', resolve(root, 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar'),
  '--server.address=127.0.0.1', '--server.port=0', `--pilot.data.dir=${join(output, 'data')}`, `--pilot.agent.workspace=${workspace}`,
  '--pilot.runtime.provision=false', '--pilot.desktop.launch=false', '--pilot.mobile.mode=direct', '--pilot.browser.enabled=false',
  '--pilot.model.capability-discovery.enabled=false', '--pilot.model.api-key=', '--pilot.model.endpoint=http://127.0.0.1:9/v1/chat/completions', '--pilot.security.mode=local'],
{ cwd: output, stdio: ['ignore', 'pipe', 'pipe'] });
let logs = '';
const results = [];
try {
  const base = await new Promise((accept, reject) => {
    const timer = setTimeout(() => reject(Error('隔离 Jar 启动超时，请使用 Java 25 并先完成 Maven 打包')), 30000);
    const capture = chunk => { logs += chunk; const match = logs.match(/Tomcat started on port (\d+)/); if (match) { clearTimeout(timer); accept(`http://127.0.0.1:${match[1]}`); } };
    child.stdout.on('data', capture); child.stderr.on('data', capture);
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('exit', code => { clearTimeout(timer); reject(Error('隔离 Jar 退出：' + code)); });
  });
  const nativeFetch = globalThis.fetch;
  globalThis.window = { fetch: (input, init) => nativeFetch(new URL(input, base), init) };
  const { api, ApiError } = await import('../src/api.js');
  delete globalThis.window;
  assert.equal((await api.health()).status, 200);
  const settings = await api.settings(); assert.ok(Array.isArray(settings.permissionModes)); assert.equal(typeof settings.modelApiKeySet, 'boolean');
  assert.equal((await api.savePermissionMode('READ_ONLY')).permissionMode, 'READ_ONLY'); results.push('设置读取/保存');
  const project = await api.createProject({ name: 'API 回归项目', folders: [workspace] }); assert.ok(project.id);
  const session = await api.createSession({ workspace, projectId: project.id }); assert.equal(session.id, session.sessionId); assert.equal(session.workspace, workspace);
  assert.ok((await api.sessions()).some(row => row.id === session.id));
  assert.equal((await api.renameSession(session.id, '中文 API 会话')).title, '中文 API 会话');
  assert.equal((await api.sessionPreferences(session.id, { pinned: true })).pinned, true);
  assert.equal((await api.archiveSession(session.id, true)).archived, true); await api.archiveSession(session.id, false);
  results.push('项目/会话创建、读取、改名、置顶、归档');
  const tree = await api.workspaceTree(workspace); assert.ok(tree.entries.some(entry => entry.name === '中文 文件.txt'));
  assert.equal((await api.workspaceFile(workspace, '中文 文件.txt')).content, '生产 API 回归');
  assert.ok((await api.workspaceFiles(workspace)).files.includes('中文 文件.txt'));
  assert.ok(Array.isArray(await api.workspaceRoots())); assert.equal((await api.workspaceBrowse(workspace)).path, workspace);
  await assert.rejects(api.workspaceFile(workspace, '../outside'), error => error instanceof ApiError && error.status >= 400); results.push('文件树/文件/中文路径/越界拒绝');
  assert.ok(Array.isArray((await api.history(session.id, { limit: 80 })).events));
  assert.deepEqual(await api.bookmarks(session.id), []); assert.deepEqual((await api.importBookmarks(session.id, [])).bookmarks, []);
  assert.ok(Array.isArray((await api.searchMessages({ q: '无匹配', saved: false, offset: 0 })).items));
  assert.ok(Array.isArray((await api.queue(session.id)).items)); assert.ok(Array.isArray((await api.plan(session.id)).plan));
  assert.equal(typeof (await api.context(session.id)).usedTokens, 'number');
  results.push('历史/书签/搜索/队列/计划/上下文');
  assert.deepEqual(await api.workProducts(session.id), []); assert.deepEqual(await api.skillDrafts(session.id), []);
  assert.ok(Array.isArray((await api.skills(session.id)).available)); assert.ok(Array.isArray((await api.results()).items));
  assert.ok(Array.isArray((await api.usage(7)).daily)); assert.ok((await api.metrics()).metrics);
  assert.equal(typeof (await api.browserStatus(session.id)).enabled, 'boolean');
  await api.diagnostics(session.id); await api.validation(session.id); await api.recovery(); await api.maintenanceStatus();
  results.push('成果/技能/收件箱/用量/诊断/浏览器能力');
  const task = { name: '未来任务', sourceSessionId: session.id, content: '隔离回归，不运行', runAt: new Date(Date.now() + 86400000).toISOString(), timezone: 'UTC', requirements: { constraints: [], acceptance: [] }, fullAccess: false, isolated: false };
  const scheduled = await api.createSchedule(task); assert.ok(scheduled.id);
  assert.equal((await api.controlSchedule(scheduled.id, { action: 'pause' })).state, 'PAUSED');
  await api.controlSchedule(scheduled.id, { action: 'resume' }); assert.equal((await api.controlSchedule(scheduled.id, { action: 'cancel' })).state, 'CANCELLED');
  const template = await api.saveTemplate({ version: 0, name: '测试模板', task }); assert.ok(template.id);
  assert.ok((await api.templates()).some(row => row.id === template.id)); assert.equal(await api.deleteTemplate(template.id, template.version), null);
  results.push('未来定时任务创建/暂停/恢复/取消、模板保存/删除');
  assert.equal(await api.deleteSession(session.id), null); assert.equal(await api.deleteProject(project.id), null);
  await assert.rejects(api.session(session.id), error => error instanceof ApiError && error.status === 404); results.push('空响应删除/404 状态保留');
  await writeFile(join(output, 'report.json'), JSON.stringify({ success: true, platform: process.platform, results }, null, 2));
  console.log('PASS production Jar + frontend API:', results.join('；')); console.log('Evidence:', output);
} finally {
  if (child.exitCode === null) { child.kill(); await new Promise(done => { const timer = setTimeout(() => { child.kill('SIGKILL'); done(); }, 5000); child.once('exit', () => { clearTimeout(timer); done(); }); }); }
  await writeFile(join(output, 'server.log'), logs);
}
