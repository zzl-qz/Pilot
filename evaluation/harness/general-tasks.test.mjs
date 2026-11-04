import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cp, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { acceptanceResult, verifyCandidate } from './runner.mjs';
const base = fileURLToPath(new URL('./general/', import.meta.url));
const { tasks } = JSON.parse(await readFile(join(base, 'tasks.json'), 'utf8'));
const references = JSON.parse(await readFile(join(base, 'references.json'), 'utf8'));
for (const task of tasks) test(`通用任务夹具 ${task.id}：初始失败、参考通过、篡改验收仍失败`, async () => {
  const root = await mkdtemp(join(tmpdir(), 'pilot-general-eval-'));
  const workspace = join(root, 'workspace');
  try {
    await cp(join(base, task.fixture), workspace, { recursive: true });
    const tests = await Promise.all((await readdir(join(workspace, 'test'))).map(async name => [name, await readFile(join(workspace, 'test', name))]));
    assert.equal((await acceptanceResult(task, workspace)).ok, false);
    for (const [name, text] of Object.entries(references[task.id])) await writeFile(join(workspace, name), text);
    assert.equal((await verifyCandidate(task, workspace, tests)).ok, true);
    await writeFile(join(workspace, 'result.json'), '{}');
    await writeFile(join(workspace, 'test', 'acceptance.test.mjs'), '// falsified');
    assert.equal((await verifyCandidate(task, workspace, tests)).ok, false);
  } finally { await rm(root, { recursive: true, force: true }); }
});
