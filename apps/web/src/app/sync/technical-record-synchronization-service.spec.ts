import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { TechnicalRecordSynchronizationService } from './technical-record-synchronization-service';
import { TechnicalRecordLocalService } from './technical-record-local-service';
import type { ChangePage, OperationResult, TechnicalRecordOperation } from './conformance';
import type { SynchronizationTransport } from './synchronization-transport';
import {
  SynchronizationProtocolError,
  SynchronizationUnavailableError,
} from './synchronization-transport';
import type { NetworkStatus } from './network-status';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { NETWORK_STATUS } from './network-status';
import type { DeferredReplaceTechnicalRecordOperation } from '../persistence/local-technical-record-operation';
import {
  SYNCHRONIZATION_CLOCK,
  SYNCHRONIZATION_JITTER,
  SYNCHRONIZATION_SCHEDULER,
  type SynchronizationClock,
  type SynchronizationJitter,
  type SynchronizationScheduler,
} from './synchronization-runtime';

describe('TechnicalRecordSynchronizationService', () => {
  const databases: Dexie[] = [];

  afterEach(async () => {
    TestBed.resetTestingModule();
    for (const database of databases.splice(0).reverse()) {
      database.close();
      await database.delete();
    }
  });

  it('pushes one pending operation and commits its stable result', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const result = acceptedResult(operation);
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => result),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'accepted' });
    expect(transport.submitOperation).toHaveBeenCalledOnce();
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.get(operation.operationId)).resolves.toEqual(
      result,
    );
  });

  it('pulls a page using the persisted cursor and commits the page', async () => {
    const database = openDatabase();
    const page: ChangePage = {
      changes: [],
      nextCursor: 'opaque-cursor',
      hasMore: false,
    };
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(async (cursor) => {
        expect(cursor).toBeUndefined();
        return page;
      }),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));

    await expect(service.pullOnePage()).resolves.toEqual({ status: 'applied', page });
    expect(transport.pullChanges).toHaveBeenCalledOnce();
    await expect(persistence.synchronizationCursor()).resolves.toBe('opaque-cursor');
  });

  it('quarantines a deletion rejected because its record is missing', async () => {
    const database = openDatabase();
    await database.technicalRecords.add({
      recordId: createOperation().recordId,
      value: 'local',
      lastAcceptedRevision: '1',
    });
    const error = {
      code: 'RECORD_NOT_FOUND' as const,
      message: 'missing',
      operationId: createOperation().operationId,
      recordId: createOperation().recordId,
    };
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => {
        throw new SynchronizationProtocolError(404, error);
      }),
      pullChanges: vi.fn(async () => ({ changes: [], nextCursor: 'cursor', hasMore: false })),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitDelete(error.operationId, error.recordId);
    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'conflict' });
    await expect(database.outboxOperations.count()).resolves.toBe(1);
    await expect(database.deletionConflicts.get(error.operationId)).resolves.toMatchObject({
      reason: 'record-not-found',
    });
    await expect(persistence.firstPendingOperation()).resolves.toBeUndefined();
  });

  it('resumes a failed page from the last committed cursor after reopening', async () => {
    const name = `hortinis-g2c-recovery-${crypto.randomUUID()}`;
    const first = openDatabase(name);
    const firstPage: ChangePage = { changes: [], nextCursor: 'committed-cursor', hasMore: true };
    const invalidPage: ChangePage = {
      changes: [
        {
          operationId: createOperation().operationId,
          record: { recordId: createOperation().recordId, revision: '4', value: 'temporary' },
          sequence: '40',
        },
        {
          operationId: '01890f3e-7c5a-7b17-8abc-0123456789ab',
          tombstone: {
            recordId: createOperation().recordId,
            revision: '3',
            deletedAtSequence: '41',
          },
          sequence: '41',
        },
      ],
      nextCursor: 'uncommitted-cursor',
      hasMore: false,
    };
    const firstTransport: SynchronizationTransport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(async (cursor) => (cursor ? invalidPage : firstPage)),
    };
    const firstServices = services(first, firstTransport, onlineStatus(true));
    await expect(firstServices.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'scheduled',
      pulled: 1,
    });
    await expect(firstServices.persistence.synchronizationCursor()).resolves.toBe(
      'committed-cursor',
    );
    first.close();
    TestBed.resetTestingModule();

    const reopened = openDatabase(name);
    const nextTransport: SynchronizationTransport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(async (cursor) => {
        expect(cursor).toBe('committed-cursor');
        return { changes: [], nextCursor: 'finished-cursor', hasMore: false };
      }),
    };
    const resumed = services(reopened, nextTransport, onlineStatus(true));
    await expect(resumed.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'completed',
      pulled: 1,
    });
    await expect(resumed.persistence.synchronizationCursor()).resolves.toBe('finished-cursor');
  });

  it('skips a pull while offline and retains the cursor', async () => {
    const database = openDatabase();
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(false));

    await expect(service.pullOnePage()).resolves.toEqual({ status: 'offline' });
    expect(transport.pullChanges).not.toHaveBeenCalled();
    await expect(persistence.synchronizationCursor()).resolves.toBeUndefined();
  });

  it('skips the request while offline and retains pending work', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(false));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toEqual({ status: 'offline' });
    expect(transport.submitOperation).not.toHaveBeenCalled();
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);
  });

  it('retains pending work when transport or result persistence fails', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => {
        throw new SynchronizationUnavailableError();
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'failed' });
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);
  });

  it('keeps accepting independent local work while synchronization is offline', async () => {
    const database = openDatabase();
    let online = false;
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (operation) => acceptedResult(operation)),
      pullChanges: vi.fn(async () => ({ changes: [], nextCursor: 'cursor', hasMore: false })),
    };
    const { service } = services(database, transport, { isOnline: () => online });
    const local = TestBed.inject(TechnicalRecordLocalService);

    const first = await local.create('offline first');
    const second = await local.create('offline second');
    await vi.waitFor(() => expect(service.status()).toEqual({ status: 'offline' }));

    expect(first.record.value).toBe('offline first');
    expect(second.record.value).toBe('offline second');
    await expect(database.technicalRecords.count()).resolves.toBe(2);
    await expect(database.outboxOperations.count()).resolves.toBe(2);
    expect(transport.submitOperation).not.toHaveBeenCalled();

    online = true;
    await expect(service.recoverAfterReload()).resolves.toMatchObject({
      status: 'completed',
      pushed: 2,
    });
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.count()).resolves.toBe(2);
    await expect(database.technicalRecords.count()).resolves.toBe(2);
  });

  it('keeps accepting local work after a failed synchronization and recovers it explicitly', async () => {
    const database = openDatabase();
    let available = false;
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (operation) => {
        if (!available) throw new SynchronizationUnavailableError();
        return acceptedResult(operation);
      }),
      pullChanges: vi.fn(async () => ({ changes: [], nextCursor: 'cursor', hasMore: false })),
    };
    const { service } = services(database, transport, onlineStatus(true), {
      jitter: { sample: () => 500 },
    });
    const local = TestBed.inject(TechnicalRecordLocalService);

    const first = await local.create('failed first');
    await vi.waitFor(() =>
      expect(service.status()).toMatchObject({
        status: 'scheduled',
        phase: 'push',
        attemptCount: 1,
      }),
    );
    const second = await local.create('after failure');
    await expect(database.outboxOperations.count()).resolves.toBe(2);
    expect(transport.submitOperation).toHaveBeenCalledOnce();

    available = true;
    await new Promise((resolve) => setTimeout(resolve, 0));
    await expect(service.retryNow()).resolves.toMatchObject({
      status: 'completed',
      pushed: 2,
    });

    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.count()).resolves.toBe(2);
    expect(transport.submitOperation).toHaveBeenCalledWith(first.operation);
    expect(transport.submitOperation).toHaveBeenCalledWith(second.operation);
  });

  it('persists a revision conflict and continues with an independent operation', async () => {
    const database = openDatabase();
    const conflictedOperation = {
      operationId: '01890f3e-7c5a-7b11-8abc-0123456789ab',
      recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
      value: 'local proposal',
      kind: 'replace' as const,
      expectedRevision: '1',
    };
    const independentOperation = {
      operationId: '01890f3e-7c5a-7b15-8abc-0123456789ab',
      recordId: '01890f3e-7c5a-7b16-8abc-0123456789ab',
      value: 'independent value',
      kind: 'create' as const,
    };
    const conflict = {
      code: 'REVISION_CONFLICT' as const,
      message: 'stale',
      operationId: conflictedOperation.operationId,
      expectedRevision: conflictedOperation.expectedRevision,
      currentRecord: {
        recordId: conflictedOperation.recordId,
        revision: '2',
        value: 'server value',
      },
    };
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (operation) => {
        if (operation.operationId === conflictedOperation.operationId) {
          throw new SynchronizationProtocolError(409, conflict);
        }
        return acceptedResult(operation);
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await database.technicalRecords.add({
      recordId: conflictedOperation.recordId,
      value: conflictedOperation.value,
      lastAcceptedRevision: conflictedOperation.expectedRevision,
    });
    await database.technicalRecords.add({
      recordId: independentOperation.recordId,
      value: independentOperation.value,
      lastAcceptedRevision: null,
    });
    await database.outboxOperations.bulkAdd([conflictedOperation, independentOperation]);

    await expect(service.pushOnePendingOperation()).resolves.toEqual({
      status: 'conflict',
      operation: conflictedOperation,
      conflict,
    });
    await expect(database.revisionConflicts.get(conflictedOperation.operationId)).resolves.toEqual(
      conflict,
    );
    await expect(database.outboxOperations.get(conflictedOperation.operationId)).resolves.toEqual(
      conflictedOperation,
    );

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({
      status: 'accepted',
      operation: independentOperation,
    });
    expect(transport.submitOperation).toHaveBeenCalledTimes(2);
    await expect(database.outboxOperations.get(conflictedOperation.operationId)).resolves.toEqual(
      conflictedOperation,
    );
    await expect(
      database.outboxOperations.get(independentOperation.operationId),
    ).resolves.toBeUndefined();
    await expect(persistence.firstPendingOperation()).resolves.toBeUndefined();
  });

  it('retries the same operation after a lost acknowledgement', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const result = acceptedResult(operation);
    let attempts = 0;
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (submitted) => {
        attempts += 1;
        expect(submitted).toEqual(operation);
        if (attempts === 1) {
          throw new Error('acknowledgement lost');
        }
        return result;
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'failed' });
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({
      status: 'accepted',
      result,
    });
    expect(transport.submitOperation).toHaveBeenCalledTimes(2);
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.get(operation.operationId)).resolves.toEqual(
      result,
    );
  });

  it('submits a dependent create and replace in order with a stable derived revision', async () => {
    const database = openDatabase();
    const predecessor = createOperation();
    const successor: DeferredReplaceTechnicalRecordOperation = {
      operationId: '01890f3e-7c5a-7b11-8abc-0123456789ab',
      recordId: predecessor.recordId,
      value: 'second value',
      kind: 'replace',
      expectedRevision: null,
      predecessorOperationId: predecessor.operationId,
    };
    const submissions: TechnicalRecordOperation[] = [];
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (operation) => {
        submissions.push(operation);
        if (operation.kind === 'create') {
          return acceptedResult(operation);
        }
        expect(operation.expectedRevision).toBe('1');
        return {
          outcome: 'accepted' as const,
          operationId: operation.operationId,
          record: { recordId: operation.recordId, revision: '2', value: operation.value },
          sequence: '2',
        };
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(predecessor);
    await database.outboxOperations.add(successor);
    await database.technicalRecords.put({
      recordId: predecessor.recordId,
      value: successor.value,
      lastAcceptedRevision: null,
    });

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'accepted' });
    expect(submissions).toEqual([predecessor]);
    await expect(database.outboxOperations.get(successor.operationId)).resolves.toMatchObject({
      expectedRevision: '1',
    });
    await expect(database.technicalRecords.get(predecessor.recordId)).resolves.toMatchObject({
      value: successor.value,
      lastAcceptedRevision: '1',
    });

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'accepted' });
    expect(submissions).toEqual([
      predecessor,
      {
        operationId: successor.operationId,
        recordId: successor.recordId,
        value: successor.value,
        kind: 'replace',
        expectedRevision: '1',
      },
    ]);
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.technicalRecords.get(predecessor.recordId)).resolves.toEqual({
      recordId: predecessor.recordId,
      value: successor.value,
      lastAcceptedRevision: '2',
    });
  });

  it('keeps both chain operations unchanged across predecessor and successor retries', async () => {
    const database = openDatabase();
    const predecessor = createOperation();
    const successor = {
      operationId: '01890f3e-7c5a-7b11-8abc-0123456789ab',
      recordId: predecessor.recordId,
      value: 'second value',
      kind: 'replace' as const,
      expectedRevision: null,
      predecessorOperationId: predecessor.operationId,
    } satisfies DeferredReplaceTechnicalRecordOperation;
    let createAttempts = 0;
    let replaceAttempts = 0;
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async (operation) => {
        if (operation.kind === 'create') {
          createAttempts += 1;
          expect(operation).toEqual(predecessor);
          if (createAttempts === 1) throw new Error('lost predecessor acknowledgement');
          return acceptedResult(operation);
        }
        replaceAttempts += 1;
        expect(operation).toEqual({
          operationId: successor.operationId,
          recordId: successor.recordId,
          value: successor.value,
          kind: 'replace',
          expectedRevision: '1',
        });
        if (replaceAttempts === 1) throw new Error('lost successor acknowledgement');
        return {
          outcome: 'accepted' as const,
          operationId: operation.operationId,
          record: { recordId: operation.recordId, revision: '2', value: operation.value },
          sequence: '2',
        };
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(predecessor);
    await persistence.commitReplace(successor.operationId, successor.recordId, successor.value);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'failed' });
    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'accepted' });
    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'failed' });
    await expect(database.outboxOperations.get(successor.operationId)).resolves.toMatchObject({
      operationId: successor.operationId,
      recordId: successor.recordId,
      value: successor.value,
      expectedRevision: '1',
      predecessorOperationId: predecessor.operationId,
    });
    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'accepted' });
    expect(createAttempts).toBe(2);
    expect(replaceAttempts).toBe(2);
  });

  it('recovers all pending operations and pull pages after a reload', async () => {
    const name = `hortinis-recovery-${crypto.randomUUID()}`;
    const firstDatabase = openDatabase(name);
    const operation = createOperation();
    await firstDatabase.technicalRecords.add({
      recordId: operation.recordId,
      value: operation.value,
      lastAcceptedRevision: null,
    });
    await firstDatabase.outboxOperations.add(operation);
    firstDatabase.close();

    const database = openDatabase(name);
    const pages: ChangePage[] = [
      { changes: [], nextCursor: 'cursor-one', hasMore: true },
      { changes: [], nextCursor: 'cursor-two', hasMore: false },
    ];
    const result = acceptedResult(operation);
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => result),
      pullChanges: vi.fn(async (cursor) => {
        expect(cursor).toBe(pages.length === 2 ? undefined : 'cursor-one');
        return pages.shift()!;
      }),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await expect(service.recoverAfterReload()).resolves.toEqual({
      status: 'completed',
      pushed: 1,
      pulled: 2,
    });

    expect(transport.submitOperation).toHaveBeenCalledWith(operation);
    expect(transport.pullChanges).toHaveBeenCalledTimes(2);
    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.get(operation.operationId)).resolves.toEqual(
      result,
    );
    await expect(persistence.synchronizationCursor()).resolves.toBe('cursor-two');
  });

  it('retains durable work when reload recovery is offline or fails', async () => {
    const database = openDatabase();
    const operation = createOperation();
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(async () => {
        throw new SynchronizationUnavailableError();
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.recoverAfterReload()).resolves.toMatchObject({
      status: 'scheduled',
      pushed: 0,
      pulled: 0,
    });
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);
    expect(transport.pullChanges).not.toHaveBeenCalled();
  });

  it('does not start a second reload recovery while one is running', async () => {
    const database = openDatabase();
    const operation = createOperation();
    let resolveSubmission: ((result: OperationResult) => void) | undefined;
    const transport: SynchronizationTransport = {
      submitOperation: vi.fn(
        () =>
          new Promise<OperationResult>((resolve) => {
            resolveSubmission = resolve;
          }),
      ),
      pullChanges: vi.fn(async () => ({ changes: [], nextCursor: 'cursor', hasMore: false })),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    const firstRecovery = service.recoverAfterReload();
    await vi.waitFor(() => expect(transport.submitOperation).toHaveBeenCalledOnce());
    await expect(service.recoverAfterReload()).resolves.toEqual({ status: 'already-running' });
    resolveSubmission!(acceptedResult(operation));
    await expect(firstRecovery).resolves.toMatchObject({ status: 'completed', pushed: 1 });
  });

  function openDatabase(name = `hortinis-e3-${crypto.randomUUID()}`): HortinisDatabase {
    const database = new HortinisDatabase(name);
    databases.push(database);
    return database;
  }

  function services(
    database: HortinisDatabase,
    transport: SynchronizationTransport,
    network: NetworkStatus,
    runtime: {
      clock?: SynchronizationClock;
      jitter?: SynchronizationJitter;
      scheduler?: SynchronizationScheduler;
    } = {},
  ): { persistence: TechnicalRecordPersistence; service: TechnicalRecordSynchronizationService } {
    const clock = runtime.clock ?? { now: () => 0 };
    const jitter = runtime.jitter ?? { sample: () => 0 };
    const scheduler = runtime.scheduler ?? { schedule: () => () => undefined };
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: SYNCHRONIZATION_TRANSPORT, useValue: transport },
        { provide: NETWORK_STATUS, useValue: network },
        { provide: SYNCHRONIZATION_CLOCK, useValue: clock },
        { provide: SYNCHRONIZATION_JITTER, useValue: jitter },
        { provide: SYNCHRONIZATION_SCHEDULER, useValue: scheduler },
      ],
    });
    return {
      persistence: TestBed.inject(TechnicalRecordPersistence),
      service: TestBed.inject(TechnicalRecordSynchronizationService),
    };
  }
});

function createOperation(): TechnicalRecordOperation & { kind: 'create' } {
  return {
    operationId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
    recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
    value: 'first value',
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
