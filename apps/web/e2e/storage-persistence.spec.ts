import { expect, test, type Page } from '@playwright/test';

import { appendPendingChain } from './support/outbox-chain';

async function waitForReleasedLease(page: Page): Promise<void> {
  await page.waitForFunction(
    () =>
      new Promise<boolean>((resolve, reject) => {
        const request = indexedDB.open('hortinis');
        request.onerror = () => reject(request.error);
        request.onsuccess = () => {
          const database = request.result;
          const transaction = database.transaction('synchronizationLeases', 'readonly');
          const lease = transaction.objectStore('synchronizationLeases').get('technical-records');
          transaction.oncomplete = () => {
            database.close();
            resolve(lease.result?.expiresAt === 0);
          };
          transaction.onabort = () => {
            database.close();
            reject(transaction.error);
          };
        };
      }),
  );
}

for (const mode of ['best-effort', 'unsupported', 'persistent'] as const) {
  test(`shows ${mode} storage status and observes pending work across reload and tabs`, async ({
    page,
    context,
  }) => {
    await context.addInitScript((mode) => {
      const state = window as unknown as { h7PersistenceRequests: number };
      state.h7PersistenceRequests = 0;
      Object.defineProperty(navigator, 'onLine', {
        get: () => location.search.includes('online=1'),
      });
      Object.defineProperty(navigator, 'storage', {
        configurable: true,
        value:
          mode === 'unsupported'
            ? undefined
            : {
                persisted: async () => mode === 'persistent',
                persist: () => {
                  state.h7PersistenceRequests += 1;
                  throw new Error('Reload must not request persistence');
                },
              },
      });
    }, mode);
    await context.route('**/api/v1/sync/**', async (route) => {
      if (route.request().method() === 'POST') {
        const operation = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            outcome: 'accepted',
            operationId: operation.operationId,
            sequence: '1',
            record: { recordId: operation.recordId, value: operation.value, revision: '1' },
          }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            changes: [],
            nextCursor: 'cursor-one',
            hasMore: false,
          }),
        });
      }
    });
    await page.goto('/');
    const storage = page.getByRole('region', { name: 'Browser storage' });
    await expect(storage.getByRole('status')).toContainText(
      mode === 'persistent' ? 'enabled' : mode === 'unsupported' ? 'unsupported' : 'not confirmed',
    );
    await waitForReleasedLease(page);
    await appendPendingChain(page, [
      {
        operationId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
        recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
        kind: 'create',
        value: 'local intent',
      },
    ]);
    await page.reload();
    await expect(storage.getByRole('status')).toContainText(
      mode === 'persistent' ? 'enabled' : mode === 'unsupported' ? 'unsupported' : 'not confirmed',
    );
    if (mode === 'persistent') await expect(storage.getByRole('alert')).toHaveCount(0);
    else await expect(storage.getByRole('alert')).toContainText('Unsynchronized changes');
    expect(
      await page.evaluate(
        () => (window as unknown as { h7PersistenceRequests: number }).h7PersistenceRequests,
      ),
    ).toBe(0);
    await waitForReleasedLease(page);
    const other = await context.newPage();
    await other.goto('/?online=1');
    await waitForReleasedLease(other);
    await expect(storage.getByRole('alert')).toHaveCount(0);
    expect(
      await other.evaluate(
        () => (window as unknown as { h7PersistenceRequests: number }).h7PersistenceRequests,
      ),
    ).toBe(0);
    await other.close();
  });
}
