import { expect, test } from '@playwright/test';

test('propagates a retried deletion through the real service and PostgreSQL', async ({
  browser,
}) => {
  const firstContext = await browser.newContext();
  const firstPage = await firstContext.newPage();
  const recordId = crypto.randomUUID();
  const createId = crypto.randomUUID();
  const replaceId = crypto.randomUUID();
  const deleteId = crypto.randomUUID();
  let loseCreateAcknowledgement = true;
  await firstPage.route('**/api/v1/sync/operations', async (route) => {
    const body = route.request().postDataJSON() as { operationId?: string };
    if (body.operationId === createId && loseCreateAcknowledgement) {
      loseCreateAcknowledgement = false;
      await route.fetch();
      await route.fulfill({ status: 503, body: '' });
      return;
    }
    await route.continue();
  });

  const initialPull = firstPage.waitForResponse(
    (response) => response.url().endsWith('/api/v1/sync/changes') && response.status() === 200,
  );
  await firstPage.goto('/');
  await initialPull;
  await waitForLeaseRelease(firstPage);
  await seedCreate(firstPage, {
    operationId: createId,
    recordId,
    value: 'integrated create',
    kind: 'create',
  });
  await firstPage.reload();
  await expect.poll(() => acceptedRevision(firstPage, createId), { timeout: 15_000 }).toBe('1');
  await waitForLeaseRelease(firstPage);

  await seedReplace(firstPage, {
    operationId: replaceId,
    recordId,
    value: 'integrated replacement',
    kind: 'replace',
    expectedRevision: '1',
  });
  await firstPage.reload();
  await expect.poll(() => acceptedRevision(firstPage, replaceId), { timeout: 15_000 }).toBe('2');
  await waitForLeaseRelease(firstPage);

  await seedDelete(firstPage, {
    operationId: deleteId,
    recordId,
    kind: 'delete',
    expectedRevision: '2',
  });
  await firstPage.reload();
  await expect.poll(() => tombstoneRevision(firstPage, recordId), { timeout: 15_000 }).toBe('3');
  await waitForLeaseRelease(firstPage);

  const secondContext = await browser.newContext();
  const secondPage = await secondContext.newPage();
  await secondPage.goto('/');
  await expect.poll(() => tombstoneRevision(secondPage, recordId), { timeout: 15_000 }).toBe('3');
  await expect.poll(() => liveRecordExists(secondPage, recordId)).toBe(false);

  await secondContext.close();
  await firstContext.close();
});

async function seedCreate(
  page: import('@playwright/test').Page,
  operation: { operationId: string; recordId: string; value: string; kind: 'create' },
): Promise<void> {
  await page.evaluate(async (pending) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction(
        ['technicalRecords', 'outboxOperations'],
        'readwrite',
      );
      transaction.objectStore('technicalRecords').put({
        recordId: pending.recordId,
        value: pending.value,
        lastAcceptedRevision: null,
      });
      transaction.objectStore('outboxOperations').put(pending);
      transaction.oncomplete = () => {
        database.close();
        resolve();
      };
      transaction.onerror = () => reject(transaction.error);
    });
  }, operation);
}

async function seedReplace(
  page: import('@playwright/test').Page,
  operation: {
    operationId: string;
    recordId: string;
    value: string;
    kind: 'replace';
    expectedRevision: string;
  },
): Promise<void> {
  await page.evaluate(async (pending) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction(
        ['technicalRecords', 'outboxOperations'],
        'readwrite',
      );
      transaction.objectStore('technicalRecords').put({
        recordId: pending.recordId,
        value: pending.value,
        lastAcceptedRevision: pending.expectedRevision,
      });
      transaction.objectStore('outboxOperations').put(pending);
      transaction.oncomplete = () => {
        database.close();
        resolve();
      };
      transaction.onerror = () => reject(transaction.error);
    });
  }, operation);
}

async function seedDelete(
  page: import('@playwright/test').Page,
  operation: {
    operationId: string;
    recordId: string;
    kind: 'delete';
    expectedRevision: string;
  },
): Promise<void> {
  await page.evaluate(async (pending) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction(
        ['technicalRecords', 'pendingDeletionRecords', 'outboxOperations'],
        'readwrite',
      );
      transaction.objectStore('pendingDeletionRecords').put({
        recordId: pending.recordId,
        value: 'integrated replacement',
        lastAcceptedRevision: pending.expectedRevision,
      });
      transaction.objectStore('technicalRecords').delete(pending.recordId);
      transaction.objectStore('outboxOperations').put(pending);
      transaction.oncomplete = () => {
        database.close();
        resolve();
      };
      transaction.onerror = () => reject(transaction.error);
    });
  }, operation);
}

async function acceptedRevision(
  page: import('@playwright/test').Page,
  operationId: string,
): Promise<string | undefined> {
  return page.evaluate(async (id) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    return new Promise<string | undefined>((resolve, reject) => {
      const transaction = database.transaction('acceptedOperationResults', 'readonly');
      const request = transaction.objectStore('acceptedOperationResults').get(id);
      transaction.oncomplete = () => {
        database.close();
        resolve(request.result?.record?.revision);
      };
      transaction.onerror = () => reject(transaction.error);
    });
  }, operationId);
}

async function tombstoneRevision(
  page: import('@playwright/test').Page,
  recordId: string,
): Promise<string | undefined> {
  return page.evaluate(async (id) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    return new Promise<string | undefined>((resolve, reject) => {
      const transaction = database.transaction('technicalTombstones', 'readonly');
      const request = transaction.objectStore('technicalTombstones').get(id);
      transaction.oncomplete = () => {
        database.close();
        resolve(request.result?.revision);
      };
      transaction.onerror = () => reject(transaction.error);
    });
  }, recordId);
}

async function liveRecordExists(
  page: import('@playwright/test').Page,
  recordId: string,
): Promise<boolean> {
  return page.evaluate(async (id) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    return new Promise<boolean>((resolve, reject) => {
      const transaction = database.transaction('technicalRecords', 'readonly');
      const request = transaction.objectStore('technicalRecords').get(id);
      transaction.oncomplete = () => {
        database.close();
        resolve(request.result !== undefined);
      };
      transaction.onerror = () => reject(transaction.error);
    });
  }, recordId);
}

async function waitForLeaseRelease(page: import('@playwright/test').Page): Promise<void> {
  await expect
    .poll(
      () =>
        page.evaluate(async () => {
          const database = await new Promise<IDBDatabase>((resolve, reject) => {
            const request = indexedDB.open('hortinis');
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
          });
          return new Promise<boolean>((resolve, reject) => {
            const transaction = database.transaction('synchronizationLeases', 'readonly');
            const request = transaction.objectStore('synchronizationLeases').count();
            transaction.oncomplete = () => {
              database.close();
              resolve(request.result === 0);
            };
            transaction.onerror = () => reject(transaction.error);
          });
        }),
      { timeout: 15_000 },
    )
    .toBe(true);
}
