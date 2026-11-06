import assert from 'node:assert/strict';

// 模拟长内容，验证滚动只发生在正文，标题与关闭入口始终可见。
export async function checkDialogLayout(page) {
  const dialog = page.locator('dialog[open]');
  const close = dialog.locator('.dialog-close');
  await dialog.locator('.schedule-dialog-body').evaluate(body => {
    const filler = document.createElement('div');
    filler.dataset.layoutTest = 'true'; filler.style.height = '2000px';
    body.append(filler); body.scrollTop = 0;
  });
  const before = await close.boundingBox();
  const bounds = await dialog.boundingBox();
  assert.ok(before && bounds);
  assert.ok(before.y - bounds.y >= 10 && before.y - bounds.y <= 18, '关闭按钮靠近顶部');
  assert.ok(bounds.x + bounds.width - before.x - before.width >= 10 && bounds.x + bounds.width - before.x - before.width <= 18, '关闭按钮靠近右侧');
  const targetSize = page.viewportSize().width <= 560 ? 44 : 36;
  assert.ok(before.width >= targetSize && before.height >= targetSize, '关闭按钮点击区域');
  assert.equal(await dialog.locator('.schedule-header button').count(), 1, '标题栏只保留关闭操作');
  await dialog.locator('.schedule-dialog-body').evaluate(body => { body.scrollTop = body.scrollHeight; });
  try {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(resolve)));
    assert.ok(await dialog.locator('.schedule-dialog-body').evaluate(el => el.scrollTop > 0 && el.scrollWidth <= el.clientWidth + 1), '正文独立滚动且无横向溢出');
    const after = await close.boundingBox();
    assert.ok(Math.abs(after.x - before.x) < 1 && Math.abs(after.y - before.y) < 1, '滚动后关闭按钮位置固定');
    assert.ok(await close.evaluate(el => { const r = el.getBoundingClientRect(); return el.contains(document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)); }), '关闭按钮没有被内容遮挡');
    await close.focus(); await page.keyboard.press('Tab');
    assert.ok(await dialog.evaluate(el => el.contains(document.activeElement)), '键盘焦点保留在弹窗内');
  } finally {
    await dialog.locator('.schedule-dialog-body').evaluate(body => { body.querySelector('[data-layout-test]').remove(); body.scrollTop = 0; });
  }
}
