export function statusLetter(status?: string) {
  const raw = String(status || 'M').replace(/\?/g, 'U');
  return raw.slice(0, 1).toUpperCase();
}
