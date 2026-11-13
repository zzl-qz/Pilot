import { readFile, mkdir, writeFile, appendFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
const state = JSON.parse(await readFile(process.argv[2], 'utf8'));
const kind = process.argv[3];
const server = state[kind];
if (!server) throw new Error('Unknown owned evaluator');
const root = state.root;
const { parseArgs, createRunContext, runTask, recordMetadata } = await import(pathToFileURL(join(root, 'evaluation/harness/runner.mjs')));
const out = join(root, 'runs', kind);
await mkdir(out, { recursive: true });
const resultFile = join(out, 'trials.jsonl');
await writeFile(resultFile, '', { flag: 'wx' });
const args = parseArgs(['--base', server.base, '--model', server.model, '--repeat', '3', '--tasks', join(root, 'evaluation/harness/tasks.json'), '--timeout-minutes', '30', '--prompt-version', 'postfix-2145dbc', '--tool-version', 'postfix-2145dbc', '--environment', 'macos-arm64-java25-node22-concurrency3-total900-idle600-direct1000', '--prompt-dir', join(root, 'pilot-core/src/main/resources/prompts'), '--runtime-source', join(root, 'pilot-core/src/main/java'), '--runtime-source', join(root, 'pilot-server/src/main/java')]);
args.workRoot = join(root, '.pilot-eval');
args.runContext = await createRunContext(args);
const tasks = JSON.parse(await readFile(args.tasks)).tasks.filter(t => ['steer-constraint-hold'].includes(t.id));
const jobs = [1, 2, 3].flatMap(attempt => tasks.map(task => ({ task, attempt })));
await writeFile(join(out, 'plan.json'), JSON.stringify({ jobs: jobs.map(({ task, attempt }) => ({ task: task.id, attempt })), context: args.runContext }, null, 2));
let next = 0, stopped = false;
const results = [];
let writing = Promise.resolve();
await Promise.all(Array.from({ length: 3 }, async (_, worker) => {
  while (!stopped && next < jobs.length) {
    const { task, attempt } = jobs[next++];
    const journal = join(out, `worker-${worker}.inflight.json`);
    await writeFile(journal, JSON.stringify({ task: task.id, attempt, startedAt: new Date().toISOString() }));
    let result;
    const start = Date.now();
    try { result = await runTask({ ...args, attempt }, task); }
    catch (error) {
      stopped = true;
      result = { ...await recordMetadata(args, task), taskId: task.id, attempt, model: server.model,
        success: false, evaluationError: true, sessionId: error.sessionId ?? null, runId: error.runId ?? null,
        terminalStatus: error.terminalStatus ?? null, cancellationRequested: error.cancellationRequested === true,
        evaluationInterrupted: error.evaluationInterrupted === true, acceptanceRan: false,
        acceptancePassed: null, scenarioCovered: false, costKnown: false, costUsd: null,
        durationSeconds: (Date.now() - start) / 1000, notes: String(error) };
    }
    writing = writing.then(() => appendFile(resultFile, JSON.stringify(result) + '\n'));
    await writing;
    results.push(result);
    await writeFile(journal, JSON.stringify({ finished: true, task: task.id, attempt, success: result.success }));
    console.log(JSON.stringify({ task: task.id, attempt, success: result.success, seconds: result.durationSeconds, notes: result.notes }));
  }
}));
await writeFile(join(out, 'completion.json'), JSON.stringify({ planned: jobs.length, recorded: results.length, passed: results.filter(r => r.success).length, stoppedEarly: stopped }, null, 2));
if (stopped) process.exitCode = 1;
