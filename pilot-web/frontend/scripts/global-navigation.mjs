/** 按真实导航路径打开收起在“更多功能”中的入口，覆盖键盘/读屏所用的角色。 */
export async function openGlobalFunction(page, name) {
  await page.getByRole('button', { name: '更多功能', exact: true }).click();
  await page.getByRole('menuitem', { name, exact: typeof name === 'string' }).click();
}
