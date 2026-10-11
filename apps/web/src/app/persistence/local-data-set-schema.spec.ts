import 'fake-indexeddb/auto';
import Dexie from 'dexie';
import { HortinisDatabase } from './hortinis-database';
import {
  HORTINIS_DATABASE_SCHEMA_V1,
  HORTINIS_DATABASE_SCHEMA_V5,
  HORTINIS_DATABASE_SCHEMA_V6,
} from './database-schema';
import { LOCAL_DATA_SET_KEY, readLocalDataSetIdentity } from './local-data-set-identity';

describe('fresh local data-set identity', () => {
  const databases: Dexie[] = [];
  afterEach(async () => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    for (const database of databases.splice(0).reverse()) {
      database.close();
      await database.delete();
    }
  });

  it('creates one UUIDv7 offline and retains it across reload and concurrent connections', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const first = track(new HortinisDatabase(name()));
    const second = track(new HortinisDatabase(first.name));
    await Promise.all([first.open(), second.open()]);
    const identity = await first.requireLocalDataSetId();
    expect(identity).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(await second.requireLocalDataSetId()).toBe(identity);
    expect(await first.localDataSetIdentity.count()).toBe(1);
    expect(await first.localServerBindings.count()).toBe(0);
    first.close();
    await first.open();
    expect(await first.requireLocalDataSetId()).toBe(identity);
    const reopened = track(new HortinisDatabase(first.name));
    expect(await reopened.requireLocalDataSetId()).toBe(identity);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('rolls back identity with failed initial creation and permits only a fresh retry', async () => {
    let abandoned: string | undefined;
    const failed = track(new HortinisDatabase(name()));
    failed.localDataSetIdentity.hook('creating', (_key, identity, transaction) => {
      abandoned = identity.localDataSetId;
      transaction.abort();
    });
    await expect(failed.open()).rejects.toThrow(/abort/i);
    expect(abandoned).toBeDefined();
    const retry = track(new HortinisDatabase(failed.name));
    await retry.open();
    expect(await retry.requireLocalDataSetId()).not.toBe(abandoned);
    expect(await retry.localDataSetIdentity.count()).toBe(1);
    expect(await retry.outboxOperations.count()).toBe(0);
  });

  it('rejects array-shaped identity metadata even when its named fields otherwise match', async () => {
    const identity = await track(new HortinisDatabase(name())).requireLocalDataSetId();
    const row = Object.assign([], {
      key: LOCAL_DATA_SET_KEY,
      version: '1',
      localDataSetId: identity,
    });
    expect(() => readLocalDataSetIdentity([row])).toThrow(
      'Local data-set identity is unavailable.',
    );
  });

  it('does not regenerate missing identity or discard retained pending intent', async () => {
    const database = track(new HortinisDatabase(name()));
    await database.open();
    const operation = {
      operationId: crypto.randomUUID(),
      recordId: crypto.randomUUID(),
      kind: 'create' as const,
      value: 'retained private intent',
    };
    await database.outboxOperations.put(operation);
    await database.localDataSetIdentity.delete(LOCAL_DATA_SET_KEY);
    database.close();
    const reopened = track(new HortinisDatabase(database.name));
    await expect(reopened.open()).rejects.toThrow('Local data-set identity is unavailable.');
    const inspection = track(new Dexie(database.name));
    await inspection.open();
    expect(await inspection.table('outboxOperations').get(operation.operationId)).toEqual(
      operation,
    );
    expect(await inspection.table('localDataSetIdentity').count()).toBe(0);
  });

  it.each(['invalid', 'additional'])(
    'denies %s identity metadata without adopting or repairing it',
    async (failure) => {
      const database = track(new HortinisDatabase(name()));
      await database.open();
      const identity = await database.requireLocalDataSetId();
      await database
        .table('localDataSetIdentity')
        .put(
          failure === 'invalid'
            ? { key: LOCAL_DATA_SET_KEY, version: '1', localDataSetId: 'private-invalid-identity' }
            : { key: 'foreign', version: '1', localDataSetId: identity },
        );
      database.close();
      const reopened = track(new HortinisDatabase(database.name));
      await expect(reopened.open()).rejects.toThrow('Local data-set identity is unavailable.');
      const inspection = track(new Dexie(database.name));
      await inspection.open();
      expect(await inspection.table('localDataSetIdentity').count()).toBe(
        failure === 'invalid' ? 1 : 2,
      );
    },
  );

  it.each([
    [1, HORTINIS_DATABASE_SCHEMA_V1],
    [5, HORTINIS_DATABASE_SCHEMA_V5],
    [6, HORTINIS_DATABASE_SCHEMA_V6],
  ] as const)(
    'rejects unowned version %i without migrating or assigning any persisted state',
    async (version, schema) => {
      const legacy = track(new Dexie(name()));
      legacy.version(version).stores(schema);
      await legacy.open();
      const snapshots: Record<string, unknown[]> = {};
      for (const table of legacy.tables) {
        const key = table.schema.primKey.keyPath;
        if (typeof key !== 'string') throw new Error('Expected a fixture primary key.');
        const row = { [key]: 'retained-key', fixture: `private-${table.name}` };
        await table.put(row);
        snapshots[table.name] = [row];
      }
      legacy.close();
      const current = track(new HortinisDatabase(legacy.name));
      await expect(current.open()).rejects.toThrow(
        'A fresh local database is required; existing data was not changed.',
      );
      const inspection = track(new Dexie(legacy.name));
      await inspection.open();
      expect(inspection.verno).toBe(version);
      expect(inspection.tables.map((table) => table.name)).not.toContain('localDataSetIdentity');
      for (const [table, rows] of Object.entries(snapshots)) {
        expect(await inspection.table(table).toArray()).toEqual(rows);
      }
    },
  );

  function track<T extends Dexie>(database: T): T {
    databases.push(database);
    return database;
  }
});

function name(): string {
  return `hortinis-s5-schema-${crypto.randomUUID()}`;
}
