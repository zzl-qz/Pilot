import test from 'node:test';
import assert from 'node:assert/strict';
import {
  computeToolSpanSeconds,
  extractFileMeta,
  formatProcessDuration,
  parseToolDisplay,
  summarizeActiveTool,
  toolArgsText,
  toolLabel
} from '../src/lib/tool-meta.js';

test('常见工具显示中文名，不直接甩协议名', () => {
  assert.equal(toolLabel('command_status'), '查命令');
  assert.equal(toolLabel('command_logs'), '读日志');
  assert.equal(toolLabel('command_input'), '写输入');
  assert.equal(toolLabel('browser'), '浏览器');
  assert.equal(toolLabel('run_command'), '命令');
  assert.equal(toolLabel('mcp_tools'), 'MCP 工具');
  assert.equal(toolLabel('mcp_call'), 'MCP 调用');
});

test('command_status 参数收成人话，不露 waitSeconds 原文', () => {
  const text = toolArgsText('command_status', 'taskId=cmd_16162ef8abcd waitSeconds=5');
  assert.match(text, /cmd_1616/);
  assert.match(text, /最多等 5 秒/);
  assert.doesNotMatch(text, /waitSeconds/);
  assert.doesNotMatch(text, /taskId=/);
});

test('command_input 只显示任务短 ID，不甩出要写入的正文', () => {
  const text = toolArgsText('command_input', 'taskId=cmd_16162ef8abcd text=y');
  assert.match(text, /cmd_1616/);
  assert.match(text, /写输入/);
  assert.doesNotMatch(text, /taskId=|text=/);
});

test('浏览器 summary 里的 action 译成中文', () => {
  assert.equal(toolArgsText('browser', 'click'), '点击');
  assert.equal(toolArgsText('browser', 'inspect'), '检查');
  assert.match(toolArgsText('browser', 'action=navigate,url=https://example.com/path'), /打开 example.com/);
});

test('formatProcessDuration 与 computeToolSpanSeconds 格式化过程耗时', () => {
  assert.equal(formatProcessDuration(0), '0s');
  assert.equal(formatProcessDuration(45), '45s');
  assert.equal(formatProcessDuration(135), '2m 15s');
  assert.equal(formatProcessDuration(1227), '20m 27s');
  assert.equal(formatProcessDuration(3665), '1h 01m');
  assert.equal(formatProcessDuration(-5), '0s');

  assert.equal(
    computeToolSpanSeconds(
      [
        { startedAt: 10_000, finishedAt: 25_000 },
        { startedAt: 26_000, finishedAt: 145_000 }
      ],
      false
    ),
    135
  );
  assert.equal(computeToolSpanSeconds([{ startedAt: 10_000 }], true, 52_000), 42);
  assert.equal(computeToolSpanSeconds([{ startedAt: 10_000 }], false), null);
  assert.equal(computeToolSpanSeconds([], false), null);
});

test('extractFileMeta 兼容 POSIX 与 Windows 路径分隔符并识别图标类型', () => {
  const win = extractFileMeta('C:\\Users\\dev\\pilot\\PreFlightLocator.java');
  assert.equal(win.fileName, 'PreFlightLocator.java');
  assert.equal(win.kind, 'java');

  const posix = extractFileMeta('docs/design review/tasks.md');
  assert.equal(posix.fileName, 'tasks.md');
  assert.equal(posix.kind, 'markdown');

  const tsx = extractFileMeta('pilot-web/frontend/src/components/Thread.tsx');
  assert.equal(tsx.fileName, 'Thread.tsx');
  assert.equal(tsx.kind, 'typescript');

  const plain = extractFileMeta('Makefile');
  assert.equal(plain.fileName, 'Makefile');
  assert.equal(plain.kind, 'file');
});

test('parseToolDisplay 解析文件行号、编辑增减行数与命令非零退出码', () => {
  const read = parseToolDisplay({
    name: 'read_file',
    args: 'path=pilot-server/src/test/java/GoalExecutionPolicyTest.java',
    body: '355 | public void test() … 显示第 355 到第 415 行，文件共 500 行 …'
  });
  assert.equal(read.kind, 'java');
  assert.equal(read.primary, 'GoalExecutionPolicyTest.java');
  assert.equal(read.lineRange, '#L355-415');
  assert.equal(read.startLine, 355);
  assert.equal(read.filePath, 'pilot-server/src/test/java/GoalExecutionPolicyTest.java');

  const edit = parseToolDisplay({
    name: 'edit_file',
    args: 'path=pilot-server/src/main/java/PreFlightLocator.java',
    body: '已修改 pilot-server/src/main/java/PreFlightLocator.java：替换 2 处（17 行 → 31 行）'
  });
  assert.equal(edit.kind, 'java');
  assert.equal(edit.primary, 'PreFlightLocator.java');
  assert.deepEqual(edit.diff, { added: 31, removed: 17 });

  const create = parseToolDisplay({
    name: 'create_file',
    args: 'path=docs/notes.md',
    body: '已创建 docs/notes.md（2048 字节，86 行）'
  });
  assert.equal(create.kind, 'markdown');
  assert.deepEqual(create.diff, { added: 86, removed: 0 });

  const failCmd = parseToolDisplay({
    name: 'run_command',
    args: 'command=./mvnw -pl pilot-core,pilot-server test',
    body: '命令: ./mvnw -pl pilot-core,pilot-server test 退出码: 1 耗时: 12000 ms'
  });
  assert.equal(failCmd.kind, 'terminal');
  assert.equal(failCmd.primary, './mvnw -pl pilot-core,pilot-server test');
  assert.equal(failCmd.exitCode, 1);

  const search = parseToolDisplay({
    name: 'search_text',
    args: 'pattern=PreFlightLocator',
    body: '共匹配 9 处'
  });
  assert.equal(search.kind, 'search');
  assert.equal(search.primary, 'PreFlightLocator');

  const okCmd = parseToolDisplay({
    name: 'run_command',
    args: 'command=git status --short',
    body: '命令: git status --short 退出码: 0 耗时: 45 ms'
  });
  assert.equal(okCmd.primary, 'git status --short');
  assert.equal(okCmd.exitCode, null);
});

test('ask_user 行头只说等待几项，不重复卡片里的问题正文', () => {
  const ask = parseToolDisplay({
    name: 'ask_user',
    args: 'ASK\n「仓库现状」快照要怎么落地？\n只加开关\u001f精简内容\u001f按 dsh 做法'
  });
  assert.equal(ask.primary, '等待你选择 3 项');
  assert.doesNotMatch(ask.primary, /仓库现状/);
  assert.equal(ask.filePath, '');

  const broken = parseToolDisplay({ name: 'ask_user', args: 'ASK\n只有一个选项\n甲' });
  assert.equal(broken.primary, '等待回答');
});

test('summarizeActiveTool 提取当前运行中工具的简短摘要', () => {
  assert.equal(
    summarizeActiveTool([
      { name: 'read_file', args: 'path=tasks.md', status: 'ok' },
      { name: 'edit_file', args: 'path=src/PreFlightLocator.java', status: 'run' }
    ]),
    '编辑 PreFlightLocator.java'
  );
  assert.equal(
    summarizeActiveTool([{ name: 'read_file', args: 'path=tasks.md', status: 'ok' }]),
    ''
  );
});

test('toolArgsText 收到结构化对象参数时提取核心字段而不输出 [object Object]', () => {
  assert.equal(toolArgsText('run_command', { command: 'mvn test' }), 'mvn test');
  assert.equal(toolArgsText('edit_file', { path: 'src/App.tsx' }), 'src/App.tsx');
  assert.equal(toolArgsText('command_status', { taskId: 'abc1234567890', waitSeconds: 10 }), 'abc12345… · 最多等 10 秒');
});
