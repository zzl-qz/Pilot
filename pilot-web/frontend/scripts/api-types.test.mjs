import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join, relative, resolve } from 'node:path';
import ts from 'typescript';

test('HTTP 与 Store 类型接受正确调用并拒绝错误请求、响应、状态和事件载荷', async () => {
  const root = fileURLToPath(new URL('..', import.meta.url));
  await mkdir(join(root, '.tmp-check'), { recursive: true });
  const directory = await mkdtemp(join(root, '.tmp-check', 'api-types-'));
  try {
    const module = relative(directory, join(root, 'src/api.ts')).replaceAll('\\', '/');
    const store = relative(directory, join(root, 'src/store.ts')).replaceAll('\\', '/');
    const valid = join(directory, 'valid.ts'), invalid = join(directory, 'invalid.ts');
    await writeFile(valid, `import {api} from ${JSON.stringify(module)};\napi.history('s',{before:0});\napi.importBookmarks('s',[1]);\napi.saveModel({clearApiKey:true});\napi.createProject({name:'项目'});\napi.session('s').then(value=>value?.title);\napi.resolveArbitration('s','a',{choice:'保留'});\n`);
    await writeFile(invalid, `import {api} from ${JSON.stringify(module)};\napi.history(123,{before:'bad'});\napi.importBookmarks('s',['bad']);\napi.saveModel({clearApiKey:'bad'});\napi.createProject({name:123});\napi.session('s').then(value=>value?.doesNotExist);\napi.resolveArbitration('s','a',{decision:'错误字段'});\n`);
    const { appendFile } = await import('node:fs/promises');
    await appendFile(valid, `import {useStore} from ${JSON.stringify(store)};\nuseStore.setState({running:true});\nuseStore.getState().submitInput('任务');\nuseStore.getState().setUi({inspectorOpen:true});\nuseStore.getState().handleEvent({type:'assistant.delta',sequence:1,payload:{text:'内容'}});\n`);
    await appendFile(invalid, `import {useStore} from ${JSON.stringify(store)};\nuseStore.setState({running:'yes'});\nuseStore.getState().submitInput(123);\nuseStore.getState().setUi({doesNotExist:true});\nuseStore.getState().handleEvent({type:'assistant.delta',sequence:1,payload:{text:123}});\n`);
    const draft = `const draft={name:'模板',sourceSessionId:null,content:'任务',timezone:'UTC',requirements:{constraints:[],acceptance:[]},skill:null,fullAccess:true,isolated:true};\n`;
    await appendFile(valid, draft + `api.saveTemplate({version:0,name:'模板',task:draft});\napi.createSchedule({...draft,sourceSessionId:'s',runAt:'2099-01-01T00:00:00Z'});\n`);
    await appendFile(invalid, draft + `api.createSchedule(draft);\napi.saveTemplate({version:'bad',name:'模板',task:draft});\n`);
    const configPath = join(root, 'tsconfig.json');
    const config = ts.readConfigFile(configPath, ts.sys.readFile);
    const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root, undefined, configPath);
    const program = ts.createProgram([...parsed.fileNames, valid, invalid], parsed.options);
    const diagnostics = ts.getPreEmitDiagnostics(program).filter(item => item.category === ts.DiagnosticCategory.Error);
    assert.deepEqual(diagnostics.filter(item => resolve(item.file?.fileName || '') !== resolve(invalid)).map(item => ts.flattenDiagnosticMessageText(item.messageText, '\n')), []);
    assert.deepEqual(diagnostics.filter(item => resolve(item.file?.fileName || '') === resolve(invalid)).map(item => item.file.getLineAndCharacterOfPosition(item.start).line + 1), [2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 14, 15]);
  } finally { await rm(directory, { recursive: true, force: true }); }
});
