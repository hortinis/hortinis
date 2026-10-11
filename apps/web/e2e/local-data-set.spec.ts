import { expect, test } from '@playwright/test';
import { readStoredValue } from './support/indexeddb';

test('preserves an unowned browser database and pauses exchange without assigning ownership', async ({
  page,
  context,
}) => {
  const operation = {
    operationId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
    recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
    kind: 'create',
    value: 'private-unowned-browser-intent',
  };
  const diagnostics: string[] = [];
  page.on('pageerror', (error) => diagnostics.push(error.message));
  let requests = 0;
  await context.route('**/api/**', (route) => {
    requests++;
    return route.abort();
  });
  await page.goto('/manifest.webmanifest');
  await page.evaluate(async (operation) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis', 6);
      request.onupgradeneeded = () => {
        request.result.createObjectStore('technicalRecords', { keyPath: 'recordId' });
        request.result.createObjectStore('outboxOperations', { keyPath: 'operationId' });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      await new Promise<void>((resolve, reject) => {
        const transaction = database.transaction(
          ['technicalRecords', 'outboxOperations'],
          'readwrite',
        );
        transaction.objectStore('technicalRecords').put({
          recordId: operation.recordId,
          value: operation.value,
          lastAcceptedRevision: null,
        });
        transaction.objectStore('outboxOperations').put(operation);
        transaction.oncomplete = () => resolve();
        transaction.onabort = () => reject(transaction.error);
      });
    } finally {
      database.close();
    }
  }, operation);
  await page.goto('/');
  await expect(page.getByRole('alert')).toHaveText(
    'Pending changes could not be checked in browser storage.',
  );
  expect(await readStoredValue(page, 'outboxOperations', operation.operationId)).toEqual(operation);
  await page.reload();
  await expect(page.getByRole('alert')).toHaveText(
    'Pending changes could not be checked in browser storage.',
  );
  expect(await readStoredValue(page, 'outboxOperations', operation.operationId)).toEqual(operation);
  expect(requests).toBe(0);
  expect(diagnostics.join(' ')).not.toContain(operation.value);
  expect(diagnostics.join(' ')).not.toContain(operation.operationId);
  expect(diagnostics.join(' ')).not.toContain(operation.recordId);
});

test('creates offline identity without a server and retains it across offline reload and tabs', async ({
  page,
  context,
}) => {
  await context.route('**/api/**', (route) => route.abort());
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Hortinis' })).toBeVisible();
  await expect
    .poll(() =>
      readStoredValue<{ localDataSetId: string }>(page, 'localDataSetIdentity', 'local-data-set'),
    )
    .toMatchObject({ version: '1', localDataSetId: expect.stringMatching(/^[0-9a-f-]{36}$/) });
  const identity = await readStoredValue(page, 'localDataSetIdentity', 'local-data-set');
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  // Let the active worker finish initialization through a controlled online navigation.
  await page.reload();
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
  await page.waitForFunction(async () => {
    const name = (await caches.keys()).find((key) => key.includes(':assets:app:cache'));
    if (!name) return false;
    const paths = (await (await caches.open(name)).keys()).map(
      (request) => new URL(request.url).pathname,
    );
    return paths.includes('/index.html') && paths.some((path) => path.endsWith('.js'));
  });
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Hortinis' })).toBeVisible();
  expect(await readStoredValue(page, 'localDataSetIdentity', 'local-data-set')).toEqual(identity);
  const other = await context.newPage();
  await other.goto('/');
  await expect(other.getByRole('heading', { name: 'Hortinis' })).toBeVisible();
  expect(await readStoredValue(other, 'localDataSetIdentity', 'local-data-set')).toEqual(identity);
});
