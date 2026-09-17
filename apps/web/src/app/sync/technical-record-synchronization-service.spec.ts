import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { describe, expect, it, vi, afterEach } from 'vitest';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { TechnicalRecordSynchronizationService } from './technical-record-synchronization-service';
import type { ChangePage, OperationResult, TechnicalRecordOperation } from './conformance';
import type { SynchronizationTransport } from './synchronization-transport';
import type { NetworkStatus } from './network-status';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { NETWORK_STATUS } from './network-status';
import type { DeferredReplaceTechnicalRecordOperation } from '../persistence/local-technical-record-operation';

describe('TechnicalRecordSynchronizationService', () => {
  const databases: Dexie[] = [];

  afterEach(async () => {
    TestBed.resetTestingModule();
    await Promise.all(
      databases.splice(0).map(async (database) => {
        database.close();
        await database.delete();
      }),
    );
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
        throw new Error('service unavailable');
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.pushOnePendingOperation()).resolves.toMatchObject({ status: 'failed' });
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);
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
        throw new Error('service unavailable');
      }),
      pullChanges: vi.fn(),
    };
    const { persistence, service } = services(database, transport, onlineStatus(true));
    await persistence.commitCreate(operation);

    await expect(service.recoverAfterReload()).resolves.toMatchObject({
      status: 'failed',
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
  ): { persistence: TechnicalRecordPersistence; service: TechnicalRecordSynchronizationService } {
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: SYNCHRONIZATION_TRANSPORT, useValue: transport },
        { provide: NETWORK_STATUS, useValue: network },
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
