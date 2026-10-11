import 'fake-indexeddb/auto';
import { TestBed } from '@angular/core/testing';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { LocalBindingExchangePersistence } from '../persistence/local-binding-exchange-persistence';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import {
  SynchronizationCoordinator,
  SYNCHRONIZATION_COORDINATION_SCHEDULER,
} from './synchronization-coordinator';
import { SYNCHRONIZATION_CLOCK, SYNCHRONIZATION_SCHEDULER } from './synchronization-runtime';
import { NETWORK_STATUS } from './network-status';
import { BINDING_TRANSPORT } from './binding-transport.token';
import { BindingSession } from './binding-session';
import { SynchronizationBindingService } from './synchronization-binding-service';
import { SynchronizationAccessError } from './binding-transport';
import { parseAccessContract, type SynchronizationBootstrap } from './access-contracts.rule';
import {
  accepted,
  testOperation,
  inertScheduler,
  deferred,
} from './testing/synchronization-test-harness';

const serverInstanceId = '01890f3e-7c5a-7b12-8abc-0123456789ab';
const identity = {
  authority: 'configured',
  accessMode: 'single-user-no-auth',
  synchronizationScopeId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
} as const;
const boundary = { kind: 'generationless' } as const;

describe('explicit binding and retained destination readiness', () => {
  let database: HortinisDatabase;
  let persistence: LocalBindingExchangePersistence;
  let records: TechnicalRecordPersistence;
  let service: SynchronizationBindingService;
  let session: BindingSession;
  let coordinator: SynchronizationCoordinator;
  let now: number;
  let online: boolean;
  let observation: SynchronizationBootstrap;
  const transport = {
    serverOrigin: 'https://server.example',
    capabilities: vi.fn(),
    bootstrap: vi.fn(),
    confirm: vi.fn(),
  };

  beforeEach(() => {
    now = 1;
    online = true;
    observation = parseAccessContract('SynchronizationBootstrap', {
      contractVersion: '1',
      serverInstanceId,
      identity,
      boundary,
      population: 'empty',
      expectation: 'initial-expectation',
    });
    transport.capabilities.mockResolvedValue(
      parseAccessContract('ServerCapabilities', {
        contractVersion: '1',
        serverInstanceId,
        accessMode: identity.accessMode,
        supportedBoundaries: ['generationless'],
        anchoredSnapshots: false,
      }),
    );
    transport.bootstrap.mockImplementation(async () => observation);
    transport.confirm.mockImplementation(async () => observation);
    database = new HortinisDatabase(`hortinis-s6-flow-${crypto.randomUUID()}`);
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: BINDING_TRANSPORT, useValue: transport },
        { provide: NETWORK_STATUS, useValue: { isOnline: () => online } },
        { provide: SYNCHRONIZATION_CLOCK, useValue: { now: () => now } },
        { provide: SYNCHRONIZATION_SCHEDULER, useValue: inertScheduler },
        { provide: SYNCHRONIZATION_COORDINATION_SCHEDULER, useValue: inertScheduler },
      ],
    });
    persistence = TestBed.inject(LocalBindingExchangePersistence);
    records = TestBed.inject(TechnicalRecordPersistence);
    service = TestBed.inject(SynchronizationBindingService);
    session = TestBed.inject(BindingSession);
    coordinator = TestBed.inject(SynchronizationCoordinator);
  });
  afterEach(async () => {
    TestBed.resetTestingModule();
    database.close();
    await database.delete();
    vi.resetAllMocks();
  });

  it('keeps unbound startup and offline connection local without submitted markers', async () => {
    await records.commitCreate(testOperation);
    const lease = await acquire();
    await expect(session.prepare('push', lease.lease)).rejects.toThrow(
      'Synchronization is paused.',
    );
    await coordinator.release(lease.lease);
    online = false;
    expect(await service.connect()).toBe('offline');
    expect(transport.capabilities).not.toHaveBeenCalled();
    expect(await database.outboxOperations.get(testOperation.operationId)).toEqual(testOperation);
  });

  it.each([false, true])(
    'binds an empty server with pending local intent %s and retains IDs',
    async (populated) => {
      if (populated) await records.commitCreate(testOperation);
      expect(await service.connect()).toBe('bound');
      expect(await persistence.snapshot()).toMatchObject({
        binding: { identity, serverInstanceId },
        initial: { expectation: 'initial-expectation', phase: 'confirmed' },
      });
      if (populated)
        expect(await database.outboxOperations.get(testOperation.operationId)).toEqual(
          testOperation,
        );
      const owner = await acquire();
      if (populated)
        await expect(session.prepare('pull', owner.lease)).rejects.toThrow(
          'Synchronization is paused.',
        );
      await session.prepare('push', owner.lease);
      expect((await session.requestContext()).expectation).toBe('initial-expectation');
      expect(transport.bootstrap).toHaveBeenLastCalledWith('initial-expectation');
    },
  );

  it('pauses a new populated-server connection without confirmation or content movement', async () => {
    await records.commitCreate(testOperation);
    observation = { ...observation, population: 'populated' };
    expect(await service.connect()).toBe('reconciliation_required');
    expect(transport.confirm).not.toHaveBeenCalled();
    expect((await persistence.snapshot()).binding).toBeUndefined();
    expect(await database.outboxOperations.get(testOperation.operationId)).toEqual(testOperation);
  });

  it('rejects history without provenance even against an empty destination', async () => {
    await records.commitCreate(testOperation);
    await database.outboxOperations.update(testOperation.operationId, { submittedAt: 1 });
    expect(await service.connect()).toBe('reconciliation_required');
    expect(transport.confirm).not.toHaveBeenCalled();
  });

  it('records a racing confirmation failure as reconciliation required', async () => {
    transport.confirm.mockRejectedValueOnce(
      new SynchronizationAccessError(
        parseAccessContract('BindingPreconditionFailedError', {
          code: 'BINDING_PRECONDITION_FAILED',
          message: 'The binding precondition no longer holds.',
        }),
      ),
    );
    expect(await service.connect()).toBe('reconciliation_required');
    expect((await persistence.snapshot()).exchange.state).toBe('reconciliation_required');
  });

  it('retries an interrupted confirmation explicitly without publishing a partial binding', async () => {
    transport.confirm.mockRejectedValueOnce(new Error('Synthetic connection loss.'));
    await service.connect();
    expect(await persistence.snapshot()).toMatchObject({
      exchange: { state: 'binding_pending' },
      binding: undefined,
    });
    expect(await service.connect()).toBe('bound');
  });

  it('reuses initial admission after reopen and removes it on known acceptance', async () => {
    await records.commitCreate(testOperation);
    await service.connect();
    database.close();
    await database.open();
    const owner = await acquire();
    await session.prepare('push', owner.lease);
    expect((await session.requestContext()).expectation).toBe('initial-expectation');
    await records.preparePendingOperation(owner, 1000);
    await records.commitAcceptedResult(testOperation, accepted(testOperation), owner);
    expect((await persistence.snapshot()).initial).toBeUndefined();
    now = 600002;
    await persistence.pause('reconciliation_required', '2', {
      expectation: 'initial-expectation',
      now,
    });
    expect((await persistence.snapshot()).exchange.state).toBe('bound');
  });

  it('expires uncertain first exchange without refreshing admission into a populated server', async () => {
    await service.connect();
    const owner = await acquire();
    now = 600002;
    await expect(session.prepare('pull', owner.lease)).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    expect(await persistence.snapshot()).toMatchObject({
      exchange: { state: 'reconciliation_required' },
      initial: { phase: 'uncertain', expectation: null, expiresAt: null },
    });
    observation = { ...observation, population: 'populated' };
    expect(await service.connect()).toBe('reconciliation_required');
    expect(transport.confirm).toHaveBeenCalledTimes(1);
  });

  it('checks instance before releasing content and fences delayed results', async () => {
    await service.connect();
    const owner = await acquire();
    await session.prepare('push', owner.lease);
    const context = await session.requestContext();
    transport.capabilities.mockResolvedValueOnce({
      contractVersion: '1',
      serverInstanceId: '01890f3e-7c5a-7b14-8abc-0123456789ab',
      accessMode: identity.accessMode,
      supportedBoundaries: ['generationless'],
      anchoredSnapshots: false,
    });
    await expect(session.requestContext()).rejects.toThrow('Synchronization ownership was lost.');
    await expect(session.assertCurrent(context)).rejects.toThrow();
    expect((await persistence.snapshot()).exchange.state).toBe('binding_mismatch');
  });

  it('reobserves scope before each content attempt without refreshing initial admission', async () => {
    await service.connect();
    const owner = await acquire();
    await session.prepare('push', owner.lease);
    const first = await session.requestContext();
    observation = {
      ...observation,
      identity: {
        ...identity,
        synchronizationScopeId: parseAccessContract(
          'SynchronizationScopeId',
          '01890f3e-7c5a-7b14-8abc-0123456789ab',
        ),
      },
    };
    await expect(session.requestContext()).rejects.toThrow('Synchronization ownership was lost.');
    expect(transport.bootstrap).toHaveBeenLastCalledWith(first.expectation);
    expect((await persistence.snapshot()).exchange.state).toBe('binding_mismatch');
  });

  it('resumes established same-destination data without first-binding into populated state', async () => {
    await records.commitCreate(testOperation);
    await service.connect();
    const owner = await acquire();
    await records.preparePendingOperation(owner, 1000);
    await records.commitAcceptedResult(testOperation, accepted(testOperation), owner);
    await coordinator.release(owner.lease);
    await persistence.pause('disconnected', '2');
    observation = { ...observation, population: 'populated', expectation: 'refreshed-expectation' };
    expect(await service.connect()).toBe('bound');
    const resumed = await acquire();
    await session.prepare('pull', resumed.lease);
    expect((await session.requestContext()).expectation).toBe('refreshed-expectation');
    await session.requestContext();
    expect(transport.bootstrap).toHaveBeenLastCalledWith('refreshed-expectation');
    expect(transport.confirm).toHaveBeenCalledTimes(1);
    expect((await persistence.snapshot()).initial).toBeUndefined();
  });

  it('records generic access denial without claiming revocation or scope deletion', async () => {
    await service.connect();
    const owner = await acquire();
    await session.prepare('pull', owner.lease);
    transport.bootstrap.mockRejectedValueOnce(
      new SynchronizationAccessError(
        parseAccessContract('AccessDeniedError', {
          code: 'ACCESS_DENIED',
          message: 'Access is denied.',
        }),
      ),
    );
    await expect(session.requestContext()).rejects.toThrow('Synchronization ownership was lost.');
    expect((await persistence.snapshot()).exchange.state).toBe('disconnected');
  });

  it('does not share a binding lease with an active synchronization owner', async () => {
    const owner = await acquire();
    expect(await service.connect()).toBe('offline');
    expect(transport.capabilities).not.toHaveBeenCalled();
    await coordinator.release(owner.lease);
    expect(await service.connect()).toBe('bound');
  });

  it('does not confirm after a concurrent fenced pause while metadata is delayed', async () => {
    const pending = deferred<SynchronizationBootstrap>();
    transport.bootstrap.mockReturnValueOnce(pending.promise);
    const connecting = service.connect();
    await vi.waitFor(() => expect(transport.bootstrap).toHaveBeenCalledOnce());
    await persistence.pause('disconnected', '1');
    pending.resolve(observation);
    await connecting;
    expect((await persistence.snapshot()).binding).toBeUndefined();
    expect(transport.confirm).not.toHaveBeenCalled();
  });

  async function acquire() {
    const attempt = await coordinator.tryAcquire();
    if (!attempt.acquired) throw new Error('Expected a lease.');
    return { lease: attempt.lease, now: () => now, isLost: () => false };
  }
});
