import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';

const servicePath = new URL('../../../pilot-server/src/main/java/com/pilot/server/capability/LocalCapabilityService.java', import.meta.url);
const controllerPath = new URL('../../../pilot-server/src/main/java/com/pilot/server/controller/CapabilitiesController.java', import.meta.url);
const inspectorPath = new URL('../src/components/Inspector.tsx', import.meta.url);

test('本机能力发现只检查可执行文件，不启动、下载或安装', async () => {
  const service = await readFile(servicePath, 'utf8');
  assert.match(service, /Files\.isRegularFile\(candidate\)/);
  assert.match(service, /Files\.isExecutable\(candidate\)/);
  assert.doesNotMatch(service, /ProcessBuilder|Runtime\.getRuntime\(\)\.exec|HttpClient|URL\.openConnection/);
  assert.match(service, /未启动它来读取版本/);
});

test('能力 API 与面板展示缺少资源时的准备建议', async () => {
  const controller = await readFile(controllerPath, 'utf8');
  const inspector = await readFile(inspectorPath, 'utf8');
  assert.match(controller, /localCapabilities\.discover\(\)/);
  assert.match(inspector, /本机资源/);
  assert.match(inspector, /preparationAdvice/);
  assert.match(inspector, /不会启动、下载或安装/);
});
