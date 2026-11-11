import type { Draft } from 'immer';
import type { StoreState } from '../types/store.js';
export interface StoreContext { get: () => StoreState; set: (change: (draft: Draft<StoreState>) => void) => void }
export type StoreSlice<K extends keyof StoreState> = Pick<StoreState, K>;
