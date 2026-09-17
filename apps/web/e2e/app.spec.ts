import { expect, test } from '@playwright/test';

test('loads the application shell', async ({ page }) => {
  const response = await page.goto('/');

  expect(response?.status()).toBe(200);
  await expect(page).toHaveTitle('Hortinis');
  await expect(page.getByRole('heading', { level: 1, name: 'Hortinis' })).toBeVisible();
});

test('reloads the application shell offline after the service worker caches it', async ({
  page,
  context,
}) => {
  await page.goto('/');
  await expect(page).toHaveTitle('Hortinis');

  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
  await page.waitForFunction(async () => {
    const appCacheName = (await caches.keys()).find((name) => name.includes(':assets:app:cache'));
    if (!appCacheName) {
      return false;
    }

    const appCache = await caches.open(appCacheName);
    const cachedPaths = (await appCache.keys()).map((request) => new URL(request.url).pathname);
    return cachedPaths.includes('/index.html') && cachedPaths.some((path) => path.endsWith('.js'));
  });

  await context.setOffline(true);
  try {
    const response = await page.reload();

    expect(response?.status()).toBe(200);
    expect(response?.fromServiceWorker()).toBe(true);
    await expect(page.getByRole('heading', { level: 1, name: 'Hortinis' })).toBeVisible();
  } finally {
    await context.setOffline(false);
  }
});

test('resumes persisted synchronization work after reloading the application', async ({ page }) => {
  let recoveryEnabled = false;
  const operation = {
    operationId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
    recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
    value: 'reload value',
    kind: 'create',
  };

  await page.route('**/api/v1/sync/**', async (route) => {
    if (!recoveryEnabled) {
      await route.abort();
      return;
    }

    if (route.request().method() === 'POST') {
      if (JSON.stringify(route.request().postDataJSON()) !== JSON.stringify(operation)) {
        await route.fulfill({ status: 400, body: 'unexpected operation' });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          outcome: 'accepted',
          operationId: operation.operationId,
          record: { recordId: operation.recordId, revision: '1', value: operation.value },
          sequence: '1',
        }),
      });
      return;
    }

    if (new URL(route.request().url()).searchParams.get('cursor') !== 'cursor-one') {
      await route.fulfill({ status: 400, body: 'unexpected cursor' });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ changes: [], nextCursor: 'cursor-two', hasMore: false }),
    });
  });

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Hortinis' })).toBeVisible();
  await page.waitForTimeout(250);
  await page.evaluate((pendingOperation) => {
    return new Promise<void>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const database = request.result;
        const transaction = database.transaction(
          ['technicalRecords', 'outboxOperations', 'synchronizationState'],
          'readwrite',
        );
        transaction.objectStore('technicalRecords').put({
          recordId: pendingOperation.recordId,
          value: pendingOperation.value,
          lastAcceptedRevision: null,
        });
        transaction.objectStore('outboxOperations').put(pendingOperation);
        transaction.objectStore('synchronizationState').put({
          scope: 'technical-records',
          cursor: 'cursor-one',
        });
        transaction.oncomplete = () => {
          database.close();
          resolve();
        };
        transaction.onerror = () => reject(transaction.error);
      };
    });
  }, operation);

  recoveryEnabled = true;
  await page.reload();
  await page.waitForFunction(
    async () => {
      const database = await new Promise<IDBDatabase>((resolve, reject) => {
        const request = indexedDB.open('hortinis');
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      });
      return await new Promise<boolean>((resolve, reject) => {
        const transaction = database.transaction(
          ['outboxOperations', 'acceptedOperationResults', 'synchronizationState'],
          'readonly',
        );
        const outbox = transaction.objectStore('outboxOperations').count();
        const results = transaction.objectStore('acceptedOperationResults').count();
        const state = transaction.objectStore('synchronizationState').get('technical-records');
        transaction.oncomplete = () => {
          database.close();
          resolve(
            outbox.result === 0 && results.result === 1 && state.result?.cursor === 'cursor-two',
          );
        };
        transaction.onerror = () => reject(transaction.error);
      });
    },
    undefined,
    { timeout: 10_000 },
  );
});
