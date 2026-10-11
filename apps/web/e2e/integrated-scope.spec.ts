import {
  expect,
  test,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test';
import { appendPendingChain, localRecord } from './support/outbox-chain';
import {
  acceptedRevision,
  readStoredValue,
  seedPendingOperation,
  tombstoneRevision,
  waitForLeaseRelease,
} from './support/indexeddb';
import { establishedHeaders } from './support/binding';
import { synchronizationDiagnostics, topologyCommand } from './support/topology';

interface Bootstrap {
  serverInstanceId: string;
  identity: { synchronizationScopeId: string };
  boundary: { kind: string };
  population: string;
  expectation: string;
}
interface Device {
  context: BrowserContext;
  page: Page;
}
const credentialCanary = 'private-s7-credential-canary';

test('isolates two A devices and B through replay, dependent edits, deletion, substitution and real restart', async ({
  browser,
}) => {
  test.setTimeout(180_000);
  const contexts: BrowserContext[] = [];
  const diagnostics: string[] = [];
  try {
    const a1 = await device(browser, 'A1', contexts, diagnostics);
    const a2 = await device(browser, 'A2', contexts, diagnostics);
    const b = await device(browser, 'B', contexts, diagnostics);
    const beforeA = await bootstrap(a1.page.request);
    const beforeA2 = await bootstrap(a2.page.request);
    const beforeB = await bootstrap(b.page.request);
    expect(beforeA.identity).toEqual(beforeA2.identity);
    expect(beforeA.identity).not.toEqual(beforeB.identity);
    expect(beforeA.serverInstanceId).toBe(beforeB.serverInstanceId);
    expect(beforeA.boundary.kind).toBe('generationless');
    const owners = await Promise.all(
      [a1, a2, b].map(({ page }) =>
        readStoredValue<{ localDataSetId: string }>(page, 'localDataSetIdentity', 'local-data-set'),
      ),
    );
    expect(new Set(owners.map((owner) => owner?.localDataSetId)).size).toBe(3);

    const recordId = crypto.randomUUID();
    const createId = crypto.randomUUID();
    const secondId = crypto.randomUUID();
    const thirdId = crypto.randomUUID();
    const deleteId = crypto.randomUUID();
    const aCreate = {
      operationId: createId,
      recordId,
      kind: 'create' as const,
      value: 'private-s7-A-original',
    };
    const bCreate = { ...aCreate, value: 'private-s7-B-original' };
    const aRequests: unknown[] = [];
    const bRequests: unknown[] = [];
    a1.page.on('request', (request) => {
      if (request.url().endsWith('/sync/operations')) aRequests.push(request.postDataJSON());
    });
    b.page.on('request', (request) => {
      if (request.url().endsWith('/sync/operations')) bRequests.push(request.postDataJSON());
    });

    let release!: () => void;
    let accepted!: () => void;
    const releaseAcknowledgement = new Promise<void>((resolve) => {
      release = resolve;
    });
    const serverAccepted = new Promise<void>((resolve) => {
      accepted = resolve;
    });
    let loseAcknowledgement = true;
    await a1.page.route('**/api/v1/sync/operations', async (route) => {
      const body = route.request().postDataJSON() as { operationId: string };
      if (body.operationId === createId && loseAcknowledgement) {
        loseAcknowledgement = false;
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        accepted();
        await releaseAcknowledgement;
        await route.fulfill({ status: 503, headers: { 'Cache-Control': 'no-store' }, body: '' });
      } else await route.continue();
    });
    try {
      await appendPendingChain(a1.page, [aCreate]);
      await a1.page.reload();
      await serverAccepted;
      await appendPendingChain(a1.page, [
        {
          operationId: secondId,
          recordId,
          kind: 'replace',
          value: 'private-s7-A-second',
          expectedRevision: null,
          predecessorOperationId: createId,
        },
        {
          operationId: thirdId,
          recordId,
          kind: 'replace',
          value: 'private-s7-A-latest',
          expectedRevision: null,
          predecessorOperationId: secondId,
        },
      ]);
      // B progresses with colliding IDs while A's acknowledgement and lease remain pending.
      await appendPendingChain(b.page, [
        bCreate,
        {
          operationId: secondId,
          recordId,
          kind: 'replace',
          value: 'private-s7-B-second',
          expectedRevision: null,
          predecessorOperationId: createId,
        },
        {
          operationId: thirdId,
          recordId,
          kind: 'replace',
          value: 'private-s7-B-latest',
          expectedRevision: null,
          predecessorOperationId: secondId,
        },
      ]);
      await b.page.reload();
      await expect.poll(() => acceptedRevision(b.page, thirdId)).toBe('3');
      await waitForLeaseRelease(b.page);
      expect(await acceptedRevision(a1.page, createId)).toBeUndefined();
      expect(
        await readStoredValue(
          a1.page,
          'synchronizationRetryState',
          `technical-records:push:${createId}`,
        ),
      ).toMatchObject({ attemptCount: 1, inFlight: true });
      const aLease = await readStoredValue<{ localDataSetId: string; ownerId: string }>(
        a1.page,
        'synchronizationLeases',
        'technical-records',
      );
      const bLease = await readStoredValue<{ localDataSetId: string; ownerId: string }>(
        b.page,
        'synchronizationLeases',
        'technical-records',
      );
      expect(aLease?.localDataSetId).toBe(owners[0]?.localDataSetId);
      expect(bLease?.localDataSetId).toBe(owners[2]?.localDataSetId);
      expect(aLease?.ownerId).not.toBe(bLease?.ownerId);

      expect(
        await readStoredValue(
          b.page,
          'synchronizationRetryState',
          `technical-records:push:${createId}`,
        ),
      ).toBeUndefined();
      release();
      await expect.poll(() => acceptedRevision(a1.page, thirdId), { timeout: 15_000 }).toBe('3');
      await waitForLeaseRelease(a1.page);
    } finally {
      release();
    }
    expect(aRequests).toEqual([
      aCreate,
      aCreate,
      {
        operationId: secondId,
        recordId,
        kind: 'replace',
        value: 'private-s7-A-second',
        expectedRevision: '1',
      },
      {
        operationId: thirdId,
        recordId,
        kind: 'replace',
        value: 'private-s7-A-latest',
        expectedRevision: '2',
      },
    ]);
    expect(bRequests).toEqual([
      bCreate,
      {
        operationId: secondId,
        recordId,
        kind: 'replace',
        value: 'private-s7-B-second',
        expectedRevision: '1',
      },
      {
        operationId: thirdId,
        recordId,
        kind: 'replace',
        value: 'private-s7-B-latest',
        expectedRevision: '2',
      },
    ]);
    await a2.page.reload();
    await expect
      .poll(() => localRecord(a2.page, recordId))
      .toMatchObject({ value: 'private-s7-A-latest', lastAcceptedRevision: '3' });
    await waitForLeaseRelease(a2.page);
    expect(await localRecord(b.page, recordId)).toMatchObject({
      value: 'private-s7-B-latest',
      lastAcceptedRevision: '3',
    });
    await assertIndependentJournal(
      a1.page.request,
      b.page.request,
      createId,
      aCreate.value,
      bCreate.value,
    );

    const aHeaders = await establishedHeaders(a1.page.request);
    const bHeaders = await establishedHeaders(b.page.request);
    const aCursor = (
      await readStoredValue<{ cursor: string }>(
        a2.page,
        'synchronizationState',
        'technical-records',
      )
    )?.cursor;
    const bCursor = (
      await readStoredValue<{ cursor: string }>(b.page, 'synchronizationState', 'technical-records')
    )?.cursor;
    expect(aCursor).toBeTruthy();
    expect(bCursor).toBeTruthy();
    expect(aCursor).not.toBe(bCursor);
    for (const [request, foreign, cursor] of [
      [b.page.request, aHeaders, aCursor],
      [a1.page.request, bHeaders, bCursor],
    ] as const) {
      const expectationDenial = await request.get('/api/v1/sync/changes', { headers: foreign });
      expect(expectationDenial.status()).toBe(409);
      expect(expectationDenial.headers()['cache-control']).toBe('no-store');
      expect(await expectationDenial.json()).toEqual({
        code: 'BINDING_PRECONDITION_FAILED',
        message: 'The binding precondition no longer holds.',
      });
      const ownHeaders = request === b.page.request ? bHeaders : aHeaders;
      const cursorDenial = await request.get('/api/v1/sync/changes', {
        headers: ownHeaders,
        params: { cursor: cursor! },
      });
      expect(cursorDenial.status()).toBe(400);
      expect(await cursorDenial.json()).toEqual({
        code: 'INVALID_REQUEST',
        message: 'The request is invalid.',
      });
    }
    const selected = await b.page.request.get('/api/v1/sync/bootstrap', {
      params: { synchronizationScopeId: beforeA.identity.synchronizationScopeId },
      headers: { 'X-Synchronization-Scope': beforeA.identity.synchronizationScopeId },
    });
    expect(((await selected.json()) as Bootstrap).identity).toEqual(beforeB.identity);
    for (const marker of ['unknown-private-s7-device', 'A1, B', 'A1 B']) {
      const denied = await b.page.request.get('/api/v1/sync/bootstrap', {
        headers: { 'X-Hortinis-Test-Device': marker },
      });
      expect(denied.status()).toBe(403);
      expect(await denied.json()).toEqual({ code: 'ACCESS_DENIED', message: 'Access is denied.' });
    }

    const deletion = {
      operationId: deleteId,
      recordId,
      kind: 'delete' as const,
      expectedRevision: '3',
    };
    await seedPendingOperation(a1.page, deletion, 'private-s7-A-latest');
    await a1.page.reload();
    await expect.poll(() => tombstoneRevision(a1.page, recordId)).toBe('4');
    await waitForLeaseRelease(a1.page);
    await a2.page.reload();
    await expect.poll(() => tombstoneRevision(a2.page, recordId)).toBe('4');
    await waitForLeaseRelease(a2.page);
    expect(await tombstoneRevision(b.page, recordId)).toBeUndefined();
    expect(await localRecord(b.page, recordId)).toMatchObject({ value: 'private-s7-B-latest' });
    const bReplace = {
      operationId: deleteId,
      recordId,
      kind: 'replace' as const,
      value: 'private-s7-B-after-A-deletion',
      expectedRevision: '3',
    };
    await seedPendingOperation(b.page, bReplace);
    await b.page.reload();
    await expect.poll(() => acceptedRevision(b.page, deleteId)).toBe('4');
    await waitForLeaseRelease(b.page);
    const retired = await a1.page.request.post('/api/v1/sync/operations', {
      headers: aHeaders,
      data: { ...aCreate, operationId: crypto.randomUUID() },
    });
    expect(retired.status()).toBe(409);
    expect((await retired.json()).code).toBe('RECORD_IDENTIFIER_RETIRED');

    const aState = await readStoredValue(a2.page, 'synchronizationState', 'technical-records');
    const bState = await readStoredValue(b.page, 'synchronizationState', 'technical-records');
    await topologyCommand('restart', 'sync');
    await expect
      .poll(
        async () => {
          try {
            return (await a1.page.request.get('/api/v1/server/capabilities')).status();
          } catch {
            return 0;
          }
        },
        { timeout: 90_000 },
      )
      .toBe(200);
    const restartedA = await bootstrap(a1.page.request, aHeaders);
    const restartedB = await bootstrap(b.page.request, bHeaders);
    expect(restartedA.serverInstanceId).toBe(beforeA.serverInstanceId);
    expect(restartedA.identity).toEqual(beforeA.identity);
    expect(restartedA.expectation).toBe(aHeaders['Sync-Expectation']);
    expect(restartedB.identity).toEqual(beforeB.identity);
    expect(restartedB.expectation).toBe(bHeaders['Sync-Expectation']);
    for (const [request, headers, create] of [
      [a1.page.request, aHeaders, aCreate],
      [b.page.request, bHeaders, bCreate],
    ] as const) {
      const replay = await request.post('/api/v1/sync/operations', { headers, data: create });
      expect(replay.status()).toBe(200);
      expect((await replay.json()).record.value).toBe(create.value);
    }
    expect(await readStoredValue(a2.page, 'synchronizationState', 'technical-records')).toEqual(
      aState,
    );
    expect(await readStoredValue(b.page, 'synchronizationState', 'technical-records')).toEqual(
      bState,
    );
    await a2.page.reload();
    await b.page.reload();
    await expect.poll(() => tombstoneRevision(a2.page, recordId)).toBe('4');
    await expect
      .poll(() => localRecord(b.page, recordId))
      .toMatchObject({ value: bReplace.value, lastAcceptedRevision: '4' });
    await waitForLeaseRelease(a2.page);
    await waitForLeaseRelease(b.page);
    await assertIndependentJournal(
      a1.page.request,
      b.page.request,
      createId,
      aCreate.value,
      bCreate.value,
    );
    const storedA = await readStoredValue<{ value: string }>(a2.page, 'technicalRecords', recordId);
    expect(storedA).toBeUndefined();
    const serverLogs = await synchronizationDiagnostics();
    const captured = `${serverLogs}\n${diagnostics.join('\n')}`;
    for (const prohibited of [
      credentialCanary,
      recordId,
      createId,
      secondId,
      thirdId,
      deleteId,
      aCreate.value,
      bCreate.value,
      'private-s7-A-latest',
      bReplace.value,
      beforeA.serverInstanceId,
      beforeA.identity.synchronizationScopeId,
      beforeB.identity.synchronizationScopeId,
      aHeaders['Sync-Expectation'],
      bHeaders['Sync-Expectation'],
      'unknown-private-s7-device',
      'X-Hortinis-Test-Device',
    ]) {
      expect(captured).not.toContain(prohibited);
    }
  } finally {
    await Promise.all(contexts.map((context) => context.close()));
  }
});

async function device(
  browser: Browser,
  marker: string,
  contexts: BrowserContext[],
  diagnostics: string[],
): Promise<Device> {
  const context = await browser.newContext({
    serviceWorkers: 'block',
    extraHTTPHeaders: {
      'X-Hortinis-Test-Device': marker,
      Authorization: `Bearer ${credentialCanary}`,
    },
  });
  contexts.push(context);
  const page = await context.newPage();
  page.on('pageerror', (error) => diagnostics.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning')
      diagnostics.push(message.text());
  });
  await page.goto('/');
  await waitForLeaseRelease(page);
  await page.getByRole('button', { name: 'Connect to server' }).click();
  await expect(page.getByRole('region', { name: 'Server connection' })).toContainText(
    'connected to its server',
  );
  await waitForLeaseRelease(page);
  return { context, page };
}

async function bootstrap(
  request: APIRequestContext,
  headers?: Record<string, string>,
): Promise<Bootstrap> {
  const response = await request.get('/api/v1/sync/bootstrap', { headers });
  expect(response.status()).toBe(200);
  expect(response.headers()['cache-control']).toBe('no-store');
  return (await response.json()) as Bootstrap;
}

async function assertIndependentJournal(
  a: APIRequestContext,
  b: APIRequestContext,
  operationId: string,
  aValue: string,
  bValue: string,
): Promise<void> {
  for (const [request, ownValue, foreignValue] of [
    [a, aValue, bValue],
    [b, bValue, aValue],
  ] as const) {
    const response = await request.get('/api/v1/sync/changes', {
      headers: await establishedHeaders(request),
    });
    expect(response.status()).toBe(200);
    const page = (await response.json()) as {
      changes: { operationId: string; record?: { value: string } }[];
      hasMore: boolean;
    };
    expect(page.hasMore).toBe(false);
    const creations = page.changes.filter((change) => change.operationId === operationId);
    expect(creations).toHaveLength(1);
    expect(creations[0].record?.value).toBe(ownValue);
    expect(JSON.stringify(page)).not.toContain(foreignValue);
  }
}
