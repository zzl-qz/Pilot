import test from 'node:test';
import assert from 'node:assert/strict';

import {
  estimateTextTokens,
  languageLabel,
  outputKind,
  parseCallout,
  resolveFileReference,
  safeImageSource
} from '../../main/resources/static/lib/content-format.js';

test('parseCallout 识别 GitHub 风格提示并保留正文', () => {
  assert.deepEqual(parseCallout(['[!WARNING]', '先保存改动。']), {
    kind: 'warning',
    label: '注意',
    lines: ['先保存改动。']
  });
  assert.equal(parseCallout(['普通引用']), null);
});

test('resolveFileReference 支持项目根路径、模块路径和行列号', () => {
  const workspace = '/Users/smt/Desktop/pilot';
  assert.deepEqual(resolveFileReference('/pilot/README.md:140', workspace), {
    path: 'README.md', line: 140, column: null
  });
  assert.deepEqual(resolveFileReference('/pilot-server/src/App.java:12:8', workspace), {
    path: 'pilot-server/src/App.java', line: 12, column: 8
  });
  assert.deepEqual(resolveFileReference('/Users/smt/Desktop/pilot/pom.xml', workspace), {
    path: 'pom.xml', line: null, column: null
  });
});

test('resolveFileReference 拒绝外链、越界路径和其他绝对路径', () => {
  const workspace = '/Users/smt/Desktop/pilot';
  assert.equal(resolveFileReference('https://example.com/a', workspace), null);
  assert.equal(resolveFileReference('/pilot/../secret.txt', workspace), null);
  assert.equal(resolveFileReference('/Users/other/project/file.txt', workspace), null);
});

test('languageLabel 把常见代码围栏名称转成可读标签', () => {
  assert.equal(languageLabel('ts'), 'TypeScript');
  assert.equal(languageLabel('bash'), 'Shell');
  assert.equal(languageLabel('svg'), 'SVG');
  assert.equal(languageLabel(''), '代码');
  assert.equal(languageLabel('kotlin'), 'KOTLIN');
});

test('outputKind 把可预览内容和源码、差异分开', () => {
  assert.equal(outputKind('svg', '<svg></svg>'), 'svg');
  assert.equal(outputKind('', '<svg xmlns="http://www.w3.org/2000/svg"></svg>'), 'svg');
  assert.equal(outputKind('html', '<html></html>'), 'html');
  assert.equal(outputKind('diff', '+ a\n- b'), 'diff');
  assert.equal(outputKind('mermaid', 'graph TD; A-->B'), 'mermaid');
  assert.equal(outputKind('latex', 'E=mc^2'), 'math');
  assert.equal(outputKind('java', 'class A {}'), 'code');
  assert.equal(outputKind('svg', '<svg>'), 'code');
});

test('safeImageSource 只接受网页图片和 data URL', () => {
  assert.equal(safeImageSource('https://example.com/a.png'), 'https://example.com/a.png');
  assert.equal(safeImageSource('javascript:alert(1)'), null);
  assert.equal(safeImageSource('data:image/png;base64,aaaa'), 'data:image/png;base64,aaaa');
  assert.equal(safeImageSource('data:text/html;base64,aaaa'), null);
});

test('estimateTextTokens 对中文更保守，并正确处理 emoji 码点', () => {
  assert.equal(estimateTextTokens('abcd'), 1);
  assert.equal(estimateTextTokens('中文'), 2);
  assert.equal(estimateTextTokens('a😀'), 2);
});
