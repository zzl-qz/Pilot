import type React from 'react';
import { useEffect, useState } from 'react';

export function ProcessChevron() {
  return (
    <svg className="process-chevron-svg" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 3.5 4.5 4.5L6 12.5" />
    </svg>
  );
}

export function isSummaryClick(event: React.MouseEvent<HTMLDetailsElement>) {
  if (!(event.target as HTMLElement).closest('summary')) return false;
  event.preventDefault();
  return true;
}

export function useSecondTick(active: boolean) {
  const [, bump] = useState(0);
  useEffect(() => {
    if (!active) return undefined;
    const timer = setInterval(() => bump((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [active]);
}

export function elapsedSeconds(startedAt?: number, finishedAt?: number | null) {
  if (startedAt == null || !Number.isFinite(startedAt)) return 0;
  return Math.max(0, Math.floor(((finishedAt ?? Date.now()) - startedAt) / 1000));
}
