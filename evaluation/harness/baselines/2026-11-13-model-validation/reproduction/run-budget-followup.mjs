import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
const state = JSON.parse(await readFile(process.argv[2], 'utf8'));
const kind = process.argv[3], server = state[kind], root = state.root;
if (!kind.startsWith('budget-') || !server) throw new Error('Only owned budget evaluators');
const { parseArgs, createRunContext, runTask, waitForTerminal, traceMetrics, verifyCandidate, recordMetadata } = await import(pathToFileURL(join(root, 'evaluation/harness/runner.mjs')));
const out = join(root, 'runs', kind); await mkdir(out, { recursive: true });
const args = parseArgs(['--base', server.base, '--model', server.model, '--tasks', join(root, 'evaluation/harness/scenarios/tasks.json'), '--timeout-minutes', '20', '--prompt-version', state.sourceCommit, '--tool-version', state.sourceCommit, '--environment', 'macos-arm64-java25-node22-budget8-maxsteps64-maxsegments8-concurrency3', '--prompt-dir', join(root, 'pilot-core/src/main/resources/prompts'), '--runtime-source', join(root, 'pilot-core/src/main/java'), '--runtime-source', join(root, 'pilot-server/src/main/java')]);
args.workRoot = join(root, '.pilot-eval'); args.runContext = await createRunContext(args);
const task = JSON.parse(await readFile(args.tasks)).tasks.find(t => t.id === 'budget-wind');
const tests = [];
async function readTests(dir, prefix = '') { for (const entry of await readdir(dir, { withFileTypes: true })) { const name = prefix + entry.name; if (entry.isDirectory()) await readTests(join(dir, entry.name), name + '/'); else if (entry.isFile()) tests.push([name, await readFile(join(dir, entry.name))]); else throw new Error('Unexpected nonregular test fixture'); } }
await readTests(join(root, 'evaluation/harness/scenarios/fixtures/budget-wind/test'));
async function api(path, body) {
  const response = await fetch(server.base + path, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Owned evaluator API ${response.status} ${path}`);
  return response.json();
}
async function outputs(workspace) {
  const result = {};
  for (const entry of await readdir(join(workspace, 'src/out'), { withFileTypes: true }).catch(error => { if (error.code === 'ENOENT') return []; throw error; })) {
    if (!entry.isFile()) throw new Error('Unexpected output entry');
    const bytes = await readFile(join(workspace, 'src/out', entry.name));
    result[entry.name] = createHash('sha256').update(bytes).digest('hex');
  }
  return result;
}
const ledger = async workspace => readFile(join(workspace, 'src/progress.json'), 'utf8').then(text => ({ raw: text, sha256: createHash('sha256').update(text).digest('hex') })).catch(error => { if (error.code === 'ENOENT') return null; throw error; });
const expectedOutputs = Array.from({ length: 40 }, (_, i) => `item-${String(i + 1).padStart(2, '0')}.json`);
const hasAllOutputs = value => Object.keys(value).length === 40 && expectedOutputs.every(name => Object.hasOwn(value, name));
const report = { schemaVersion: 2, model: server.model, requestLimitPerSegment: 8, maxSegments: 8, maxSteps: 64, sourceCommit: state.sourceCommit, context: args.runContext, segments: [], completed: false, budgetStopObserved: false };
const reportFile = join(out, 'recovery.json'); await writeFile(reportFile, JSON.stringify(report, null, 2), { flag: 'wx' });
const save = () => writeFile(reportFile, JSON.stringify(report, null, 2));
try {
  const attempt = Number(kind.split('-').at(-1));
  let first;
  const started = Date.now();
  try { first = await runTask({ ...args, attempt }, task); }
  catch (error) {
    report.sessionId = error.sessionId ?? null;
    const failed = { ...await recordMetadata(args, task), taskId: task.id, attempt, model: server.model,
      success: false, evaluationError: true, sessionId: report.sessionId, runId: error.runId ?? null,
      terminalStatus: error.terminalStatus ?? null, cancellationRequested: error.cancellationRequested === true,
      evaluationInterrupted: error.evaluationInterrupted === true, acceptanceRan: false,
      acceptancePassed: null, scenarioCovered: false, costKnown: false, costUsd: null,
      durationSeconds: (Date.now() - started) / 1000, notes: String(error) };
    await writeFile(join(out, 'initial-trial.json'), JSON.stringify(failed, null, 2), { flag: 'wx' });
    throw error;
  }
  await writeFile(join(out, 'initial-trial.json'), JSON.stringify(first, null, 2), { flag: 'wx' });
  report.sessionId = first.sessionId;
  const session = await api(`/api/sessions/${first.sessionId}`), workspace = session.workspace;
  let previous = await outputs(workspace);
  report.segments.push({ segment: 1, progressLedger: await ledger(workspace), terminalStatus: first.terminalStatus, originalTrialSuccess: first.success, acceptancePassed: first.acceptancePassed, modelRequests: first.modelRequestCount, outputCount: Object.keys(previous).length, budgetStopObserved: first.budgetStopObserved, outputs: previous });
  report.initialBoundaryAcceptancePassed = first.acceptancePassed === true;
  report.budgetStopObserved = first.budgetStopObserved === true; await save();
  let status = first.terminalStatus;
  for (let segment = 2; segment <= 8 && (status !== 'COMPLETED' || !hasAllOutputs(previous)); segment++) {
    const started = Date.now();
    await api(`/api/sessions/${first.sessionId}/continue`, {});
    const diagnostic = await waitForTerminal(args, first.sessionId);
    const trace = await traceMetrics(args, first.sessionId);
    const current = await outputs(workspace);
    const rewritten = Object.keys(previous).filter(file => previous[file] !== current[file]);
    const acceptance = await verifyCandidate(task, workspace, tests);
    report.segments.push({ segment, progressLedger: await ledger(workspace), terminalStatus: diagnostic.sessionStatus, seconds: (Date.now() - started) / 1000, modelRequests: diagnostic.modelRequests, budgetStopObserved: trace.budgetStopObserved, outputCount: Object.keys(current).length, previouslyWrittenFilesChanged: rewritten, outputs: current, acceptance });
    report.budgetStopObserved ||= trace.budgetStopObserved === true;
    previous = current; status = diagnostic.sessionStatus; await save();
  }
  report.finalAcceptance = await verifyCandidate(task, workspace, tests);
  report.completed = status === 'COMPLETED';
  const progress = JSON.parse(await readFile(join(workspace, 'src/progress.json'), 'utf8').catch(() => 'null'));
  report.allFortyItemsCompleted = hasAllOutputs(previous) && Array.isArray(progress?.done) && progress.done.length === 40 && new Set(progress.done).size === 40 && Array.isArray(progress?.remaining) && progress.remaining.length === 0;
  report.allBoundariesHaveValidLedger = report.initialBoundaryAcceptancePassed && report.segments.slice(1).every(segment => segment.acceptance.ok);
  report.recoveryPassed = report.budgetStopObserved && report.completed && report.allFortyItemsCompleted && report.finalAcceptance.ok;
  report.originalBoundaryResultPreserved = true;
  await save();
  console.log(JSON.stringify({ kind, recoveryPassed: report.recoveryPassed, segments: report.segments.length, finalStatus: status }));
} catch (error) {
  report.error = String(error); report.recoveryPassed = false;
  if (report.sessionId) {
    try { const d = await api(`/api/sessions/${report.sessionId}/runs/active`); if (d.runId) { await api(`/api/runs/${d.runId}/cancel`, {}); report.cancellationRequested = true; } } catch { report.cancellationUnconfirmed = true; }
  }
  await save(); process.exitCode = 1; console.error(String(error));
}
