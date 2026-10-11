import { expect, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test';

const serverInstanceId = '01890f3e-7c5a-7b12-8abc-0123456789ab';
const identity = {
  authority: 'configured',
  accessMode: 'single-user-no-auth',
  synchronizationScopeId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
};
const boundary = { kind: 'generationless' };

interface Observation {
  serverInstanceId: string;
  identity: typeof identity;
  boundary: typeof boundary;
  population: string;
  expectation: string;
}

export async function mockBindingDiscovery(surface: Page | BrowserContext): Promise<void> {
  if ('context' in surface) await mockBindingDiscovery(surface.context());
  await surface.route('**/api/v1/server/capabilities', (route) =>
    route.fulfill({
      json: {
        contractVersion: '1',
        serverInstanceId,
        accessMode: identity.accessMode,
        supportedBoundaries: ['generationless'],
        anchoredSnapshots: false,
      },
      headers: { 'Cache-Control': 'no-store' },
    }),
  );
  await surface.route('**/api/v1/sync/bootstrap', (route) =>
    route.fulfill({
      json: {
        contractVersion: '1',
        serverInstanceId,
        identity,
        boundary,
        population: 'populated',
        expectation: 'fixture-expectation',
      },
      headers: { 'Cache-Control': 'no-store' },
    }),
  );
}

// Disposable browser regression fixtures represent an already established device; production never infers this provenance.
export async function seedRetainedBinding(
  page: Page,
  observed: Pick<Observation, 'serverInstanceId' | 'identity' | 'boundary'> = {
    serverInstanceId,
    identity,
    boundary,
  },
): Promise<void> {
  await page.evaluate(async (observed) => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    try {
      await new Promise<void>((resolve, reject) => {
        const transaction = database.transaction(
          [
            'localDataSetIdentity',
            'localServerBindings',
            'localExchangeState',
            'synchronizationLeases',
          ],
          'readwrite',
        );
        const owner = transaction.objectStore('localDataSetIdentity').get('local-data-set');
        owner.onsuccess = () => {
          const localDataSetId = owner.result.localDataSetId;
          transaction
            .objectStore('localServerBindings')
            .put({ version: '1', localDataSetId, serverOrigin: location.origin, ...observed });
          transaction.objectStore('localExchangeState').put({
            localDataSetId,
            value: { state: 'bound', exchangeFence: '2', logoutPending: false },
          });
        };
        const lease = transaction.objectStore('synchronizationLeases').get('technical-records');
        lease.onsuccess = () => {
          if (lease.result)
            transaction
              .objectStore('synchronizationLeases')
              .put({ ...lease.result, exchangeFence: '2', expiresAt: 0 });
        };
        transaction.oncomplete = () => resolve();
        transaction.onabort = () => reject(transaction.error);
      });
    } finally {
      database.close();
    }
  }, observed);
}

const headersByRequest = new WeakMap<APIRequestContext, { 'Sync-Expectation': string }>();
export async function establishedHeaders(
  request: APIRequestContext,
): Promise<{ 'Sync-Expectation': string }> {
  const cached = headersByRequest.get(request);
  if (cached) return cached;
  const response = await request.get('/api/v1/sync/bootstrap');
  expect(response.status()).toBe(200);
  const observed = (await response.json()) as Observation;
  if (observed.population === 'empty') {
    const confirmation = await request.post('/api/v1/sync/bindings/confirm', {
      data: { expectation: observed.expectation, expectedPopulation: 'empty' },
    });
    expect(confirmation.status()).toBe(200);
  }
  const headers = { 'Sync-Expectation': observed.expectation };
  const page = await request.get('/api/v1/sync/changes', { headers });
  expect(page.status()).toBe(200);
  headersByRequest.set(request, headers);
  return headers;
}

export async function openRetainedDevice(page: Page): Promise<void> {
  const response = await page.request.get('/api/v1/sync/bootstrap');
  expect(response.status()).toBe(200);
  const observation = (await response.json()) as Observation;
  await page.goto('/');
  await page.waitForFunction(async () => {
    const databases = await indexedDB.databases();
    return databases.some((database) => database.name === 'hortinis' && database.version === 80);
  });
  await seedRetainedBinding(page, {
    serverInstanceId: observation.serverInstanceId,
    identity: observation.identity,
    boundary: observation.boundary,
  });
  await page.reload();
}
