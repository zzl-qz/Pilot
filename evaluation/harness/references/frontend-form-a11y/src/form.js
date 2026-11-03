'use strict';
const escape = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
function renderField(field) {
  const id = `field-${encodeURIComponent(field.name)}`;
  const errorId = `${id}-error`;
  return `<div class="field">
  <label for="${id}">${escape(field.label)}</label>
  <input id="${id}" name="${escape(field.name)}" value="${escape(field.value ?? '')}"${field.error ? ` aria-invalid="true" aria-describedby="${errorId}"` : ''} />
  ${field.error ? `<div class="error" id="${errorId}" role="alert">${escape(field.error)}</div>` : ''}
</div>`;
}
function renderForm(fields) { return `<form class="settings">${fields.map(renderField).join('\n')}</form>`; }
function renderStyles() {
  return '.settings { width: 100%; max-width: 40rem; box-sizing: border-box; } .field { display: flex; flex-direction: column; min-width: 0; } .field input { min-width: 0; width: 100%; box-sizing: border-box; } .error { color: red; overflow-wrap: anywhere; }';
}
module.exports = { renderField, renderForm, renderStyles };
