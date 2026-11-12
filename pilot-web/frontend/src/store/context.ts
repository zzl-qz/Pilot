import type { Draft } from 'immer';
import type { StoreState } from '../types/store.js';
export interface StoreContext { get: () => StoreState; set: (change: (draft: Draft<StoreState>) => void) => void }
export type StoreSlice<K extends keyof StoreState> = Pick<StoreState, K>;

/** 浏览器计时器是数字，Node 测试计时器可主动释放事件循环。 */
export function unrefTimer(timer: number | { unref?: () => void }) {
  if (typeof timer === 'object') timer.unref?.();
}
