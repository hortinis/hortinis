import { expect, test, type APIRequestContext } from '@playwright/test';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';

const execute = promisify(execFile);

test('recovers unchanged pending work after a real PostgreSQL outage', async ({ browser }) => {
  test.setTimeout(60_000);
  const context = await browser.newContext();
  const page = await context.newPage();
  // Use the existing maximum jitter delays to leave time for PostgreSQL to restart.
  await page.addInitScript(() => {
    Math.random = () => 1 - Number.EPSILON;
  });
  const operation = {
    operationId: crypto.randomUUID(),
    recordId: crypto.randomUUID(),
    value: 'outage recovery',
    kind: 'create' as const,
  };
  const submitted: unknown[] = [];
  page.on('response', (response) => {
    if (
      response.url().endsWith('/api/v1/sync/operations') &&
      response.request().method() === 'POST'
    ) {
      submitted.push(response.request().postDataJSON());
    }
  });
  try {
    await ensurePaginatedJournal(page.request);
    const initialPull = page.waitForResponse(
      (response) => response.url().endsWith('/api/v1/sync/changes') && response.status() === 200,
    );
    await page.goto('/');
    await initialPull;
    await waitForLeaseRelease(page);
    await seedCreate(page, operation);
    await postgresCommand('stop');
    try {
      const unavailable = page.waitForResponse(
        (response) =>
          response.url().endsWith('/api/v1/sync/operations') && response.status() === 503,
      );
      await page.reload();
      const response = await unavailable;
      expect(response.headers()['retry-after']).toBe('1');
      expect(response.headers()['cache-control']).toBe('no-store');
      expect(await response.json()).toEqual({
        code: 'SYNCHRONIZATION_UNAVAILABLE',
        message: 'The synchronization service is unavailable.',
      });
    } finally {
      await postgresCommand('start');
    }
    await expect
      .poll(() => acceptedRevision(page, operation.operationId), { timeout: 25_000 })
      .toBe('1');
    await waitForLeaseRelease(page);
    expect(submitted.length).toBeGreaterThanOrEqual(2);
    for (const request of submitted) expect(request).toEqual(operation);
    expect(await journalOperationCount(page.request, operation.operationId)).toBe(1);
  } finally {
    await context.close();
  }
});

interface JournalPage {
  changes: { operationId: string }[];
  nextCursor: string;
  hasMore: boolean;
}

async function ensurePaginatedJournal(request: APIRequestContext): Promise<void> {
  const response = await request.get('/api/v1/sync/changes');
  expect(response.status()).toBe(200);
  const firstPage = (await response.json()) as JournalPage;
  if (firstPage.hasMore) return;
  // Keep existing history; seed enough accepted changes to cross the server's 100-entry page.
  for (let index = firstPage.changes.length; index <= 100; index++) {
    const accepted = await request.post('/api/v1/sync/operations', {
      data: {
        operationId: crypto.randomUUID(),
        recordId: crypto.randomUUID(),
        kind: 'create',
        value: 'journal pagination regression',
      },
    });
    expect(accepted.status()).toBe(200);
  }
}

async function journalOperationCount(
  request: APIRequestContext,
  operationId: string,
): Promise<number> {
  let cursor: string | undefined;
  let count = 0;
  for (;;) {
    const response = await request.get('/api/v1/sync/changes', {
      params: cursor === undefined ? {} : { cursor },
    });
    expect(response.status()).toBe(200);
    const page = (await response.json()) as JournalPage;
    count += page.changes.filter((change) => change.operationId === operationId).length;
    if (!page.hasMore) return count;
    expect(page.nextCursor).not.toBe(cursor);
    cursor = page.nextCursor;
  }
}

function topologyProjectName(): string {
  const project = process.env['HORTINIS_TOPOLOGY_PROJECT_NAME'];
  if (!project) throw new Error('Run this suite through pnpm test:e2e:topology.');
  return project;
}

async function postgresCommand(command: 'stop' | 'start'): Promise<void> {
  await execute('docker', [
    'compose',
    '--project-name',
    topologyProjectName(),
    '--file',
    resolve(__dirname, '../../../infrastructure/docker/compose.yaml'),
    '--file',
    resolve(__dirname, '../../../infrastructure/docker/compose.sync-test.yaml'),
    command,
    'postgres',
  ]);
}

test('replays a lost create acknowledgement and propagates deletion through the real service and PostgreSQL', async ({
  browser,
}) => {
  // Route-based acknowledgement loss must reach Playwright rather than the service worker.
  const firstContext = await browser.newContext({ serviceWorkers: 'block' });
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
  expect(loseCreateAcknowledgement).toBe(false);
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
            const request = transaction
              .objectStore('synchronizationLeases')
              .get('technical-records');
            transaction.oncomplete = () => {
              database.close();
              resolve(!request.result || request.result.expiresAt <= Date.now());
            };
            transaction.onerror = () => reject(transaction.error);
          });
        }),
      { timeout: 15_000 },
    )
    .toBe(true);
}
