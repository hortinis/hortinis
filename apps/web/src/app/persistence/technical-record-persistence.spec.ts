import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { HortinisDatabase } from './hortinis-database';
import { TechnicalRecordPersistence } from './technical-record-persistence';
import { TechnicalRecordLocalService } from '../sync/technical-record-local-service';
import { TechnicalRecordSynchronizationService } from '../sync/technical-record-synchronization-service';
import type { CreateTechnicalRecordOperation } from '../sync/conformance';
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
