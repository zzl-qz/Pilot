export interface Idea {
  id: string;
  text: string;
  files: File[];
  createdAt: string;
}

const STORE = 'ideas';

async function transact<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('pilot-idea-library', 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: 'id' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  try {
    return await new Promise<T>((resolve, reject) => {
      const transaction = db.transaction(STORE, mode);
      const request = run(transaction.objectStore(STORE));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onabort = () => reject(transaction.error || new Error('想法库操作已取消'));
    });
  } finally {
    db.close();
  }
}

export async function listIdeas(): Promise<Idea[]> {
  const ideas = await transact<Idea[]>('readonly', store => store.getAll());
  return ideas.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function saveIdea(idea: Idea) {
  return transact('readwrite', store => store.put(idea));
}

export function deleteIdea(id: string) {
  return transact('readwrite', store => store.delete(id));
}
