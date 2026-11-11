export function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('服务端返回空响应，请刷新后重试');
  return value;
}
