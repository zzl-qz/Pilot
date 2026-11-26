export function localCapabilitySummary<T extends { name: string; available: boolean }>(items: readonly T[] | undefined) {
  const sorted = [...(items || [])].sort((a, b) => Number(b.available) - Number(a.available) || a.name.localeCompare(b.name));
  return { items: sorted, available: sorted.filter(item => item.available).length };
}
