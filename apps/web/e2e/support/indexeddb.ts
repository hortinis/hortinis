import { expect, type Page } from '@playwright/test';

type PendingOperation =
  | { operationId: string; recordId: string; kind: 'create'; value: string }
  | {
      operationId: string;
      recordId: string;
      kind: 'replace';
      value: string;
      expectedRevision: string;
    }
  | { operationId: string; recordId: string; kind: 'delete'; expectedRevision: string };

// Browser evaluations must be self-contained: Node-side closures cannot cross into the page.
export async function readStoredValue<T>(
  page: Page,
  store: string,
  key: string,
): Promise<T | undefined> {
  return page.evaluate(
    async ({ store, key }) => {
      const database = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open('hortinis');
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      try {
        return await new Promise<T | undefined>((resolve, reject) => {
          const transaction = database.transaction(store, 'readonly');
          const request = transaction.objectStore(store).get(key);
          transaction.oncomplete = () => resolve(request.result as T | undefined);
          transaction.onabort = () =>
            reject(transaction.error ?? new Error('IndexedDB read aborted.'));
        });
      } finally {
        database.close();
      }
    },
    { store, key },
  );
}

// Seed persisted intent atomically while the technical shell has no editing UI.
export async function seedPendingOperation(
  page: Page,
  operation: PendingOperation,
  deletedValue?: string,
): Promise<void> {
  if (operation.kind === 'delete' && deletedValue === undefined)
    throw new Error('A pending deletion must retain its previous value.');
  await page.evaluate(
    async ({ operation, deletedValue }) => {
      const database = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open('hortinis');
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      try {
        await new Promise<void>((resolve, reject) => {
          const stores = ['technicalRecords', 'outboxOperations'];
          if (operation.kind === 'delete') stores.push('pendingDeletionRecords');
          const transaction = database.transaction(stores, 'readwrite');
          const records = transaction.objectStore('technicalRecords');
          if (operation.kind === 'delete') {
            transaction.objectStore('pendingDeletionRecords').put({
              recordId: operation.recordId,
              value: deletedValue,
              lastAcceptedRevision: operation.expectedRevision,
            });
            records.delete(operation.recordId);
          } else {
            records.put({
              recordId: operation.recordId,
              value: operation.value,
              lastAcceptedRevision: operation.kind === 'create' ? null : operation.expectedRevision,
            });
          }
          transaction.objectStore('outboxOperations').put(operation);
          transaction.oncomplete = () => resolve();
          transaction.onabort = () =>
            reject(transaction.error ?? new Error('IndexedDB write aborted.'));
        });
      } finally {
        database.close();
      }
    },
    { operation, deletedValue },
  );
}

export async function acceptedRevision(
  page: Page,
  operationId: string,
): Promise<string | undefined> {
  const result = await readStoredValue<{ record?: { revision: string } }>(
    page,
    'acceptedOperationResults',
    operationId,
  );
  return result?.record?.revision;
}

export async function tombstoneRevision(page: Page, recordId: string): Promise<string | undefined> {
  return (await readStoredValue<{ revision: string }>(page, 'technicalTombstones', recordId))
    ?.revision;
}

export async function leaseIsReleased(page: Page): Promise<boolean> {
  const lease = await readStoredValue<{ expiresAt: number }>(
    page,
    'synchronizationLeases',
    'technical-records',
  );
  return !lease || lease.expiresAt <= (await page.evaluate(() => Date.now()));
}

export async function waitForLeaseRelease(page: Page): Promise<void> {
  await expect.poll(() => leaseIsReleased(page), { timeout: 15_000 }).toBe(true);
}
