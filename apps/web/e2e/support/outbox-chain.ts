import type { Page } from '@playwright/test';
import { readStoredValue } from './indexeddb';

export interface BrowserChainOperation {
  operationId: string;
  recordId: string;
  kind: 'create' | 'replace';
  value: string;
  expectedRevision?: string | null;
  predecessorOperationId?: string;
}

// The shell has no editing UI yet. Seed persisted intent without exposing test hooks in production.
export async function appendPendingChain(
  page: Page,
  operations: BrowserChainOperation[],
): Promise<void> {
  await page.evaluate(async (pending) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      await new Promise<void>((resolve, reject) => {
        const transaction = database.transaction(
          ['technicalRecords', 'outboxOperations'],
          'readwrite',
        );
        for (const operation of pending) transaction.objectStore('outboxOperations').add(operation);
        const latest = pending[pending.length - 1];
        const projection = transaction.objectStore('technicalRecords');
        const previous = projection.get(latest.recordId);
        previous.onsuccess = () =>
          projection.put({
            recordId: latest.recordId,
            value: latest.value,
            lastAcceptedRevision: previous.result?.lastAcceptedRevision ?? null,
          });
        transaction.oncomplete = () => {
          resolve();
        };
        transaction.onabort = () => {
          reject(transaction.error ?? new Error('IndexedDB chain write aborted.'));
        };
      });
    } finally {
      database.close();
    }
  }, operations);
}

export async function localRecord(
  page: Page,
  recordId: string,
): Promise<{ value: string; lastAcceptedRevision: string | null } | undefined> {
  return readStoredValue(page, 'technicalRecords', recordId);
}
