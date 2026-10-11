import 'fake-indexeddb/auto';
import { TestBed } from '@angular/core/testing';
import { HortinisDatabase } from './hortinis-database';
import { LocalBindingExchangePersistence } from './local-binding-exchange-persistence';
import { TechnicalRecordPersistence } from './technical-record-persistence';
import { SynchronizationRetryPersistence } from './synchronization-retry-persistence';
import type { SynchronizationOwnership } from './synchronization-ownership';
import { SynchronizationCoordinator } from '../sync/synchronization-coordinator';
import { SYNCHRONIZATION_CLOCK } from '../sync/synchronization-runtime';
import { parseAccessContract, type LocalServerBinding } from '../sync/access-contracts.rule';
import { accepted, emptyPage, testOperation } from '../sync/testing/synchronization-test-harness';

describe('atomic local binding, admission and exchange fences', () => {
  let database: HortinisDatabase;
  let binding: LocalBindingExchangePersistence;
  let records: TechnicalRecordPersistence;
  let coordinator: SynchronizationCoordinator;
  beforeEach(() => {
    database = new HortinisDatabase(`hortinis-s6-binding-${crypto.randomUUID()}`);
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: SYNCHRONIZATION_CLOCK, useValue: { now: () => 1 } },
      ],
    });
    binding = TestBed.inject(LocalBindingExchangePersistence);
    records = TestBed.inject(TechnicalRecordPersistence);
    coordinator = TestBed.inject(SynchronizationCoordinator);
  });
  afterEach(async () => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
    database.close();
    await database.delete();
  });

  it('starts unbound offline without admission or a network dependency', async () => {
    expect(await binding.snapshot()).toMatchObject({
      exchange: { state: 'offline', exchangeFence: '1', logoutPending: false },
      binding: undefined,
      initial: undefined,
    });
  });

  it('does not treat stored provenance alone as an established binding', async () => {
    const destination = await value();
    await database.localServerBindings.put(destination);
    await expect(binding.snapshot()).rejects.toThrow('Local data-set identity is unavailable.');
    expect(await database.localServerBindings.toArray()).toEqual([destination]);
    expect(await database.localInitialAdmissions.count()).toBe(0);
  });

  it('commits provenance, admission and a new fence together and retains unchanged pending intent', async () => {
    await records.commitCreate(testOperation);
    const before = await owner();
    await binding.begin(before);
    const destination = await value();
    await binding.confirm(destination, 'synthetic-expectation', 1001, before);
    const snapshot = await binding.snapshot();
    expect(snapshot).toMatchObject({
      binding: destination,
      exchange: { state: 'bound', exchangeFence: '2' },
      initial: {
        phase: 'confirmed',
        expectation: 'synthetic-expectation',
        expiresAt: 1001,
        exchangeFence: '2',
      },
    });
    expect(await database.outboxOperations.get(testOperation.operationId)).toEqual(testOperation);
    await expect(coordinator.assertOwner(before)).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    expect(await database.synchronizationLeases.get('technical-records')).toMatchObject({
      expiresAt: 0,
      exchangeFence: '2',
    });
    database.close();
    await database.open();
    expect(await binding.snapshot()).toEqual(snapshot);
  });

  it('completes admission with the known accepted result in one local transaction', async () => {
    await records.commitCreate(testOperation);
    const lease = await owner();
    await binding.begin(lease);
    await binding.confirm(await value(), 'synthetic-expectation', 1001, lease);
    const admitted = await owner();
    await records.preparePendingOperation(admitted, 10000);
    await records.commitAcceptedResult(testOperation, accepted(testOperation), admitted);
    expect(await binding.snapshot()).toMatchObject({
      exchange: { state: 'bound', exchangeFence: '2' },
      initial: undefined,
    });
    expect(await database.acceptedOperationResults.count()).toBe(1);
    expect(await database.outboxOperations.count()).toBe(0);
  });

  it('rolls back all binding writes if admission persistence fails', async () => {
    const lease = await owner();
    await binding.begin(lease);
    const before = await binding.snapshot();
    const storedLease = await database.synchronizationLeases.get('technical-records');
    vi.spyOn(database.localInitialAdmissions, 'put').mockRejectedValueOnce(
      new Error('Synthetic admission failure.'),
    );
    await expect(
      binding.confirm(await value(), 'synthetic-expectation', 1001, lease),
    ).rejects.toThrow('Synthetic admission failure.');
    expect(await binding.snapshot()).toEqual(before);
    expect(await database.synchronizationLeases.get('technical-records')).toEqual(storedLease);
    await binding.confirm(await value(), 'synthetic-expectation', 1001, lease);
    expect((await binding.snapshot()).exchange.state).toBe('bound');
  });

  it('pauses with an advanced fence and removes initial expectation without losing provenance or intent', async () => {
    await records.commitCreate(testOperation);
    const lease = await owner();
    await binding.begin(lease);
    const destination = await value();
    await binding.confirm(destination, 'synthetic-expectation', 1001, lease);
    const inFlight = await owner();
    await binding.pause('reconciliation_required', '2');
    expect(await binding.snapshot()).toMatchObject({
      binding: destination,
      exchange: { state: 'reconciliation_required', exchangeFence: '3' },
      initial: { phase: 'uncertain', expectation: null, expiresAt: null, exchangeFence: '3' },
    });
    await expect(
      records.commitAcceptedResult(testOperation, accepted(testOperation), inFlight),
    ).rejects.toThrow('Synchronization ownership was lost.');
    await expect(records.commitPulledPage(emptyPage, undefined, inFlight)).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    await expect(
      TestBed.inject(SynchronizationRetryPersistence).delete('pull', inFlight),
    ).rejects.toThrow('Synchronization ownership was lost.');
    await expect(binding.pause('binding_mismatch', '2')).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    expect(await database.acceptedOperationResults.count()).toBe(0);
    expect(await database.synchronizationState.count()).toBe(0);
    expect(await database.outboxOperations.get(testOperation.operationId)).toEqual(testOperation);
  });

  it('preserves a foreign or malformed progress row and fails closed', async () => {
    const ownerId = await database.requireLocalDataSetId();
    await database.table('localInitialAdmissions').put({
      localDataSetId: ownerId,
      exchangeFence: '1',
      phase: 'confirmed',
      expectation: 'private-metadata-canary',
      expiresAt: 1001,
      accessToken: 'private-credential-canary',
    });
    await expect(binding.snapshot()).rejects.toThrow('Local data-set identity is unavailable.');
    expect(await database.localInitialAdmissions.count()).toBe(1);
  });

  async function owner(): Promise<SynchronizationOwnership> {
    const attempt = await coordinator.tryAcquire();
    if (!attempt.acquired) throw new Error('Expected owned lease.');
    return { lease: attempt.lease, now: () => 1, isLost: () => false };
  }

  async function value(): Promise<LocalServerBinding> {
    return parseAccessContract('LocalServerBinding', {
      version: '1',
      localDataSetId: await database.requireLocalDataSetId(),
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
});
