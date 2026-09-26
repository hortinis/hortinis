import 'fake-indexeddb/auto';

import Dexie from 'dexie';
import { HortinisDatabase } from './hortinis-database';
import { HORTINIS_DATABASE_SCHEMA, HORTINIS_DATABASE_SCHEMA_V1 } from './database-schema';
import {
  LegacyMigrationFixtureDatabase,
  MigrationFixtureDatabase,
} from './testing/migration-fixture-database';

describe('HortinisDatabase', () => {
  const databases: Dexie[] = [];

  afterEach(async () => {
    await Promise.all(
      databases.splice(0).map(async (database) => {
        database.close();
        await database.delete();
      }),
    );
  });

  it('creates the initial application database', async () => {
    const database = track(new HortinisDatabase(databaseName()));

    await database.open();

    expect(database.isOpen()).toBe(true);
    expect(database.verno).toBe(3);
    expect(database.tables.map((table) => table.name)).toEqual([
      'technicalRecords',
      'outboxOperations',
      'acceptedOperationResults',
      'revisionConflicts',
      'synchronizationState',
      'technicalTombstones',
      'pendingDeletionRecords',
      'deletionConflicts',
      'synchronizationRetryState',
    ]);
  });

  it('runs a migration when upgrading an older database', async () => {
    const name = databaseName();
    const legacyDatabase = track(new LegacyMigrationFixtureDatabase(name));
    await legacyDatabase.open();
    await legacyDatabase.migrationProbes.add({ createdAt: '2026-09-14T00:00:00Z' });
    legacyDatabase.close();

    const upgradedDatabase = track(new MigrationFixtureDatabase(name));
    await upgradedDatabase.open();

    expect(upgradedDatabase.verno).toBe(2);
    await expect(upgradedDatabase.migrationProbes.toArray()).resolves.toEqual([
      {
        id: 1,
        createdAt: '2026-09-14T00:00:00Z',
        migrationState: 'migrated',
      },
    ]);
  });

  it('upgrades a populated application database without losing pending work or its cursor', async () => {
    const name = databaseName();
    const oldDatabase = new Dexie(name);
    oldDatabase.version(1).stores(HORTINIS_DATABASE_SCHEMA_V1);
    await oldDatabase.open();
    const record = { recordId: 'legacy-record', value: 'local value', lastAcceptedRevision: '1' };
    const operation = {
      operationId: 'legacy-operation',
      recordId: record.recordId,
      kind: 'replace',
      value: 'local value',
      expectedRevision: '1',
    };
    await oldDatabase.table('technicalRecords').add(record);
    await oldDatabase.table('outboxOperations').add(operation);
    await oldDatabase
      .table('synchronizationState')
      .add({ scope: 'technical-records', cursor: 'saved-cursor' });
    oldDatabase.close();

    const upgraded = track(new HortinisDatabase(name));
    await upgraded.open();
    expect(upgraded.verno).toBe(3);
    await expect(upgraded.technicalRecords.toArray()).resolves.toEqual([record]);
    await expect(upgraded.outboxOperations.toArray()).resolves.toEqual([operation]);
    await expect(upgraded.synchronizationState.get('technical-records')).resolves.toMatchObject({
      cursor: 'saved-cursor',
    });
    await expect(upgraded.technicalTombstones.count()).resolves.toBe(0);
    await expect(upgraded.pendingDeletionRecords.count()).resolves.toBe(0);
    await expect(upgraded.deletionConflicts.count()).resolves.toBe(0);
    await expect(upgraded.synchronizationRetryState.count()).resolves.toBe(0);
  });

  it('adds retry state to a populated version-two database without losing tombstones', async () => {
    const name = databaseName();
    const oldDatabase = new Dexie(name);
    oldDatabase.version(1).stores(HORTINIS_DATABASE_SCHEMA_V1);
    oldDatabase.version(2).stores(HORTINIS_DATABASE_SCHEMA);
    await oldDatabase.open();
    const tombstone = {
      recordId: 'retired-record',
      revision: '2',
      deletedAtSequence: '8',
    };
    await oldDatabase.table('technicalTombstones').add(tombstone);
    await oldDatabase
      .table('synchronizationState')
      .add({ scope: 'technical-records', cursor: 'version-two-cursor' });
    oldDatabase.close();

    const upgraded = track(new HortinisDatabase(name));
    await upgraded.open();

    expect(upgraded.verno).toBe(3);
    await expect(upgraded.technicalTombstones.toArray()).resolves.toEqual([tombstone]);
    await expect(upgraded.synchronizationState.get('technical-records')).resolves.toMatchObject({
      cursor: 'version-two-cursor',
    });
    await expect(upgraded.synchronizationRetryState.count()).resolves.toBe(0);
  });

  it('rolls back writes when a transaction fails', async () => {
    const database = track(new MigrationFixtureDatabase(databaseName()));
    await database.open();

    await expect(
      database.transaction('rw', database.migrationProbes, async () => {
        await database.migrationProbes.add({
          createdAt: '2026-09-14T00:00:00Z',
          migrationState: 'migrated',
        });
        throw new Error('Force transaction rollback');
      }),
    ).rejects.toThrow('Force transaction rollback');

    await expect(database.migrationProbes.count()).resolves.toBe(0);
  });

  it('keeps uniquely named test databases isolated', async () => {
    const first = track(new MigrationFixtureDatabase(databaseName()));
    const second = track(new MigrationFixtureDatabase(databaseName()));
    await Promise.all([first.open(), second.open()]);

    await first.migrationProbes.add({
      createdAt: '2026-09-14T00:00:00Z',
      migrationState: 'migrated',
    });

    await expect(first.migrationProbes.count()).resolves.toBe(1);
    await expect(second.migrationProbes.count()).resolves.toBe(0);
  });

  function track<T extends Dexie>(database: T): T {
    databases.push(database);
    return database;
  }
});

function databaseName(): string {
  return `hortinis-b8-${crypto.randomUUID()}`;
}
