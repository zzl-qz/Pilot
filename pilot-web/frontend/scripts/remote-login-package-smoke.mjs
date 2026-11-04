// 验收已经构建的 Jar；隔离数据目录与随机端口，不使用真实模型服务或现有会话。
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright-core';
const root = fileURLToPath(new URL('../../../', import.meta.url));
const output = await mkdtemp(join(tmpdir(), 'pilot-packaged-login-'));
const workspace = join(output, 'workspace'); await mkdir(workspace);
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const jar = resolve(root, 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar');
const token = randomUUID();
let logs = '', browser, child;
try {
  child = spawn(java, ['--enable-native-access=ALL-UNNAMED','-jar',jar,'--server.address=127.0.0.1','--server.port=0',
    `--pilot.data.dir=${join(output,'data')}`,`--pilot.agent.workspace=${workspace}`,
    '--pilot.runtime.provision=false','--pilot.web-search.local=false','--pilot.model.api-key=',
    '--pilot.model.endpoint=http://127.0.0.1:9/v1/chat/completions','--pilot.security.mode=token',`--pilot.security.token=${token}`],
  {cwd:output,stdio:['ignore','pipe','pipe']});
  const base = await new Promise((accept, reject) => {
    const timeout = setTimeout(()=>reject(new Error('测试服务启动超时')),30000);
    const capture = chunk => {
      logs += chunk.toString();
      const match = logs.match(/Tomcat started on port (\d+)/);
      if(match) {clearTimeout(timeout);accept(`http://127.0.0.1:${match[1]}`);}
    };
    child.stdout.on('data',capture);child.stderr.on('data',capture);
    child.once('error',error=>{clearTimeout(timeout);reject(error);});
    child.once('exit',code=>{clearTimeout(timeout);reject(new Error(`测试服务退出 ${code}`));});
  });
  browser = await chromium.launch({channel:'chrome',headless:true});
  const context = await browser.newContext({viewport:{width:390,height:844}});
  const page = await context.newPage(), errors=[]; page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base); await page.getByRole('heading',{name:'连接你的工作区'}).waitFor();
  assert.equal(new URL(page.url()).pathname,'/connect');
  await page.getByLabel('访问令牌').fill('wrong'); await page.getByRole('button',{name:'连接',exact:true}).click();
  await page.getByRole('alert').filter({hasText:'缺少或错误的访问令牌'}).waitFor();
  await page.getByLabel('访问令牌').fill(token); await page.getByRole('button',{name:'连接',exact:true}).click();
  await page.getByRole('heading',{name:'从一个任务开始'}).waitFor();
  assert.equal(new URL(page.url()).search,'');
  const cookie=(await context.cookies()).find(c=>c.name==='PILOT_ACCESS');
  assert.equal(cookie.httpOnly,true);assert.equal(cookie.sameSite,'Strict');
  const session = await page.evaluate(async workspace => {
    const response = await fetch('/api/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:'打包验收会话',workspace})});
    if(!response.ok) throw new Error(await response.text()); return response.json();
  },workspace);
  const files = await page.evaluate(async id=>{const response=await fetch(`/api/sessions/${encodeURIComponent(id)}/products`);if(!response.ok)throw new Error(await response.text());return response.json();},session.id);
  assert.deepEqual(files,[]);
  await page.getByRole('button',{name:/调研问题/}).click();
  assert.ok(await page.locator('.input-flow').innerText().then(text=>text.includes('调研问题：')));
  await page.screenshot({path:join(output,'connected-mobile.png'),animations:'disabled'});
  const denied = await page.evaluate(async()=>{await fetch('/api/access/logout',{method:'POST'});return (await fetch('/api/sessions')).status;});
  assert.equal(denied,401);await page.goto(base);await page.getByRole('heading',{name:'连接你的工作区'}).waitFor();
  assert.deepEqual(errors,[]);
  console.log('PASS packaged Jar: login CSP/header/cookie, real session/products API, mobile draft entry, logout/re-auth');
  console.log('Evidence:',output);
} finally {
  await browser?.close();
  if(child && child.exitCode===null) {
    child.kill();
    await new Promise(resolveDone=>{const timer=setTimeout(()=>{child.kill('SIGKILL');resolveDone();},5000);child.once('exit',()=>{clearTimeout(timer);resolveDone();});});
  }
  await writeFile(join(output,'server.log'),logs.replaceAll(token,'[test-token]'));
}
