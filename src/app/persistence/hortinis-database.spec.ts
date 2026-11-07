import 'fake-indexeddb/auto';

import Dexie from 'dexie';
import { HortinisDatabase } from './hortinis-database';
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
    expect(database.verno).toBe(2);
    expect(database.tables.map((table) => table.name).sort()).toEqual([
      'configuration',
      'garden',
      'space',
    ]);
  });

  it('preserves gardens and setup state when removing synchronization stores', async () => {
    const name = databaseName();
    const legacy = track(new Dexie(name));
    legacy.version(1).stores({
      garden: 'id, name',
      space: 'id, gardenId, type, name',
      configuration: 'id, sync, serverUrl',
      technicalRecords: 'recordId',
      outboxOperations: 'operationId, [entityType+entityId]',
      acceptedOperationResults: 'operationId',
      revisionConflicts: 'operationId',
      synchronizationState: 'scope',
    });
    const garden = { id: 'existing-garden', name: 'Existing garden', enabled: true };
    await legacy.table('garden').add(garden);
    await legacy.table('configuration').add({ id: 'existing-configuration', sync: false });
    await legacy.table('outboxOperations').add({ operationId: 'obsolete-operation' });
    legacy.close();

    const upgraded = track(new HortinisDatabase(name));
    await upgraded.open();

    await expect(upgraded.garden.toArray()).resolves.toEqual([garden]);
    await expect(upgraded.configuration.toArray()).resolves.toEqual([
      { id: 'existing-configuration', sync: false },
    ]);
    expect(upgraded.tables.map((table) => table.name).sort()).toEqual([
      'configuration',
      'garden',
      'space',
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
