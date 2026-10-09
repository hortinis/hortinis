import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpSynchronizationTransport } from './http-synchronization-transport';
import Dexie from 'dexie';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { pushRetryWorkId } from '../persistence/local-synchronization-retry-state';
import type { OperationResult, TechnicalRecordOperation } from './conformance';
import { NETWORK_STATUS, type NetworkStatus } from './network-status';
import {
  SYNCHRONIZATION_CLOCK,
  SYNCHRONIZATION_JITTER,
  SYNCHRONIZATION_SCHEDULER,
  type SynchronizationClock,
  type SynchronizationJitter,
  type SynchronizationScheduler,
} from './synchronization-runtime';
import {
  SynchronizationUnavailableError,
  SynchronizationUnexpectedResponseError,
  type SynchronizationTransport,
} from './synchronization-transport';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { TechnicalRecordSynchronizationService } from './technical-record-synchronization-service';

describe('bounded synchronization retry', () => {
  const databases: Dexie[] = [];

  afterEach(async () => {
    TestBed.resetTestingModule();
    for (const database of databases.splice(0).reverse()) {
      database.close();
      await database.delete();
    }
  });

  it('uses one immediate attempt and four bounded retries before durable exhaustion', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const clock = new TestClock(10_000);
    const scheduler = new ControlledScheduler(clock);
    const jitter: SynchronizationJitter = { sample: vi.fn((maximum) => maximum) };
    const failingTransport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => {
        throw new SynchronizationUnavailableError();
      }),
      pullChanges: vi.fn(),
    };
    const first = services(database, failingTransport, onlineStatus(true), {
      clock,
      jitter,
      scheduler,
    });
    await first.persistence.commitCreate(operation);

    await expect(first.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'scheduled',
      attemptCount: 1,
    });
    expect(scheduler.delays()).toEqual([1_000]);

    for (const [attemptCount, expectedDelays] of [
      [2, [2_000]],
      [3, [4_000]],
      [4, [8_000]],
    ] as const) {
      scheduler.runNext();
      await vi.waitFor(() =>
        expect(first.service.status()).toMatchObject({ status: 'scheduled', attemptCount }),
      );
      expect(scheduler.delays()).toEqual(expectedDelays);
    }

    scheduler.runNext();
    await vi.waitFor(() =>
      expect(first.service.status()).toEqual({
        status: 'exhausted',
        phase: 'push',
        attemptCount: 5,
        reason: 'unavailable',
      }),
    );

    expect(failingTransport.submitOperation).toHaveBeenCalledTimes(5);
    for (const [submitted] of vi.mocked(failingTransport.submitOperation).mock.calls) {
      expect(submitted).toEqual(operation);
    }
    expect(jitter.sample).toHaveBeenNthCalledWith(1, 1_000);
    expect(jitter.sample).toHaveBeenNthCalledWith(2, 2_000);
    expect(jitter.sample).toHaveBeenNthCalledWith(3, 4_000);
    expect(jitter.sample).toHaveBeenNthCalledWith(4, 8_000);
    expect(scheduler.delays()).toEqual([]);

    const exhausted = await database.synchronizationRetryState.get(
      pushRetryWorkId(operation.operationId),
    );
    expect(exhausted).toEqual({
      workId: pushRetryWorkId(operation.operationId),
      scope: 'technical-records',
      phase: 'push',
      operationId: operation.operationId,
      attemptCount: 5,
      nextEligibleAt: null,
      failureCategory: 'unavailable',
      exhausted: true,
    });
    expect(Object.keys(exhausted!).sort()).toEqual(
      [
        'attemptCount',
        'exhausted',
        'failureCategory',
        'nextEligibleAt',
        'operationId',
        'phase',
        'scope',
        'workId',
      ].sort(),
    );
    await expect(database.outboxOperations.toArray()).resolves.toMatchObject([
      { ...operation, submittedAt: expect.any(Number) },
    ]);

    TestBed.resetTestingModule();
    let resolveManualAttempt: ((result: OperationResult) => void) | undefined;
    const recoveredTransport: SynchronizationTransport = {
      submitOperation: vi.fn(
        () =>
          new Promise<OperationResult>((resolve) => {
            resolveManualAttempt = resolve;
          }),
      ),
      pullChanges: vi.fn(async () => ({
        changes: [],
        nextCursor: 'after-manual-recovery',
        hasMore: false,
      })),
    };
    const reloaded = services(database, recoveredTransport, onlineStatus(true), {
      clock,
      scheduler: new ControlledScheduler(clock),
    });

    await expect(reloaded.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'exhausted',
      attemptCount: 5,
    });
    expect(recoveredTransport.submitOperation).not.toHaveBeenCalled();

    const manualRecovery = reloaded.service.retryNow();
    await vi.waitFor(() => expect(recoveredTransport.submitOperation).toHaveBeenCalledOnce());
    expect(reloaded.service.status()).toEqual({ status: 'manual-recovery' });
    await expect(
      database.synchronizationRetryState.get(pushRetryWorkId(operation.operationId)),
    ).resolves.toMatchObject({
      attemptCount: 1,
      inFlight: true,
      exhausted: false,
      operationId: operation.operationId,
    });
    resolveManualAttempt!(acceptedResult(operation));
    await expect(manualRecovery).resolves.toEqual({ status: 'completed', pushed: 1, pulled: 1 });
    expect(recoveredTransport.submitOperation).toHaveBeenCalledWith(operation);
    await expect(database.synchronizationRetryState.count()).resolves.toBe(0);
    await expect(database.outboxOperations.count()).resolves.toBe(0);
  });

  it('restores a pending delay after reload without resetting its budget', async () => {
    const name = databaseName();
    const clock = new TestClock(5_000);
    const firstDatabase = openDatabase(name);
    const operation = createOperation();
    const firstTransport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => {
        throw new SynchronizationUnavailableError();
      }),
      pullChanges: vi.fn(),
    };
    const first = services(firstDatabase, firstTransport, onlineStatus(true), {
      clock,
      jitter: { sample: () => 400 },
      scheduler: new ControlledScheduler(clock),
    });
    await first.persistence.commitCreate(operation);
    await expect(first.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'scheduled',
      attemptCount: 1,
      nextEligibleAt: 5_400,
    });
    firstDatabase.close();
    TestBed.resetTestingModule();

    const reopenedDatabase = openDatabase(name);
    const scheduler = new ControlledScheduler(clock);
    const resumedTransport: SynchronizationTransport = {
      submitOperation: vi.fn(async (submitted) => acceptedResult(submitted)),
      pullChanges: vi.fn(async () => ({ changes: [], nextCursor: 'resumed', hasMore: false })),
    };
    const resumed = services(reopenedDatabase, resumedTransport, onlineStatus(true), {
      clock,
      scheduler,
    });

    await expect(resumed.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'scheduled',
      attemptCount: 1,
      nextEligibleAt: 5_400,
    });
    expect(resumedTransport.submitOperation).not.toHaveBeenCalled();
    expect(scheduler.delays()).toEqual([400]);

    scheduler.runNext();
    await vi.waitFor(() =>
      expect(resumed.service.status()).toEqual({ status: 'completed', pushed: 1, pulled: 1 }),
    );
    expect(resumedTransport.submitOperation).toHaveBeenCalledWith(operation);
    await expect(reopenedDatabase.synchronizationRetryState.count()).resolves.toBe(0);
  });

  it('does not consume retry attempts while offline and resumes on the online event', async () => {
    const database = openDatabase();
    const operation = createOperation();
    let online = false;
    let onlineListener: (() => void) | undefined;
    const network: NetworkStatus = {
      isOnline: () => online,
      onOnline: (listener) => {
        onlineListener = listener;
        return () => {
          onlineListener = undefined;
        };
      },
    };
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (submitted) => acceptedResult(submitted)),
      pullChanges: vi.fn(async () => ({ changes: [], nextCursor: 'online', hasMore: false })),
    };
    const { persistence, service } = services(database, transport, network);
    await persistence.commitCreate(operation);

    await expect(service.recoverAfterReload()).resolves.toEqual({
      status: 'offline',
      pushed: 0,
      pulled: 0,
    });
    await expect(service.recoverAfterReload()).resolves.toEqual({
      status: 'offline',
      pushed: 0,
      pulled: 0,
    });
    expect(transport.submitOperation).not.toHaveBeenCalled();
    await expect(database.synchronizationRetryState.count()).resolves.toBe(0);
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);

    online = true;
    onlineListener!();
    await vi.waitFor(() =>
      expect(service.status()).toEqual({ status: 'completed', pushed: 1, pulled: 1 }),
    );
    expect(transport.submitOperation).toHaveBeenCalledWith(operation);
  });

  it('retries a pull with the exact persisted opaque cursor', async () => {
    const database = openDatabase();
    const clock = new TestClock(1_000);
    const scheduler = new ControlledScheduler(clock);
    let attempts = 0;
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) throw new SynchronizationUnavailableError();
        return { changes: [], nextCursor: 'next-opaque-cursor', hasMore: false };
      }),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true), {
      clock,
      jitter: { sample: () => 250 },
      scheduler,
    });
    await persistence.commitPulledPage({
      changes: [],
      nextCursor: 'saved-opaque-cursor',
      hasMore: false,
    });

    await expect(service.recoverAfterReload()).resolves.toMatchObject({
      status: 'scheduled',
      phase: 'pull',
      attemptCount: 1,
    });
    expect(scheduler.delays()).toEqual([250]);
    scheduler.runNext();
    await vi.waitFor(() =>
      expect(service.status()).toEqual({ status: 'completed', pushed: 0, pulled: 1 }),
    );
    expect(transport.pullChanges).toHaveBeenNthCalledWith(1, 'saved-opaque-cursor');
    expect(transport.pullChanges).toHaveBeenNthCalledWith(2, 'saved-opaque-cursor');
    await expect(persistence.synchronizationCursor()).resolves.toBe('next-opaque-cursor');
    await expect(database.synchronizationRetryState.count()).resolves.toBe(0);
  });

  it('does not schedule malformed or unexpected responses', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const scheduler = new ControlledScheduler(new TestClock(0));
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => {
        throw new SynchronizationUnexpectedResponseError(503, { message: 'untrusted body' });
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true), {
      scheduler,
    });
    await persistence.commitCreate(operation);

    await expect(service.recoverAfterReload()).resolves.toMatchObject({
      status: 'failed',
      pushed: 0,
      pulled: 0,
    });
    expect(service.status()).toEqual({ status: 'failed', reason: 'unexpected-response' });
    expect(transport.submitOperation).toHaveBeenCalledOnce();
    expect(scheduler.delays()).toEqual([]);
    await expect(database.synchronizationRetryState.count()).resolves.toBe(0);
    await expect(database.outboxOperations.toArray()).resolves.toMatchObject([
      { ...operation, submittedAt: expect.any(Number) },
    ]);
  });

  it.each(['push', 'pull'] as const)(
    'counts one real HTTP 503 attempt for %s and recovers unchanged work',
    async (phase) => {
      const database = openDatabase();
      const clock = new TestClock(0);
      const scheduler = new ControlledScheduler(clock);
      const { persistence, service, http } = httpServices(database, scheduler, clock);
      const operation = createOperation();
      if (phase === 'push') await persistence.commitCreate(operation);
      else
        await persistence.commitPulledPage({
          changes: [],
          nextCursor: 'saved-cursor',
          hasMore: false,
        });

      const recovery = service.recoverAfterReload();
      const path =
        phase === 'push' ? '/api/v1/sync/operations' : '/api/v1/sync/changes?cursor=saved-cursor';
      let request: ReturnType<HttpTestingController['expectOne']> | undefined;
      await vi.waitFor(() => {
        request = http.expectOne(path);
      });
      request!.flush(
        {
          code: 'SYNCHRONIZATION_UNAVAILABLE',
          message: 'The synchronization service is unavailable.',
        },
        {
          status: 503,
          statusText: 'Service Unavailable',
          headers: { 'Retry-After': '1', 'Cache-Control': 'no-store' },
        },
      );
      await expect(recovery).resolves.toMatchObject({
        status: 'scheduled',
        phase,
        attemptCount: 1,
      });
      expect(scheduler.delays()).toEqual([250]);
      expect((await database.synchronizationRetryState.toArray())[0]).toMatchObject({
        attemptCount: 1,
        failureCategory: 'unavailable',
      });
      if (phase === 'push')
        await expect(database.outboxOperations.toArray()).resolves.toMatchObject([
          { ...operation, submittedAt: expect.any(Number) },
        ]);
      else await expect(persistence.synchronizationCursor()).resolves.toBe('saved-cursor');
      http.verify();

      scheduler.runNext();
      await vi.waitFor(() => {
        request = http.expectOne(path);
      });
      if (phase === 'push') {
        expect(request!.request.body).toEqual(operation);
        request!.flush(acceptedResult(operation));
        await vi.waitFor(() => {
          request = http.expectOne('/api/v1/sync/changes');
        });
      }
      request!.flush({ changes: [], nextCursor: 'recovered-cursor', hasMore: false });
      await vi.waitFor(() =>
        expect(service.status()).toEqual({
          status: 'completed',
          pushed: phase === 'push' ? 1 : 0,
          pulled: 1,
        }),
      );
      await expect(database.synchronizationRetryState.count()).resolves.toBe(0);
      await expect(database.outboxOperations.count()).resolves.toBe(0);
      await expect(persistence.synchronizationCursor()).resolves.toBe('recovered-cursor');
      http.verify();
    },
  );

  it('exhausts five real HTTP 503 attempts and permits explicit manual recovery', async () => {
    const database = openDatabase();
    const clock = new TestClock(0);
    const scheduler = new ControlledScheduler(clock);
    const { persistence, service, http } = httpServices(database, scheduler, clock);
    const operation = createOperation();
    await persistence.commitCreate(operation);
    const recovery = service.recoverAfterReload();
    for (let attemptCount = 1; attemptCount <= 5; attemptCount++) {
      const request = await nextHttpRequest(http, '/api/v1/sync/operations');
      expect(request.request.body).toEqual(operation);
      request.flush(null, { status: 503, statusText: 'Service Unavailable' });
      if (attemptCount === 1) await recovery;
      await vi.waitFor(() =>
        expect(service.status()).toMatchObject({
          status: attemptCount === 5 ? 'exhausted' : 'scheduled',
          attemptCount,
        }),
      );
      if (attemptCount < 5) scheduler.runNext();
    }
    expect(scheduler.delays()).toEqual([]);
    await expect(database.outboxOperations.toArray()).resolves.toMatchObject([
      { ...operation, submittedAt: expect.any(Number) },
    ]);
    expect((await database.synchronizationRetryState.toArray())[0]).toMatchObject({
      attemptCount: 5,
      exhausted: true,
    });
    http.verify();

    const manual = service.retryNow();
    const replay = await nextHttpRequest(http, '/api/v1/sync/operations');
    expect(replay.request.body).toEqual(operation);
    replay.flush(acceptedResult(operation));
    (await nextHttpRequest(http, '/api/v1/sync/changes')).flush({
      changes: [],
      nextCursor: 'manual-cursor',
      hasMore: false,
    });
    await expect(manual).resolves.toEqual({ status: 'completed', pushed: 1, pulled: 1 });
    await expect(database.synchronizationRetryState.count()).resolves.toBe(0);
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    http.verify();
  });

  it('does not retry a real HTTP 500 response', async () => {
    const database = openDatabase();
    const clock = new TestClock(0);
    const scheduler = new ControlledScheduler(clock);
    const { persistence, service, http } = httpServices(database, scheduler, clock);
    const operation = createOperation();
    await persistence.commitCreate(operation);
    const recovery = service.recoverAfterReload();
    let request: ReturnType<HttpTestingController['expectOne']> | undefined;
    await vi.waitFor(() => {
      request = http.expectOne('/api/v1/sync/operations');
    });
    request!.flush(null, { status: 500, statusText: 'Internal Server Error' });
    await expect(recovery).resolves.toMatchObject({ status: 'failed' });
    expect(service.status()).toEqual({ status: 'failed', reason: 'unexpected-response' });
    expect(scheduler.delays()).toEqual([]);
    await expect(database.synchronizationRetryState.count()).resolves.toBe(0);
    await expect(database.outboxOperations.toArray()).resolves.toMatchObject([
      { ...operation, submittedAt: expect.any(Number) },
    ]);
    http.verify();
  });

  function openDatabase(name = databaseName()): HortinisDatabase {
    const database = new HortinisDatabase(name);
    databases.push(database);
    return database;
  }
});

function services(
  database: HortinisDatabase,
  transport: SynchronizationTransport | undefined,
  network: NetworkStatus,
  runtime: {
    clock?: SynchronizationClock;
    jitter?: SynchronizationJitter;
    scheduler?: SynchronizationScheduler;
  } = {},
): { persistence: TechnicalRecordPersistence; service: TechnicalRecordSynchronizationService } {
  TestBed.configureTestingModule({
    providers: [
      ...(transport ? [] : [provideHttpClient(), provideHttpClientTesting()]),
      { provide: HortinisDatabase, useValue: database },
      transport
        ? { provide: SYNCHRONIZATION_TRANSPORT, useValue: transport }
        : { provide: SYNCHRONIZATION_TRANSPORT, useClass: HttpSynchronizationTransport },
      { provide: NETWORK_STATUS, useValue: network },
      { provide: SYNCHRONIZATION_CLOCK, useValue: runtime.clock ?? new TestClock(0) },
      { provide: SYNCHRONIZATION_JITTER, useValue: runtime.jitter ?? { sample: () => 0 } },
      {
        provide: SYNCHRONIZATION_SCHEDULER,
        useValue: runtime.scheduler ?? { schedule: () => () => undefined },
      },
    ],
  });
  return {
    persistence: TestBed.inject(TechnicalRecordPersistence),
    service: TestBed.inject(TechnicalRecordSynchronizationService),
  };
}

function httpServices(
  database: HortinisDatabase,
  scheduler: ControlledScheduler,
  clock: TestClock,
) {
  const result = services(database, undefined, onlineStatus(true), {
    clock,
    scheduler,
    jitter: { sample: () => 250 },
  });
  return { ...result, http: TestBed.inject(HttpTestingController) };
}

async function nextHttpRequest(http: HttpTestingController, path: string) {
  let request: ReturnType<HttpTestingController['expectOne']> | undefined;
  await vi.waitFor(() => {
    request = http.expectOne(path);
  });
  return request!;
}

class TestClock implements SynchronizationClock {
  constructor(private currentTime: number) {}

  now(): number {
    return this.currentTime;
  }

  advance(milliseconds: number): void {
    this.currentTime += milliseconds;
  }
}

class ControlledScheduler implements SynchronizationScheduler {
  private readonly scheduled: {
    task: () => void;
    delay: number;
    cancelled: boolean;
  }[] = [];

  constructor(private readonly clock: TestClock) {}

  schedule(task: () => void, delay: number): () => void {
    const entry = { task, delay, cancelled: false };
    this.scheduled.push(entry);
    return () => {
      entry.cancelled = true;
    };
  }

  delays(): number[] {
    return this.scheduled.filter(({ cancelled }) => !cancelled).map(({ delay }) => delay);
  }

  runNext(): void {
    const index = this.scheduled.findIndex(({ cancelled }) => !cancelled);
    if (index < 0) throw new Error('No synchronization retry is scheduled.');
    const [entry] = this.scheduled.splice(index, 1);
    this.clock.advance(entry.delay);
    entry.task();
  }
}

function createOperation(): TechnicalRecordOperation & { kind: 'create' } {
  return {
    operationId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
    recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
    value: 'private record value',
    kind: 'create',
  };
}

function acceptedResult(operation: TechnicalRecordOperation): OperationResult {
  if (operation.kind === 'delete') throw new Error('This fixture accepts live records only.');
  return {
    outcome: 'accepted',
    operationId: operation.operationId,
    record: { recordId: operation.recordId, revision: '1', value: operation.value },
    sequence: '1',
  };
}

function onlineStatus(isOnline: boolean): NetworkStatus {
  return { isOnline: () => isOnline };
}

function databaseName(): string {
  return `hortinis-g2d-${crypto.randomUUID()}`;
}
