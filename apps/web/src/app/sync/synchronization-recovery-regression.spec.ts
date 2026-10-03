import 'fake-indexeddb/auto';

import { createEnvironmentInjector, EnvironmentInjector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { HORTINIS_DATABASE_SCHEMA_V4 } from '../persistence/database-schema';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { SynchronizationRetryPersistence } from '../persistence/synchronization-retry-persistence';
import { pushRetryWorkId } from '../persistence/local-synchronization-retry-state';
import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import type { ChangePage, OperationResult, TechnicalRecordOperation } from './conformance';
import { NETWORK_STATUS } from './network-status';
import {
  SYNCHRONIZATION_CLOCK,
  SYNCHRONIZATION_JITTER,
  SYNCHRONIZATION_SCHEDULER,
  type SynchronizationScheduler,
} from './synchronization-runtime';
import {
  SynchronizationCoordinator,
  SYNCHRONIZATION_COORDINATION_SCHEDULER,
  SYNCHRONIZATION_OWNER_ID,
} from './synchronization-coordinator';
import {
  SynchronizationUnavailableError,
  SynchronizationProtocolError,
  type SynchronizationTransport,
} from './synchronization-transport';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { TechnicalRecordSynchronizationService } from './technical-record-synchronization-service';
import { TechnicalRecordLocalService } from './technical-record-local-service';

const firstId = '00000000-0000-4000-8000-000000000001';
const secondId = '00000000-0000-4000-8000-000000000002';
const recordId = '00000000-0000-4000-8000-000000000003';
const otherRecordId = '00000000-0000-4000-8000-000000000004';
const emptyPage = { changes: [], nextCursor: 'cursor', hasMore: false };
const inertScheduler = { schedule: () => () => undefined };

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

function create(operationId = firstId, id = recordId) {
  return { kind: 'create' as const, operationId, recordId: id, value: 'original' };
}

function result(operation: TechnicalRecordOperation): OperationResult {
  const revision =
    operation.kind === 'create' ? '1' : String(BigInt(operation.expectedRevision) + 1n);
  if (operation.kind === 'delete')
    return {
      outcome: 'accepted',
      operationId: operation.operationId,
      tombstone: { recordId: operation.recordId, revision, deletedAtSequence: '2' },
      sequence: '2',
    };
  return {
    outcome: 'accepted',
    operationId: operation.operationId,
    record: { recordId: operation.recordId, revision, value: operation.value },
    sequence: revision,
  };
}

function page(revision: string, value: string, cursor: string, hasMore = false): ChangePage {
  return {
    changes: [
      {
        operationId: revision === '1' ? firstId : secondId,
        record: { recordId, revision, value },
        sequence: revision,
      },
    ],
    nextCursor: cursor,
    hasMore,
  };
}

describe('synchronization recovery regressions', () => {
  const databases: HortinisDatabase[] = [];
  const injectors: EnvironmentInjector[] = [];

  afterEach(async () => {
    injectors.splice(0).forEach((injector) => injector.destroy());
    TestBed.resetTestingModule();
    for (const database of databases.splice(0).reverse()) {
      database.close();
      await database.delete();
    }
  });

  function database(name = `hortinis-recovery-${crypto.randomUUID()}`) {
    const db = new HortinisDatabase(name);
    databases.push(db);
    return db;
  }

  function scope(
    db: HortinisDatabase,
    transport: SynchronizationTransport,
    clock = { value: 0 },
    options: { online?: () => boolean; coordinationScheduler?: SynchronizationScheduler } = {},
  ) {
    const injector = createEnvironmentInjector(
      [
        { provide: HortinisDatabase, useValue: db },
        TechnicalRecordPersistence,
        SynchronizationRetryPersistence,
        TechnicalRecordSynchronizationService,
        TechnicalRecordLocalService,
        SynchronizationCoordinator,
        { provide: SYNCHRONIZATION_TRANSPORT, useValue: transport },
        { provide: NETWORK_STATUS, useValue: { isOnline: options.online ?? (() => true) } },
        { provide: SYNCHRONIZATION_CLOCK, useValue: { now: () => clock.value } },
        { provide: SYNCHRONIZATION_JITTER, useValue: { sample: () => 0 } },
        { provide: SYNCHRONIZATION_SCHEDULER, useValue: inertScheduler },
        {
          provide: SYNCHRONIZATION_COORDINATION_SCHEDULER,
          useValue: options.coordinationScheduler ?? inertScheduler,
        },
        { provide: SYNCHRONIZATION_OWNER_ID, useValue: crypto.randomUUID() },
      ],
      TestBed.inject(EnvironmentInjector),
    );
    injectors.push(injector);
    return {
      service: injector.get(TechnicalRecordSynchronizationService),
      persistence: injector.get(TechnicalRecordPersistence),
      local: injector.get(TechnicalRecordLocalService),
      coordinator: injector.get(SynchronizationCoordinator),
      retries: injector.get(SynchronizationRetryPersistence),
    };
  }

  it.each(['create', 'replace', 'delete'] as const)(
    'drains a local %s committed during the final pull',
    async (kind) => {
      const db = database();
      const pull = deferred<ChangePage>();
      const transport = {
        submitOperation: vi.fn(async (operation: TechnicalRecordOperation) => result(operation)),
        pullChanges: vi
          .fn()
          .mockImplementationOnce(() => pull.promise)
          .mockResolvedValue(emptyPage),
      };
      const owner = scope(db, transport);
      if (kind !== 'create')
        await owner.persistence.commitPulledPage(page('1', 'original', 'base'));
      const recovery = owner.service.recoverAfterReload();
      await vi.waitFor(() => expect(transport.pullChanges).toHaveBeenCalledOnce());
      if (kind === 'create') await owner.local.create('new local value');
      else if (kind === 'replace') await owner.local.replace(recordId, 'new local value');
      else await owner.local.delete(recordId);
      pull.resolve(emptyPage);
      await expect(recovery).resolves.toMatchObject({ status: 'completed', pushed: 1 });
      expect(transport.submitOperation).toHaveBeenCalledOnce();
      await expect(db.outboxOperations.count()).resolves.toBe(0);
    },
  );

  it.each(['replace', 'delete'] as const)(
    'preserves a %s successor while retaining newer accepted state',
    async (kind) => {
      const db = database();
      const owner = scope(db, { submitOperation: vi.fn(), pullChanges: vi.fn() });
      const predecessor = create();
      await owner.persistence.commitCreate(predecessor);
      if (kind === 'replace')
        await owner.persistence.commitReplace(secondId, recordId, 'successor value');
      else await owner.persistence.commitDelete(secondId, recordId);
      await owner.persistence.commitPulledPage(page('2', 'remote value', 'after-two'));
      await owner.persistence.commitAcceptedResult(predecessor, result(predecessor));
      await expect(db.acceptedTechnicalRecords.get(recordId)).resolves.toMatchObject({
        value: 'remote value',
        revision: '2',
      });
      await expect(db.outboxOperations.get(secondId)).resolves.toMatchObject({
        kind,
        expectedRevision: '1',
      });
      if (kind === 'replace')
        await expect(db.technicalRecords.get(recordId)).resolves.toMatchObject({
          value: 'successor value',
          lastAcceptedRevision: '2',
        });
      else {
        await expect(db.technicalRecords.get(recordId)).resolves.toBeUndefined();
        await expect(db.pendingDeletionRecords.get(recordId)).resolves.toMatchObject({
          lastAcceptedRevision: '2',
        });
      }
    },
  );

  it('rolls back a contradictory equal revision and retains its retry reservation', async () => {
    const db = database();
    const owner = scope(db, { submitOperation: vi.fn(), pullChanges: vi.fn() });
    await owner.persistence.commitPulledPage(page('1', 'original', 'before'));
    await db.synchronizationRetryState.put({
      workId: 'technical-records:pull',
      scope: 'technical-records',
      phase: 'pull',
      attemptCount: 1,
      nextEligibleAt: 10_000,
      failureCategory: 'unavailable',
      exhausted: false,
      inFlight: true,
    });
    await expect(
      owner.persistence.commitPulledPage(page('1', 'contradictory', 'after')),
    ).rejects.toThrow('Equal accepted revisions');
    await expect(owner.persistence.synchronizationCursor()).resolves.toBe('before');
    await expect(db.acceptedTechnicalRecords.get(recordId)).resolves.toMatchObject({
      value: 'original',
    });
    await expect(db.synchronizationRetryState.get('technical-records:pull')).resolves.toMatchObject(
      { attemptCount: 1 },
    );
  });

  it('does not loop over retained conflicts when finishing a pull', async () => {
    const db = database();
    const transport = {
      submitOperation: vi.fn(async () => {
        throw new SynchronizationProtocolError(409, {
          code: 'RECORD_IDENTIFIER_RETIRED',
          message: 'retired',
          operationId: firstId,
          recordId,
        });
      }),
      pullChanges: vi.fn(async () => emptyPage),
    };
    const owner = scope(db, transport);
    await owner.persistence.commitCreate(create());
    await expect(owner.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'completed',
    });
    await expect(db.outboxOperations.count()).resolves.toBe(1);
    expect(transport.submitOperation).toHaveBeenCalledOnce();
    expect(transport.pullChanges).toHaveBeenCalledOnce();
    await expect(db.synchronizationRetryState.count()).resolves.toBe(0);
  });

  it('coalesces new edits and drains work committed by another tab', async () => {
    const db = database();
    const pull = deferred<ChangePage>();
    const transport = {
      submitOperation: vi.fn(async (operation: TechnicalRecordOperation) => result(operation)),
      pullChanges: vi
        .fn()
        .mockImplementationOnce(() => pull.promise)
        .mockResolvedValue(emptyPage),
    };
    const first = scope(db, transport);
    const second = scope(db, transport);
    const recovery = first.service.recoverAfterReload();
    await vi.waitFor(() => expect(transport.pullChanges).toHaveBeenCalledOnce());
    await second.local.create('second tab');
    await first.local.create('first edit');
    await first.local.create('second edit');
    pull.resolve(emptyPage);
    await expect(recovery).resolves.toMatchObject({ status: 'completed', pushed: 3 });
    expect(transport.submitOperation).toHaveBeenCalledTimes(3);
    expect(transport.pullChanges).toHaveBeenCalledTimes(2);
  });

  it('does not lose an edit committed during lease release', async () => {
    const db = database();
    const transport = {
      submitOperation: vi.fn(async (operation: TechnicalRecordOperation) => result(operation)),
      pullChanges: vi.fn(async () => emptyPage),
    };
    const owner = scope(db, transport);
    const release = owner.coordinator.release.bind(owner.coordinator);
    vi.spyOn(owner.coordinator, 'release').mockImplementationOnce(async (lease) => {
      await owner.local.create('during release');
      await release(lease);
    });
    await owner.service.recoverAfterReload();
    await vi.waitFor(() => expect(transport.submitOperation).toHaveBeenCalledOnce());
    await vi.waitFor(async () => expect(await db.outboxOperations.count()).toBe(0));
  });

  it.each(['success', 'failure', 'conflict'] as const)(
    'discards a former owner’s late %s without bypassing exhaustion',
    async (late) => {
      const db = database();
      const clock = { value: 0 };
      const pending = deferred<OperationResult>();
      const oldTransport = {
        submitOperation: vi.fn(() => pending.promise),
        pullChanges: vi.fn(async () => emptyPage),
      };
      const newTransport = {
        submitOperation: vi.fn(async (operation: TechnicalRecordOperation) => {
          if (operation.operationId === firstId) return result(operation);
          throw new SynchronizationUnavailableError();
        }),
        pullChanges: vi.fn(async () => emptyPage),
      };
      const oldOwner = scope(db, oldTransport, clock);
      const newOwner = scope(db, newTransport, clock);
      await oldOwner.persistence.commitCreate(create());
      await oldOwner.persistence.commitCreate(create(secondId, otherRecordId));
      const oldRecovery = oldOwner.service.recoverAfterReload();
      await vi.waitFor(() => expect(oldTransport.submitOperation).toHaveBeenCalledOnce());
      clock.value = 16_001;
      for (let index = 0; index < 5; index++) await newOwner.service.recoverAfterReload();
      expect(newOwner.service.status()).toMatchObject({ status: 'exhausted', attemptCount: 5 });
      const before = await db.synchronizationRetryState.toArray();
      if (late === 'success') pending.resolve(result(create()));
      else if (late === 'failure') pending.reject(new SynchronizationUnavailableError());
      else
        pending.reject(
          new SynchronizationProtocolError(409, {
            code: 'RECORD_IDENTIFIER_RETIRED',
            message: 'retired',
            operationId: firstId,
            recordId,
          }),
        );
      await expect(oldRecovery).resolves.toEqual({ status: 'ownership-lost' });
      expect(oldTransport.submitOperation).toHaveBeenCalledOnce();
      expect(newTransport.submitOperation).toHaveBeenCalledTimes(6);
      await expect(db.synchronizationRetryState.toArray()).resolves.toEqual(before);
      await expect(db.deletionConflicts.count()).resolves.toBe(0);
    },
  );

  it('rejects a late pull even when the successor did not change the cursor', async () => {
    const db = database();
    const clock = { value: 0 };
    const pull = deferred<ChangePage>();
    const oldTransport = { submitOperation: vi.fn(), pullChanges: vi.fn(() => pull.promise) };
    const oldOwner = scope(db, oldTransport, clock);
    const newOwner = scope(
      db,
      { submitOperation: vi.fn(), pullChanges: vi.fn(async () => emptyPage) },
      clock,
    );
    const recovery = oldOwner.service.recoverAfterReload();
    await vi.waitFor(() => expect(oldTransport.pullChanges).toHaveBeenCalledOnce());
    clock.value = 16_001;
    await newOwner.coordinator.tryAcquire();
    pull.resolve(page('1', 'stale owner value', 'late'));
    await expect(recovery).resolves.toEqual({ status: 'ownership-lost' });
    await expect(db.technicalRecords.count()).resolves.toBe(0);
    await expect(oldOwner.persistence.synchronizationCursor()).resolves.toBeUndefined();
  });

  it('keeps an interrupted request counted after reopening the database', async () => {
    const db = database();
    const clock = { value: 0 };
    const first = scope(db, { submitOperation: vi.fn(), pullChanges: vi.fn() }, clock);
    await first.persistence.commitCreate(create());
    const acquired = await first.coordinator.tryAcquire();
    if (!acquired.acquired) throw new Error('Expected ownership.');
    await first.retries.reserveAttempt(
      pushRetryWorkId(firstId),
      'push',
      firstId,
      { lease: acquired.lease, now: () => clock.value, isLost: () => false },
      10_000,
    );
    db.close();
    const reopened = database(db.name);
    const nextTransport = {
      submitOperation: vi.fn(async (operation: TechnicalRecordOperation) => {
        expect(
          await reopened.synchronizationRetryState.get(pushRetryWorkId(firstId)),
        ).toMatchObject({ attemptCount: 2, inFlight: true });
        return result(operation);
      }),
      pullChanges: vi.fn(async () => emptyPage),
    };
    const next = scope(reopened, nextTransport, clock);
    clock.value = 16_001;
    await next.service.recoverAfterReload();
    expect(nextTransport.submitOperation).toHaveBeenCalledOnce();
    await expect(reopened.outboxOperations.count()).resolves.toBe(0);
  });

  it('requires manual recovery after an interrupted fifth attempt', async () => {
    const db = database();
    const clock = { value: 0 };
    const transport = {
      submitOperation: vi.fn(async (operation: TechnicalRecordOperation) => result(operation)),
      pullChanges: vi.fn(async () => emptyPage),
    };
    const owner = scope(db, transport, clock);
    await owner.persistence.commitCreate(create());
    await db.synchronizationRetryState.put({
      workId: pushRetryWorkId(firstId),
      scope: 'technical-records',
      phase: 'push',
      operationId: firstId,
      attemptCount: 5,
      nextEligibleAt: 10_000,
      failureCategory: 'unavailable',
      exhausted: false,
      inFlight: true,
    });
    clock.value = 16_001;
    await expect(owner.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'exhausted',
      attemptCount: 5,
    });
    await owner.local.create('another offline-capable edit');
    await vi.waitFor(() => expect(owner.service.status()).toMatchObject({ status: 'exhausted' }));
    expect(transport.submitOperation).not.toHaveBeenCalled();
    await expect(owner.service.retryNow()).resolves.toMatchObject({
      status: 'completed',
      pushed: 2,
    });
  });

  it('signals ownership loss when renewal fails', async () => {
    const db = database();
    let heartbeat!: () => void;
    const owner = scope(
      db,
      { submitOperation: vi.fn(), pullChanges: vi.fn() },
      { value: 0 },
      {
        coordinationScheduler: {
          schedule: (task) => {
            heartbeat = task;
            return () => undefined;
          },
        },
      },
    );
    const acquired = await owner.coordinator.tryAcquire();
    if (!acquired.acquired) throw new Error('Expected ownership.');
    const lost = vi.fn();
    owner.coordinator.keepAlive(acquired.lease, lost);
    vi.spyOn(db.synchronizationLeases, 'get').mockRejectedValueOnce(
      new Error('storage unavailable'),
    );
    heartbeat();
    await vi.waitFor(() => expect(lost).toHaveBeenCalledOnce());
  });

  it('preserves the fencing counter across release and rejects expired renewal', async () => {
    const db = database();
    const clock = { value: 0 };
    let heartbeat!: () => void;
    const coordinationScheduler = {
      schedule: (task: () => void) => {
        heartbeat = task;
        return () => undefined;
      },
    };
    const owner = scope(db, { submitOperation: vi.fn(), pullChanges: vi.fn() }, clock, {
      coordinationScheduler,
    });
    const first = await owner.coordinator.tryAcquire();
    if (!first.acquired) throw new Error('Expected ownership.');
    const lost = vi.fn();
    owner.coordinator.keepAlive(first.lease, lost);
    clock.value = 16_001;
    heartbeat();
    await vi.waitFor(() => expect(lost).toHaveBeenCalledOnce());
    await expect(db.synchronizationLeases.get('technical-records')).resolves.toMatchObject({
      expiresAt: 15_000,
    });
    await owner.coordinator.release(first.lease);
    const next = await owner.coordinator.tryAcquire();
    expect(next).toMatchObject({ acquired: true, lease: { fencingToken: 2 } });
  });

  it('rejects retry-state writes from a former owner inside their transaction', async () => {
    const db = database();
    const clock = { value: 0 };
    const first = scope(db, { submitOperation: vi.fn(), pullChanges: vi.fn() }, clock);
    const second = scope(db, { submitOperation: vi.fn(), pullChanges: vi.fn() }, clock);
    const acquired = await first.coordinator.tryAcquire();
    if (!acquired.acquired) throw new Error('Expected ownership.');
    const ownership = { lease: acquired.lease, now: () => clock.value, isLost: () => false };
    await first.retries.reserveAttempt(
      pushRetryWorkId(firstId),
      'push',
      firstId,
      ownership,
      10_000,
    );
    clock.value = 16_001;
    await second.coordinator.tryAcquire();
    await expect(first.retries.delete(pushRetryWorkId(firstId), ownership)).rejects.toBeInstanceOf(
      SynchronizationOwnershipLostError,
    );
    await expect(db.synchronizationRetryState.get(pushRetryWorkId(firstId))).resolves.toMatchObject(
      { attemptCount: 1, inFlight: true },
    );
  });

  it('retains deletion-conflict evidence when repair replays an existing tombstone', async () => {
    const db = database();
    const owner = scope(db, { submitOperation: vi.fn(), pullChanges: vi.fn() });
    await owner.persistence.commitPulledPage(page('1', 'original', 'before'));
    await owner.persistence.commitReplace(firstId, recordId, 'pending proposal');
    const deletion: ChangePage = {
      changes: [
        {
          operationId: secondId,
          sequence: '2',
          tombstone: { recordId, revision: '2', deletedAtSequence: '2' },
        },
      ],
      nextCursor: 'after',
      hasMore: false,
    };
    await owner.persistence.commitPulledPage(deletion);
    const conflict = await db.deletionConflicts.get(firstId);
    expect(conflict?.localRecord?.value).toBe('pending proposal');
    await db.synchronizationState.put({
      scope: 'technical-records',
      cursor: 'after',
      repairRequired: true,
    });
    await owner.persistence.commitPulledPage(deletion, { expectedCursor: undefined, repair: true });
    await expect(db.deletionConflicts.get(firstId)).resolves.toEqual(conflict);
    await expect(db.outboxOperations.get(firstId)).resolves.toMatchObject({
      value: 'pending proposal',
      expectedRevision: '1',
    });
    await expect(db.technicalRecords.get(recordId)).resolves.toBeUndefined();
  });

  it('repairs a legacy projection across an offline period, page failure, and reopen before pushing', async () => {
    const name = `hortinis-legacy-repair-${crypto.randomUUID()}`;
    const legacy = new Dexie(name);
    legacy.version(4).stores(HORTINIS_DATABASE_SCHEMA_V4);
    await legacy.open();
    await legacy
      .table('technicalRecords')
      .put({ recordId, value: 'original', lastAcceptedRevision: '2' });
    await legacy
      .table('technicalRecords')
      .put({ recordId: otherRecordId, value: 'pending', lastAcceptedRevision: null });
    await legacy
      .table('outboxOperations')
      .put({ ...create(secondId, otherRecordId), value: 'pending' });
    await legacy
      .table('synchronizationState')
      .put({ scope: 'technical-records', cursor: 'old-cursor' });
    legacy.close();
    const db = database(name);
    let online = false;
    const transport = {
      submitOperation: vi.fn(),
      pullChanges: vi
        .fn()
        .mockResolvedValueOnce(page('1', 'original', 'repair-one', true))
        .mockRejectedValueOnce(new SynchronizationUnavailableError()),
    };
    const first = scope(db, transport, { value: 0 }, { online: () => online });
    await expect(first.service.recoverAfterReload()).resolves.toMatchObject({ status: 'offline' });
    expect(transport.pullChanges).not.toHaveBeenCalled();
    online = true;
    await expect(first.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'scheduled',
      phase: 'pull',
    });
    expect(transport.submitOperation).not.toHaveBeenCalled();
    await expect(db.synchronizationState.get('technical-records')).resolves.toMatchObject({
      cursor: 'old-cursor',
      repairRequired: true,
      repairCursor: 'repair-one',
    });
    await expect(db.technicalRecords.get(recordId)).resolves.toMatchObject({
      value: 'original',
      lastAcceptedRevision: '2',
    });
    db.close();
    const reopened = database(name);
    const resumedTransport = {
      submitOperation: vi.fn(async (operation: TechnicalRecordOperation) => {
        expect(await reopened.technicalRecords.get(recordId)).toMatchObject({
          value: 'updated',
          lastAcceptedRevision: '2',
        });
        return result(operation);
      }),
      pullChanges: vi
        .fn()
        .mockResolvedValueOnce(page('2', 'updated', 'repair-two'))
        .mockResolvedValue(emptyPage),
    };
    const resumed = scope(reopened, resumedTransport);
    await expect(resumed.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'completed',
      pushed: 1,
    });
    expect(resumedTransport.pullChanges).toHaveBeenNthCalledWith(1, 'repair-one');
    await expect(reopened.acceptedTechnicalRecords.get(recordId)).resolves.toMatchObject({
      value: 'updated',
      revision: '2',
    });
    await expect(reopened.synchronizationState.get('technical-records')).resolves.toEqual({
      scope: 'technical-records',
      cursor: 'cursor',
    });
  });
});
