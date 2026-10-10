import { expect, test } from '@playwright/test';
import { appendPendingChain } from './support/outbox-chain';
import {
  leaseIsReleased,
  readStoredValue,
  seedPendingOperation,
  waitForLeaseRelease,
} from './support/indexeddb';

test('rolls back aborted test transactions and closes connections after failures', async ({
  page,
}) => {
  // Use the application origin without starting synchronization or retaining application connections.
  await page.goto('/manifest.webmanifest');
  await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis', 1);
      request.onupgradeneeded = () => {
        const records = request.result.createObjectStore('technicalRecords', {
          keyPath: 'recordId',
        });
        records.createIndex('uniqueValue', 'value', { unique: true });
        request.result.createObjectStore('outboxOperations', { keyPath: 'operationId' });
        request.result.createObjectStore('synchronizationLeases', { keyPath: 'scope' });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    database.close();
  });
  const original = {
    operationId: 'original',
    recordId: 'first',
    kind: 'create' as const,
    value: 'unique',
  };
  await seedPendingOperation(page, original);
  await expect(
    seedPendingOperation(page, { ...original, operationId: 'rejected', recordId: 'second' }),
  ).rejects.toThrow(/ConstraintError|constraint/i);
  expect(await readStoredValue(page, 'outboxOperations', 'rejected')).toBeUndefined();
  expect(await readStoredValue(page, 'technicalRecords', 'second')).toBeUndefined();

  await expect(
    appendPendingChain(page, [
      { ...original, operationId: 'rolled-back', value: 'later' },
      { ...original, kind: 'replace', value: 'duplicate operation' },
    ]),
  ).rejects.toThrow(/ConstraintError|constraint/i);
  expect(await readStoredValue(page, 'outboxOperations', 'rolled-back')).toBeUndefined();
  expect(await readStoredValue(page, 'technicalRecords', 'first')).toMatchObject({
    value: 'unique',
  });
  await expect(readStoredValue(page, 'missingStore', 'first')).rejects.toThrow(/NotFoundError/);

  // Lease expiry must follow browser time, including Playwright's controlled clock.
  await page.clock.install({ time: new Date('2020-01-01T00:00:00Z') });
  await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      await new Promise<void>((resolve, reject) => {
        const transaction = database.transaction('synchronizationLeases', 'readwrite');
        transaction.objectStore('synchronizationLeases').put({
          scope: 'technical-records',
          expiresAt: Date.now() + 60_000,
        });
        transaction.oncomplete = () => resolve();
        transaction.onabort = () => reject(transaction.error);
      });
    } finally {
      database.close();
    }
  });
  expect(await leaseIsReleased(page)).toBe(false);
  await page.clock.fastForward(60_001);
  expect(await leaseIsReleased(page)).toBe(true);
  await waitForLeaseRelease(page);

  // A leaked connection would block database deletion rather than completing it.
  await page.evaluate(
    () =>
      new Promise<void>((resolve, reject) => {
        const request = indexedDB.deleteDatabase('hortinis');
        request.onsuccess = () => resolve();
        request.onerror = () => reject(request.error);
        request.onblocked = () =>
          reject(new Error('A test helper retained an IndexedDB connection.'));
      }),
  );
});
