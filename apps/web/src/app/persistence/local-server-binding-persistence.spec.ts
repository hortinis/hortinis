import 'fake-indexeddb/auto';
import { TestBed } from '@angular/core/testing';
import { HortinisDatabase } from './hortinis-database';
import { LocalServerBindingPersistence } from './local-server-binding-persistence';
import {
  parseAccessContract,
  type LocalDataSetId,
  type LocalServerBinding,
} from '../sync/access-contracts.rule';

describe('local binding storage ownership', () => {
  let database: HortinisDatabase;
  let persistence: LocalServerBindingPersistence;
  beforeEach(() => {
    database = new HortinisDatabase(`hortinis-s5-binding-${crypto.randomUUID()}`);
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: database }],
    });
    persistence = TestBed.inject(LocalServerBindingPersistence);
  });
  afterEach(async () => {
    TestBed.resetTestingModule();
    database.close();
    await database.delete();
  });

  it('stores only non-credential provenance for its durable local owner', async () => {
    expect(await persistence.current()).toBeUndefined();
    const binding = value(await database.requireLocalDataSetId());
    await persistence.put(binding);
    database.close();
    await database.open();
    expect(await persistence.current()).toEqual(binding);
    expect(await database.localServerBindings.count()).toBe(1);
  });

  it('rejects foreign data-set ownership without creating a binding', async () => {
    const binding = value(parseAccessContract('LocalDataSetId', crypto.randomUUID()));
    await expect(persistence.put(binding)).rejects.toThrow(
      'Local data-set identity is unavailable.',
    );
    expect(await persistence.current()).toBeUndefined();
  });

  it.each(['password', 'sessionId', 'accessToken', 'refreshToken', 'credentials'])(
    'rejects credential field %s in the closed local contract',
    async (field) => {
      const binding = {
        ...value(await database.requireLocalDataSetId()),
        [field]: 'private-credential-canary',
      };
      await expect(persistence.put(binding)).rejects.toThrow('The access contract is invalid.');
      expect(await database.localServerBindings.count()).toBe(0);
    },
  );

  it('fails closed on a foreign persisted row rather than hiding or overwriting it', async () => {
    const foreign = value(parseAccessContract('LocalDataSetId', crypto.randomUUID()));
    await database.localServerBindings.put(foreign);
    await expect(persistence.current()).rejects.toThrow('Local data-set identity is unavailable.');
    await expect(persistence.put(value(await database.requireLocalDataSetId()))).rejects.toThrow(
      'Local data-set identity is unavailable.',
    );
    expect(await database.localServerBindings.toArray()).toEqual([foreign]);
  });

  it('preserves provenance when an idempotent binding write fails', async () => {
    const original = value(await database.requireLocalDataSetId());
    await persistence.put(original);
    vi.spyOn(database.localServerBindings, 'put').mockRejectedValueOnce(
      new Error('Synthetic binding failure.'),
    );
    await expect(persistence.put(original)).rejects.toThrow('Synthetic binding failure.');
    expect(await persistence.current()).toEqual(original);
  });

  it('preserves a malformed current binding instead of silently replacing its destination', async () => {
    const original = {
      ...value(await database.requireLocalDataSetId()),
      unexpected: 'private-canary',
    };
    await database.table('localServerBindings').put(original);
    await expect(persistence.current()).rejects.toThrow('The access contract is invalid.');
    await expect(persistence.put(value(await database.requireLocalDataSetId()))).rejects.toThrow(
      'The access contract is invalid.',
    );
    expect(await database.localServerBindings.toArray()).toEqual([original]);
  });
});

function value(localDataSetId: LocalDataSetId): LocalServerBinding {
  return parseAccessContract('LocalServerBinding', {
    version: '1',
    localDataSetId,
    serverOrigin: 'https://server.example',
    serverInstanceId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
    identity: {
      authority: 'configured',
      accessMode: 'single-user-no-auth',
      synchronizationScopeId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
    },
    boundary: { kind: 'generationless' },
  });
}
