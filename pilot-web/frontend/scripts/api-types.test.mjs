import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join, relative } from 'node:path';
import ts from 'typescript';

test('HTTP 类型接受正常请求并拒绝错误标识符、游标、请求体和响应字段', async () => {
  const root = fileURLToPath(new URL('..', import.meta.url));
  await mkdir(join(root, '.tmp-check'), { recursive: true });
  const directory = await mkdtemp(join(root, '.tmp-check', 'api-types-'));
  try {
    const module = relative(directory, join(root, 'src/api.ts')).replaceAll('\\', '/');
    const valid = join(directory, 'valid.ts'), invalid = join(directory, 'invalid.ts');
    await writeFile(valid, `import {api} from ${JSON.stringify(module)};\napi.history('s',{before:0});\napi.importBookmarks('s',[1]);\napi.saveModel({clearApiKey:true});\napi.createProject({name:'项目'});\napi.session('s').then(value=>value?.title);\n`);
    await writeFile(invalid, `import {api} from ${JSON.stringify(module)};\napi.history(123,{before:'bad'});\napi.importBookmarks('s',['bad']);\napi.saveModel({clearApiKey:'bad'});\napi.createProject({name:123});\napi.session('s').then(value=>value?.doesNotExist);\n`);
    const configPath = join(root, 'tsconfig.json');
    const config = ts.readConfigFile(configPath, ts.sys.readFile);
    const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root, undefined, configPath);
    const program = ts.createProgram([...parsed.fileNames, valid, invalid], parsed.options);
    const diagnostics = ts.getPreEmitDiagnostics(program).filter(item => item.category === ts.DiagnosticCategory.Error);
    assert.deepEqual(diagnostics.filter(item => item.file?.fileName !== invalid).map(item => ts.flattenDiagnosticMessageText(item.messageText, '\n')), []);
    assert.deepEqual(diagnostics.filter(item => item.file?.fileName === invalid).map(item => item.file.getLineAndCharacterOfPosition(item.start).line + 1), [2, 3, 4, 5, 6]);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
