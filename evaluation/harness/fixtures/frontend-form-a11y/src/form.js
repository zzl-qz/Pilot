'use strict';

// 设置页表单。当前实现只顾排版，没管可访问性与窄屏。
function renderField(field) {
  const value = field.value ?? '';
  const error = field.error ? `<div class="error">${field.error}</div>` : '';
  return `<div class="field">
  <label>${field.label}</label>
  <input name="${field.name}" value="${value}" />
  ${error}
</div>`;
}

function renderForm(fields) {
  const body = fields.map(renderField).join('\n');
  return `<form class="settings">\n${body}\n</form>`;
}

function renderStyles() {
  return '.settings { width: 640px; } .field { display: flex; } .error { color: red; }';
}

module.exports = { renderField, renderForm, renderStyles };
