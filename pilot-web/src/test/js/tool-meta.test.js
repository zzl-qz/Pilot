import test from 'node:test';
import assert from 'node:assert/strict';
import { toolArgsText } from '../../../frontend/src/lib/tool-meta.js';

test('command rows show the shell command, not the raw argument bag', () => {
  assert.equal(
    toolArgsText('run_command', '{command=git status --short --branch,timeoutSeconds=120}'),
    'git status --short --branch'
  );
  assert.equal(toolArgsText('read_file', '{path=pom.xml}'), 'pom.xml');
});
