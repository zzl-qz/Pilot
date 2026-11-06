/** 只读取错误对象提供的字段；非 Error 的拒绝值也能使用原来的 UI 回退文案。 */
export function errorInfo(value: unknown): { name?: string; message?: string; status?: number } {
  if (!value || typeof value !== 'object') return {};
  const name = 'name' in value && typeof value.name === 'string' ? value.name : undefined;
  const message = 'message' in value && typeof value.message === 'string' ? value.message : undefined;
  const status = 'status' in value && typeof value.status === 'number' ? value.status : undefined;
  return { name, message, status };
}
