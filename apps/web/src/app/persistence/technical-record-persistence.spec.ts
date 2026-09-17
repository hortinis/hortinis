import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { HortinisDatabase } from './hortinis-database';
import { TechnicalRecordPersistence } from './technical-record-persistence';
import { TechnicalRecordLocalService } from '../sync/technical-record-local-service';
import { TechnicalRecordSynchronizationService } from '../sync/technical-record-synchronization-service';
import type { CreateTechnicalRecordOperation } from '../sync/conformance';
import type { DeferredReplaceTechnicalRecordOperation } from './local-technical-record-operation';
import { vi } from 'vitest';

describe('technical record local persistence', () => {
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

  it('commits a local projection and its outbox operation together', async () => {
    const database = openDatabase();
    const service = localService(database);

    const result = await service.create('first value');

    await expect(database.technicalRecords.toArray()).resolves.toEqual([result.record]);
    await expect(database.outboxOperations.toArray()).resolves.toEqual([result.operation]);
    expect(result.record.lastAcceptedRevision).toBeNull();
    expect(result.operation.kind).toBe('create');
  });

  it('starts one synchronization push after a successful local commit', async () => {
    const database = openDatabase();
    const synchronization = {
      pushOnePendingOperation: vi.fn(async () => ({ status: 'empty' as const })),
    } as unknown as TechnicalRecordSynchronizationService;
    const service = localService(database, synchronization);

    await service.create('first value');
    await vi.waitFor(() => expect(synchronization.pushOnePendingOperation).toHaveBeenCalledOnce());
  });

  it('rolls back the local projection when the outbox write fails', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const existing = operation('existing-record', 'existing-operation');
    await database.outboxOperations.add(existing);

    const attempted = operation('new-record', existing.operationId);

    await expect(persistence.commitCreate(attempted)).rejects.toBeDefined();
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.outboxOperations.toArray()).resolves.toEqual([existing]);
  });

  it('does not add an outbox operation when the local projection write fails', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const existing = operation('existing-record', 'existing-operation');
    await database.technicalRecords.add({
      recordId: existing.recordId,
      value: existing.value,
      lastAcceptedRevision: null,
    });

    await expect(persistence.commitCreate(existing)).rejects.toBeDefined();
    await expect(database.outboxOperations.count()).resolves.toBe(0);
  });

  it('retains the pending operation after closing and reopening the database', async () => {
    const name = databaseName();
    const firstDatabase = openDatabase(name);
    const service = localService(firstDatabase);
    const result = await service.create('retained value');
    firstDatabase.close();

    const reopenedDatabase = openDatabase(name);
    await expect(reopenedDatabase.outboxOperations.toArray()).resolves.toEqual([result.operation]);
    await expect(reopenedDatabase.technicalRecords.toArray()).resolves.toEqual([result.record]);
  });

  it('generates distinct canonical UUIDv7 identifiers for each local create', async () => {
    const database = openDatabase();
    const service = localService(database);

    const result = await service.create('generated value');
    const uuidV7Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

    expect(result.operation.operationId).toMatch(uuidV7Pattern);
    expect(result.operation.recordId).toMatch(uuidV7Pattern);
    expect(result.operation.operationId).not.toBe(result.operation.recordId);
  });

  it('stores an accepted result before removing its pending operation', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const operation = operationWithIds('accepted-record', 'accepted-operation');
    await persistence.commitCreate(operation);
    const result = {
      outcome: 'accepted' as const,
      operationId: operation.operationId,
      record: { recordId: operation.recordId, revision: '1', value: operation.value },
      sequence: '1',
    };

    await persistence.commitAcceptedResult(operation, result);

    await expect(database.outboxOperations.count()).resolves.toBe(0);
    await expect(database.acceptedOperationResults.toArray()).resolves.toEqual([result]);
    await expect(database.technicalRecords.toArray()).resolves.toEqual([
      { recordId: operation.recordId, value: operation.value, lastAcceptedRevision: '1' },
    ]);
  });

  it('persists a revision conflict while retaining the exact local proposal', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const operation = {
      operationId: '01890f3e-7c5a-7b11-8abc-0123456789ab',
      recordId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
      value: 'local proposal',
      kind: 'replace' as const,
      expectedRevision: '1',
    };
    const conflict = {
      code: 'REVISION_CONFLICT' as const,
      message: 'The expected revision does not match the current revision.',
      operationId: operation.operationId,
      expectedRevision: operation.expectedRevision,
      currentRecord: {
        recordId: operation.recordId,
        revision: '2',
        value: 'server value',
      },
    };
    await database.technicalRecords.add({
      recordId: operation.recordId,
      value: operation.value,
      lastAcceptedRevision: operation.expectedRevision,
    });
    await database.outboxOperations.add(operation);

    await persistence.commitRevisionConflict(operation, conflict);

    await expect(database.revisionConflicts.get(operation.operationId)).resolves.toEqual(conflict);
    await expect(database.outboxOperations.get(operation.operationId)).resolves.toEqual(operation);
    await expect(database.technicalRecords.get(operation.recordId)).resolves.toEqual({
      recordId: operation.recordId,
      value: operation.value,
      lastAcceptedRevision: operation.expectedRevision,
    });
  });

  it('retains accepted results and the synchronization cursor after reopening', async () => {
    const name = databaseName();
    const firstDatabase = openDatabase(name);
    const persistence = persistenceFor(firstDatabase);
    const operation = operationWithIds('reload-record', 'reload-operation');
    const result = {
      outcome: 'accepted' as const,
      operationId: operation.operationId,
      record: { recordId: operation.recordId, revision: '1', value: operation.value },
      sequence: '1',
    };

    await persistence.commitCreate(operation);
    await persistence.commitAcceptedResult(operation, result);
    await persistence.commitPulledPage({
      changes: [],
      nextCursor: 'opaque-reload-cursor',
      hasMore: false,
    });
    firstDatabase.close();

    const reopenedDatabase = openDatabase(name);
    await expect(
      reopenedDatabase.acceptedOperationResults.get(operation.operationId),
    ).resolves.toEqual(result);
    await expect(reopenedDatabase.synchronizationState.get('technical-records')).resolves.toEqual({
      scope: 'technical-records',
      cursor: 'opaque-reload-cursor',
    });
  });

  it('persists a dependent replacement with an unresolved revision', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const predecessor = operationWithIds('dependent-record', 'predecessor-operation');
    await persistence.commitCreate(predecessor);

    const result = await persistence.commitReplace(
      'successor-operation',
      predecessor.recordId,
      'successor value',
    );

    const deferred: DeferredReplaceTechnicalRecordOperation = {
      operationId: 'successor-operation',
      recordId: predecessor.recordId,
      value: 'successor value',
      kind: 'replace',
      expectedRevision: null,
      predecessorOperationId: predecessor.operationId,
    };
    expect(result.operation).toEqual(deferred);
    await expect(database.outboxOperations.toArray()).resolves.toEqual([predecessor, deferred]);
    await expect(database.technicalRecords.get(predecessor.recordId)).resolves.toEqual({
      recordId: predecessor.recordId,
      value: 'successor value',
      lastAcceptedRevision: null,
    });
  });

  it('exposes the dependent replacement through the local workflow', async () => {
    const database = openDatabase();
    const synchronization = {
      pushOnePendingOperation: vi.fn(async () => ({ status: 'empty' as const })),
    } as unknown as TechnicalRecordSynchronizationService;
    const service = localService(database, synchronization);

    const created = await service.create('first value');
    const replaced = await service.replace(created.record.recordId, 'second value');

    expect(replaced.record.value).toBe('second value');
    expect(replaced.operation).toMatchObject({
      recordId: created.record.recordId,
      value: 'second value',
      kind: 'replace',
      expectedRevision: null,
      predecessorOperationId: created.operation.operationId,
    });
    await vi.waitFor(() =>
      expect(synchronization.pushOnePendingOperation).toHaveBeenCalledTimes(2),
    );
  });

  it('atomically resolves a dependent successor and preserves its local value', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const predecessor = operationWithIds('resolve-record', 'resolve-predecessor');
    await persistence.commitCreate(predecessor);
    await persistence.commitReplace('resolve-successor', predecessor.recordId, 'successor value');

    const predecessorResult = {
      outcome: 'accepted' as const,
      operationId: predecessor.operationId,
      record: { recordId: predecessor.recordId, revision: '1', value: predecessor.value },
      sequence: '1',
    };
    await persistence.commitAcceptedResult(predecessor, predecessorResult);

    await expect(database.outboxOperations.toArray()).resolves.toEqual([
      {
        operationId: 'resolve-successor',
        recordId: predecessor.recordId,
        value: 'successor value',
        kind: 'replace',
        expectedRevision: '1',
        predecessorOperationId: predecessor.operationId,
      },
    ]);
    await expect(database.acceptedOperationResults.toArray()).resolves.toEqual([predecessorResult]);
    await expect(database.technicalRecords.get(predecessor.recordId)).resolves.toEqual({
      recordId: predecessor.recordId,
      value: 'successor value',
      lastAcceptedRevision: '1',
    });
  });

  it('rolls back predecessor acceptance when successor resolution fails', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const predecessor = operationWithIds('rollback-chain-record', 'rollback-predecessor');
    await persistence.commitCreate(predecessor);
    const successor = {
      operationId: 'rollback-successor',
      recordId: predecessor.recordId,
      value: 'successor value',
      kind: 'replace' as const,
      expectedRevision: null,
      predecessorOperationId: predecessor.operationId,
    } satisfies DeferredReplaceTechnicalRecordOperation;
    await database.outboxOperations.add(successor);
    await database.outboxOperations.add({
      ...successor,
      operationId: 'second-successor',
    });
    await database.technicalRecords.put({
      recordId: predecessor.recordId,
      value: successor.value,
      lastAcceptedRevision: null,
    });

    const predecessorResult = {
      outcome: 'accepted' as const,
      operationId: predecessor.operationId,
      record: { recordId: predecessor.recordId, revision: '1', value: predecessor.value },
      sequence: '1',
    };
    await expect(persistence.commitAcceptedResult(predecessor, predecessorResult)).rejects.toThrow(
      'more than one successor',
    );

    await expect(database.acceptedOperationResults.count()).resolves.toBe(0);
    await expect(database.outboxOperations.count()).resolves.toBe(3);
    await expect(database.technicalRecords.get(predecessor.recordId)).resolves.toMatchObject({
      value: successor.value,
      lastAcceptedRevision: null,
    });
  });

  it('rolls back the accepted result when local accepted-state persistence fails', async () => {
    const database = openDatabase();
    const persistence = persistenceFor(database);
    const operation = operationWithIds('missing-record', 'failed-acceptance');
    await persistence.commitCreate(operation);
    await database.technicalRecords.delete(operation.recordId);
    const result = {
      outcome: 'accepted' as const,
      operationId: operation.operationId,
      record: { recordId: operation.recordId, revision: '1', value: operation.value },
      sequence: '1',
    };

    await expect(persistence.commitAcceptedResult(operation, result)).rejects.toThrow(
      'local record',
    );
    await expect(database.acceptedOperationResults.count()).resolves.toBe(0);
    await expect(database.outboxOperations.toArray()).resolves.toEqual([operation]);
  });

  function openDatabase(name = databaseName()): HortinisDatabase {
    const database = new HortinisDatabase(name);
    databases.push(database);
    return database;
  }

  function persistenceFor(database: HortinisDatabase): TechnicalRecordPersistence {
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: database }],
    });
    return TestBed.inject(TechnicalRecordPersistence);
  }

  function localService(
    database: HortinisDatabase,
    synchronization: TechnicalRecordSynchronizationService = {
      pushOnePendingOperation: vi.fn(async () => ({ status: 'empty' as const })),
    } as unknown as TechnicalRecordSynchronizationService,
  ): TechnicalRecordLocalService {
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: TechnicalRecordSynchronizationService, useValue: synchronization },
      ],
    });
    return TestBed.inject(TechnicalRecordLocalService);
  }
});

function operation(recordId: string, operationId: string): CreateTechnicalRecordOperation {
  return { operationId, recordId, value: `${recordId}-value`, kind: 'create' };
}

function operationWithIds(recordId: string, operationId: string): CreateTechnicalRecordOperation {
  return operation(recordId, operationId);
}

function databaseName(): string {
  return `hortinis-e2-${crypto.randomUUID()}`;
}
