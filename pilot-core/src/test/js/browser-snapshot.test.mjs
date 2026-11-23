import assert from 'node:assert/strict';
import test from 'node:test';

import { SNAPSHOT_SCRIPT } from '../../main/resources/browser/browser-sidecar.mjs';

const snapshot = eval(SNAPSHOT_SCRIPT);

function node(tag, options = {}) {
  const element = {
    tagName: tag.toUpperCase(),
    children: options.children ?? [],
    childNodes: options.childNodes ?? [],
    textContent: options.text ?? '',
    innerText: options.innerText ?? '',
    id: options.id ?? '',
    tabIndex: options.tabIndex ?? -1,
    isContentEditable: false,
    type: options.type ?? '',
    value: options.value ?? '',
    autocomplete: options.autocomplete ?? '',
    name: options.name ?? '',
    alt: '',
    disabled: false,
    hidden: options.hidden === true,
    style: {
      display: options.display ?? 'block',
      visibility: options.visibility ?? 'visible',
      opacity: options.opacity ?? '1',
    },
    rect: options.rect ?? { width: 40, height: 20 },
    attrs: { ...(options.attrs ?? {}) },
    getAttribute(name) {
      return this.attrs[name] ?? null;
    },
    setAttribute(name, value) {
      this.attrs[name] = String(value);
    },
    removeAttribute(name) {
      delete this.attrs[name];
    },
    hasAttribute(name) {
      return name === 'hidden' ? this.hidden : Object.hasOwn(this.attrs, name);
    },
    getBoundingClientRect() {
      return this.rect;
    },
  };
  return element;
}

function flatten(root, found = []) {
  if (!root) return found;
  found.push(root);
  for (const child of root.children) flatten(child, found);
  return found;
}

function mount(body) {
  globalThis.Node = { TEXT_NODE: 3 };
  globalThis.getComputedStyle = (element) => element.style;
  globalThis.location = { href: 'http://localhost:5185/' };
  globalThis.document = {
    title: 'Pilot',
    body,
    getElementById() {
      return null;
    },
    querySelectorAll(selector) {
      const all = flatten(body);
      if (String(selector).includes('data-pilot-ref')) {
        return all.filter((item) => item.attrs['data-pilot-ref']);
      }
      return all.filter((item) => item.tagName === 'BUTTON' || item.tagName === 'A' || item.tagName === 'INPUT');
    },
  };
}

test('零尺寸容器里的按钮仍然能点到', () => {
  const button = node('button', { text: '新任务', rect: { width: 36, height: 36 } });
  const shell = node('div', { rect: { width: 0, height: 0 }, children: [button] });
  mount(node('body', { rect: { width: 0, height: 0 }, children: [shell] }));

  const result = snapshot({ nodes: 200, chars: 20_000 });

  assert.ok(result.interactive >= 1);
  assert.equal(button.attrs['data-pilot-ref'], 'e1');
});

test('display none 的子树不会被收进快照', () => {
  const hidden = node('button', { text: '藏起来', display: 'none', rect: { width: 36, height: 36 } });
  mount(node('body', { children: [hidden] }));

  const result = snapshot({ nodes: 200, chars: 20_000 });

  assert.equal(result.interactive, 0);
  assert.equal(hidden.attrs['data-pilot-ref'], undefined);
});

test('空的新任务页会标成 freshTask', () => {
  const body = node('body', { innerText: '从一个任务开始', rect: { width: 800, height: 600 } });
  mount(body);

  const result = snapshot({ nodes: 200, chars: 20_000 });

  assert.equal(result.freshTask, true);
});

test('敏感表单值和 agent 动作提示不会进入快照', () => {
  const token = node('input', { type: 'text', name: 'apiToken', value: 'token-value-must-not-leak' });
  const code = node('input', { type: 'text', autocomplete: 'one-time-code', value: '184203' });
  const hidden = node('input', { type: 'hidden', name: 'csrf', value: 'csrf-token-must-not-leak' });
  const secret = node('textarea', { name: 'clientSecret', value: 'private-note-must-not-leak' });
  const overlay = node('div', { text: '正在填写：验证码', attrs: { 'data-pilot-agent-overlay': 'true' } });
  mount(node('body', { children: [token, code, hidden, secret, overlay] }));

  const result = snapshot({ nodes: 200, chars: 20_000 });
  const text = result.lines.join('\n');

  assert.match(text, /value=\[已遮挡\]/);
  assert.doesNotMatch(text, /token-value-must-not-leak|184203|csrf-token-must-not-leak|private-note-must-not-leak|正在填写/);
});
