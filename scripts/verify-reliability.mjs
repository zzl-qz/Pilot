#!/usr/bin/env node
// 可由本机、Gitee 或 GitHub runner 直接调用；无模型 API 请求，不自动安装工具或拉取镜像。
import { spawn } from 'node:child_process';
import { readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('..', import.meta.url));
const flags = new Set(process.argv.slice(2));
if ([...flags].some(flag => !['--containers', '--ui', '--web-only'].includes(flag))) throw new Error('用法：node scripts/verify-reliability.mjs [--containers] [--ui] [--web-only]');
const windows = process.platform === 'win32';
async function run(command, args, cwd = root) {
  console.log(`\n> ${command} ${args.join(' ')}`);
  const code = await new Promise((resolveCode, reject) => {
    const child = spawn(command, args, { cwd, stdio: 'inherit', env: { ...process.env, CI: 'true', PILOT_SKIP_DEPS_ASK: '1' } });
    child.once('error', reject); child.once('exit', (code, signal) => resolveCode(signal ? 1 : code));
  });
  if (code !== 0) throw new Error(`检查失败：${command}（${code}）`);
}
const javaArgs = ['-B', '-ntp', '-pl', 'pilot-server', '-am', '-DskipFrontend=true', 'test', 'spotless:check', 'checkstyle:check'];
if (flags.has('--containers')) {
  for (const image of ['alpine:3.20', 'node:22-alpine', 'maven:3.9-eclipse-temurin-25']) await run('docker', ['image', 'inspect', '--format', '{{.Id}}', image]);
  javaArgs.push('-Dpilot.test.container.image=alpine:3.20', '-Dpilot.test.project-builds=true');
}
// cmd 路径和参数均为固定脚本常量，不把用户输入拼接为 shell 代码。
await run(windows ? (process.env.ComSpec || 'cmd.exe') : 'sh', windows ? ['/d', '/s', '/c', 'mvnw.cmd', ...javaArgs] : ['./mvnw', ...javaArgs]);
async function tests(folder) {
  const files = (await readdir(resolve(root, folder))).filter(name => /\.test\.(m?js)$/.test(name)).sort().map(name => `${folder}/${name}`);
  await run(process.execPath, ['--test', ...files]);
}
await tests('pilot-web/src/test/js');
await tests('pilot-web/frontend/scripts');
await tests('pilot-desktop');
await tests('evaluation/harness');
await run(process.execPath, ['--test', 'evaluation/harness/baselines/2026-11-03-gpt-screening/support/proxy-usage-final.test.mjs']);
await run(process.execPath, ['pilot-web/frontend/scripts/check.mjs']);
if (flags.has('--ui')) await run(process.execPath, ['pilot-web/frontend/scripts/sandbox-recovery-smoke.mjs', ...(flags.has('--web-only') ? ['--web-only'] : [])]);
console.log('\n可靠性验证通过。未启用的容器/UI 实测，以及本机之外的操作系统验证，均不计为通过。');
