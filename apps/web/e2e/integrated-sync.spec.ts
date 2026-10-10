import { expect, test, type APIRequestContext } from '@playwright/test';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { appendPendingChain, localRecord } from './support/outbox-chain';
import {
  acceptedRevision,
  seedPendingOperation,
  tombstoneRevision,
  waitForLeaseRelease,
} from './support/indexeddb';

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
    await seedPendingOperation(page, operation);
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
  try {
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
        const accepted = await route.fetch();
        expect(accepted.status()).toBe(200);
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
    await seedPendingOperation(firstPage, {
      operationId: createId,
      recordId,
      value: 'integrated create',
      kind: 'create',
    });
    await firstPage.reload();
    await expect.poll(() => acceptedRevision(firstPage, createId), { timeout: 15_000 }).toBe('1');
    expect(loseCreateAcknowledgement).toBe(false);
    expect(await journalOperationCount(firstPage.request, createId)).toBe(1);
    await waitForLeaseRelease(firstPage);

    await seedPendingOperation(firstPage, {
      operationId: replaceId,
      recordId,
      value: 'integrated replacement',
      kind: 'replace',
      expectedRevision: '1',
    });
    await firstPage.reload();
    await expect.poll(() => acceptedRevision(firstPage, replaceId), { timeout: 15_000 }).toBe('2');
    await waitForLeaseRelease(firstPage);

    await seedPendingOperation(
      firstPage,
      {
        operationId: deleteId,
        recordId,
        kind: 'delete',
        expectedRevision: '2',
      },
      'integrated replacement',
    );
    await firstPage.reload();
    await expect.poll(() => tombstoneRevision(firstPage, recordId), { timeout: 15_000 }).toBe('3');
    await waitForLeaseRelease(firstPage);

    const secondContext = await browser.newContext();
    try {
      const secondPage = await secondContext.newPage();
      await secondPage.goto('/');
      await expect
        .poll(() => tombstoneRevision(secondPage, recordId), { timeout: 15_000 })
        .toBe('3');
      await expect.poll(() => localRecord(secondPage, recordId)).toBeUndefined();
    } finally {
      await secondContext.close();
    }
  } finally {
    await firstContext.close();
  }
});

test('synchronizes three offline edits in predecessor order through the real topology', async ({
  browser,
}) => {
  const context = await browser.newContext({ serviceWorkers: 'block' });
  const page = await context.newPage();
  const recordId = crypto.randomUUID();
  const createId = crypto.randomUUID();
  const secondId = crypto.randomUUID();
  const thirdId = crypto.randomUUID();
  const submitted: unknown[] = [];
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/sync/operations')) submitted.push(request.postDataJSON());
  });
  try {
    await page.goto('/');
    await waitForLeaseRelease(page);
    await context.setOffline(true);
    await appendPendingChain(page, [
      { operationId: createId, recordId, kind: 'create', value: 'first offline value' },
    ]);
    await appendPendingChain(page, [
      {
        operationId: secondId,
        recordId,
        kind: 'replace',
        value: 'second offline value',
        expectedRevision: null,
        predecessorOperationId: createId,
      },
    ]);
    await appendPendingChain(page, [
      {
        operationId: thirdId,
        recordId,
        kind: 'replace',
        value: 'third offline value',
        expectedRevision: null,
        predecessorOperationId: secondId,
      },
    ]);
    expect(submitted).toEqual([]);
    expect(await localRecord(page, recordId)).toMatchObject({ value: 'third offline value' });
    await context.setOffline(false);
    await page.reload();
    await expect.poll(() => acceptedRevision(page, thirdId), { timeout: 15000 }).toBe('3');
    await waitForLeaseRelease(page);
    expect(submitted).toEqual([
      { operationId: createId, recordId, kind: 'create', value: 'first offline value' },
      {
        operationId: secondId,
        recordId,
        kind: 'replace',
        value: 'second offline value',
        expectedRevision: '1',
      },
      {
        operationId: thirdId,
        recordId,
        kind: 'replace',
        value: 'third offline value',
        expectedRevision: '2',
      },
    ]);
    expect(await localRecord(page, recordId)).toMatchObject({
      value: 'third offline value',
      lastAcceptedRevision: '3',
    });
    for (const operationId of [createId, secondId, thirdId])
      expect(await journalOperationCount(page.request, operationId)).toBe(1);
  } finally {
    await context.close();
  }
});

test('preserves a lost acknowledgement replay when two later edits arrive', async ({ browser }) => {
  const context = await browser.newContext({ serviceWorkers: 'block' });
  const page = await context.newPage();
  const recordId = crypto.randomUUID();
  const createId = crypto.randomUUID();
  const secondId = crypto.randomUUID();
  const thirdId = crypto.randomUUID();
  const create = {
    operationId: createId,
    recordId,
    kind: 'create' as const,
    value: 'immutable original',
  };
  const submitted: unknown[] = [];
  let acknowledge!: () => void;
  let reportAccepted!: () => void;
  const releaseAcknowledgement = new Promise<void>((resolve) => {
    acknowledge = resolve;
  });
  const serverAccepted = new Promise<void>((resolve) => {
    reportAccepted = resolve;
  });
  let loseAcknowledgement = true;
  await page.route('**/api/v1/sync/operations', async (route) => {
    const operation = route.request().postDataJSON() as { operationId: string };
    submitted.push(operation);
    if (operation.operationId === createId && loseAcknowledgement) {
      loseAcknowledgement = false;
      const result = await route.fetch();
      expect(result.status()).toBe(200);
      reportAccepted();
      await releaseAcknowledgement;
      await route.fulfill({ status: 503, body: '' });
    } else await route.continue();
  });
  try {
    await page.goto('/');
    await waitForLeaseRelease(page);
    await appendPendingChain(page, [create]);
    await page.reload();
    await serverAccepted;
    await appendPendingChain(page, [
      {
        operationId: secondId,
        recordId,
        kind: 'replace',
        value: 'second intent',
        expectedRevision: null,
        predecessorOperationId: createId,
      },
      {
        operationId: thirdId,
        recordId,
        kind: 'replace',
        value: 'latest intent',
        expectedRevision: null,
        predecessorOperationId: secondId,
      },
    ]);
    acknowledge();
    await expect.poll(() => acceptedRevision(page, thirdId), { timeout: 15000 }).toBe('3');
    await waitForLeaseRelease(page);
    expect(submitted).toEqual([
      create,
      create,
      {
        operationId: secondId,
        recordId,
        kind: 'replace',
        value: 'second intent',
        expectedRevision: '1',
      },
      {
        operationId: thirdId,
        recordId,
        kind: 'replace',
        value: 'latest intent',
        expectedRevision: '2',
      },
    ]);
    expect(await localRecord(page, recordId)).toMatchObject({
      value: 'latest intent',
      lastAcceptedRevision: '3',
    });
    for (const operationId of [createId, secondId, thirdId])
      expect(await journalOperationCount(page.request, operationId)).toBe(1);
  } finally {
    acknowledge();
    await context.close();
  }
});
