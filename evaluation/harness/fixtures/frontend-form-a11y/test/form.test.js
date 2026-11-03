const test = require('node:test');
const assert = require('node:assert/strict');

const { renderField, renderForm, renderStyles } = require('../src/form.js');

const fields = [
  { name: 'email', label: '邮箱', value: 'a@b.com' },
  { name: 'nickname', label: '昵称', error: '不能为空' }
];

test('label 与输入框通过 for/id 关联', () => {
  const html = renderField({ name: 'email', label: '邮箱' });
  const idMatch = html.match(/<input[^>]*\bid="([^"]+)"/);
  const forMatch = html.match(/<label[^>]*\bfor="([^"]+)"/);
  assert.ok(idMatch, 'input 需要 id');
  assert.ok(forMatch, 'label 需要 for');
  assert.equal(forMatch[1], idMatch[1], 'for 与 id 必须一致');
});

test('错误提示能被读屏软件读到', () => {
  const html = renderField({ name: 'email', label: '邮箱', error: '格式不对' });
  assert.match(html, /role="alert"|aria-live=/, '错误提示需要 role="alert" 或 aria-live');
});

test('错误提示通过 aria-describedby 挂到输入框上', () => {
  const html = renderField({ name: 'email', label: '邮箱', error: '格式不对' });
  const described = html.match(/<input[^>]*aria-describedby="([^"]+)"/);
  assert.ok(described, 'input 需要 aria-describedby 指向错误提示');
  assert.match(html, new RegExp(`id="${described[1]}"`), '被指向的错误提示要有对应 id');
});

test('窄屏不出现横向滚动', () => {
  const styles = renderStyles();
  assert.match(styles, /max-width:\s*100%|width:\s*100%/, '容器应自适应宽度');
  // 只盯写死的 width；max-width 不会撑破窄屏，不算问题（所以要排除 -width）
  assert.equal(/(?<![-\w])width:\s*(?:[4-9]\d{2}|\d{4,})px/.test(styles), false,
    '不应写死超过 400px 的 width，窄屏会横向溢出');
});

test('渲染整张表单时不丢字段', () => {
  const html = renderForm(fields);
  assert.match(html, /name="email"/);
  assert.match(html, /name="nickname"/);
  assert.match(html, /不能为空/);
});
