export type SafeStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key'> & Pick<Storage, 'length'> & {
  available: () => boolean;
  trySetItem: (key: string, value: string) => boolean;
  tryRemoveItem: (key: string) => boolean;
};
export type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> & {
  trySetItem?: (key: string, value: string) => boolean;
  tryRemoveItem?: (key: string) => boolean;
};

function safeStorage(getStorage: () => Storage): SafeStorage {
  const access = <T,>(operation: (storage: Storage) => T, fallback: T): T => {
    try {
      const target = getStorage();
      if (!target) return fallback;
      return operation(target);
    } catch (error) {
      console.warn('站点存储不可用，当前操作已降级', error);
      return fallback;
    }
  };
  return {
    get length() { return access(storage => storage.length, 0); },
    available() { return access(storage => typeof storage.length === 'number', false); },
    key(index) { return access(storage => storage.key(index), null); },
    getItem(key) { return access(storage => storage.getItem(key), null); },
    setItem(key, value) { access(storage => storage.setItem(key, value), undefined); },
    removeItem(key) { access(storage => storage.removeItem(key), undefined); },
    trySetItem(key, value) { return access(storage => { storage.setItem(key, value); return true; }, false); },
    tryRemoveItem(key) { return access(storage => { storage.removeItem(key); return true; }, false); }
  };
}

export function safeLocalStorage(): SafeStorage {
  return safeStorage(() => globalThis.localStorage);
}

export function safeSessionStorage(): SafeStorage {
  return safeStorage(() => globalThis.sessionStorage);
}

export function safeGetItem(storage: Pick<Storage, 'getItem'>, key: string): string | null {
  try { return storage.getItem(key); } catch { return null; }
}

export function safeSetItem(storage: Pick<Storage, 'setItem'> & { trySetItem?: (key: string, value: string) => boolean }, key: string, value: string): boolean {
  if (typeof storage.trySetItem === 'function') return storage.trySetItem(key, value);
  try { storage.setItem(key, value); return true; } catch { return false; }
}

export function safeRemoveItem(storage: Pick<Storage, 'removeItem'> & { tryRemoveItem?: (key: string) => boolean }, key: string): boolean {
  if (typeof storage.tryRemoveItem === 'function') return storage.tryRemoveItem(key);
  try { storage.removeItem(key); return true; } catch { return false; }
}
