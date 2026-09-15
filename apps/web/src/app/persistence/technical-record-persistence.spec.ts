import 'fake-indexeddb/auto';

import Dexie from 'dexie';
import { HortinisDatabase } from './hortinis-database';
import { TechnicalRecordPersistence } from './technical-record-persistence';
import { TechnicalRecordLocalService } from '../sync/technical-record-local-service';
import type { CreateTechnicalRecordOperation } from '../sync/conformance';

describe('technical record local persistence', () => {
  const databases: Dexie[] = [];

  afterEach(async () => {
    await Promise.all(
      databases.splice(0).map(async (database) => {
        database.close();
        await database.delete();
      }),
    );
  });

  it('commits a local projection and its outbox operation together', async () => {
    const database = openDatabase();
    const service = new TechnicalRecordLocalService(new TechnicalRecordPersistence(database));

    const result = await service.create('first value');

    await expect(database.technicalRecords.toArray()).resolves.toEqual([result.record]);
    await expect(database.outboxOperations.toArray()).resolves.toEqual([result.operation]);
    expect(result.record.lastAcceptedRevision).toBeNull();
    expect(result.operation.kind).toBe('create');
  });

  it('rolls back the local projection when the outbox write fails', async () => {
    const database = openDatabase();
    const persistence = new TechnicalRecordPersistence(database);
    const existing = operation('existing-record', 'existing-operation');
    await database.outboxOperations.add(existing);

    const attempted = operation('new-record', existing.operationId);

    await expect(persistence.commitCreate(attempted)).rejects.toBeDefined();
    await expect(database.technicalRecords.count()).resolves.toBe(0);
    await expect(database.outboxOperations.toArray()).resolves.toEqual([existing]);
  });

  it('does not add an outbox operation when the local projection write fails', async () => {
    const database = openDatabase();
    const persistence = new TechnicalRecordPersistence(database);
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
    const service = new TechnicalRecordLocalService(new TechnicalRecordPersistence(firstDatabase));
    const result = await service.create('retained value');
    firstDatabase.close();

    const reopenedDatabase = openDatabase(name);
    await expect(reopenedDatabase.outboxOperations.toArray()).resolves.toEqual([result.operation]);
    await expect(reopenedDatabase.technicalRecords.toArray()).resolves.toEqual([result.record]);
  });

  it('generates distinct canonical UUIDv7 identifiers for each local create', async () => {
    const database = openDatabase();
    const service = new TechnicalRecordLocalService(new TechnicalRecordPersistence(database));

    const result = await service.create('generated value');
    const uuidV7Pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

    expect(result.operation.operationId).toMatch(uuidV7Pattern);
    expect(result.operation.recordId).toMatch(uuidV7Pattern);
    expect(result.operation.operationId).not.toBe(result.operation.recordId);
  });

  function openDatabase(name = databaseName()): HortinisDatabase {
    const database = new HortinisDatabase(name);
    databases.push(database);
    return database;
  }
});

function operation(recordId: string, operationId: string): CreateTechnicalRecordOperation {
  return { operationId, recordId, value: `${recordId}-value`, kind: 'create' };
}

function databaseName(): string {
  return `hortinis-e2-${crypto.randomUUID()}`;
}
