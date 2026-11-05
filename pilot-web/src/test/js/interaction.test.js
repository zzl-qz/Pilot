import test from 'node:test';
import assert from 'node:assert/strict';
import { duplicateResult, reviewQuote, runPhase, runTone } from '../../../frontend/src/lib/interaction.js';

test('仅消除同一用户回合中完全重复的结论，保留不同和跨轮结论', () => {
  const turn = {type:'turn', reply:{text:'完成'}};
  const result = {type:'result', detail:' 完成 '};
  assert.equal(duplicateResult([turn,{type:'plan'},result],2),true);
  assert.equal(duplicateResult([turn,{type:'user'},result],2),false);
  assert.equal(duplicateResult([turn,{type:'result',detail:'其他'}],1),false);
});
test('审阅引用包含旧新行号和多行；内容的围栏不能截断引用', () => {
  const text = reviewQuote('目录/a b.md', [{kind:'del',old:2,text:'```'}, {kind:'add',next:3,text:'new'}], '请修改');
  assert.match(text,/目录\/a b.md（原文件:2 → 现文件:3）/);
  assert.match(text,/````diff\n-```\n\+new\n````\n请修改/);
  assert.equal(reviewQuote('a',[{kind:'hunk',text:'@@'}]),'');
});
test('运行状态优先显示停止与审批，再显示实际工具阶段', () => {
  assert.equal(runPhase({running:true,pendingApprovals:{a:{}}}),'等待批准');
  assert.equal(runPhase({running:true,cancelling:true,pendingApprovals:{a:{}}}),'正在停止');
  for (const [name,args,label] of [['read_file','','正在读取'],['apply_patch','','正在修改'],['run_command','npm test','正在测试']]) {
    assert.equal(runPhase({running:true,thread:{rows:[{type:'turn',tools:[{name,args,status:'run'}]}]}}),label);
  }
  assert.equal(runPhase({lastOutcome:'NEED_INPUT'}),'等待补充');
});

test('失败、暂停和等待状态不能显示为就绪或沿用运行颜色', () => {
  for (const [state, label, tone] of [
    [{lastOutcome:'completed'}, '就绪', 'ready'],
    [{lastOutcome:'HTTP_502'}, '运行失败', 'error'],
    [{lastOutcome:'MAX_STEPS'}, '已暂停', 'paused'],
    [{lastOutcome:'CANCELLED'}, '已取消', 'paused'],
    [{lastOutcome:'BLOCKED'}, '遇到阻塞', 'attention'],
    [{lastOutcome:'UNVERIFIED'}, '结果待核验', 'attention'],
    [{running:true,pendingApprovals:{a:{}}}, '等待批准', 'attention'],
    [{running:true,cancelling:true}, '正在停止', 'paused']
  ]) {
    assert.equal(runPhase(state), label);
    assert.equal(runTone(runPhase(state)), tone);
  }
  assert.equal(runTone('正在思考', true), 'attention');
  assert.equal(runTone('正在测试'), 'running');
});
