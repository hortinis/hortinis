import 'fake-indexeddb/auto';
import { createEnvironmentInjector, EnvironmentInjector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HortinisDatabase } from './hortinis-database';
import { TechnicalRecordPersistence } from './technical-record-persistence';
import { SynchronizationRetryPersistence } from './synchronization-retry-persistence';
import { TECHNICAL_PULL_RETRY_WORK_ID } from './local-synchronization-retry-state';
import { LOCAL_DATA_SET_KEY } from './local-data-set-identity';
import type { SynchronizationOwnership } from './synchronization-ownership';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';
import { parseAccessContract } from '../sync/access-contracts.rule';
import {
  SynchronizationCoordinator,
  SYNCHRONIZATION_OWNER_ID,
  SYNCHRONIZATION_COORDINATION_SCHEDULER,
} from '../sync/synchronization-coordinator';
import {
  SYNCHRONIZATION_CLOCK,
  SYNCHRONIZATION_JITTER,
  SYNCHRONIZATION_SCHEDULER,
} from '../sync/synchronization-runtime';
import { NETWORK_STATUS } from '../sync/network-status';
import { SYNCHRONIZATION_TRANSPORT } from '../sync/synchronization-transport.token';
import {
  SynchronizationUnavailableError,
  type SynchronizationTransport,
} from '../sync/synchronization-transport';
import { TechnicalRecordSynchronizationService } from '../sync/technical-record-synchronization-service';
import {
  accepted,
  emptyPage,
  inertScheduler,
  testOperation,
} from '../sync/testing/synchronization-test-harness';

describe('local data-set isolation and fencing', () => {
  const databases: HortinisDatabase[] = [];
  const injectors: EnvironmentInjector[] = [];
  afterEach(async () => {
    for (const injector of injectors.splice(0)) injector.destroy();
    TestBed.resetTestingModule();
    for (const database of databases.splice(0).reverse()) {
      database.close();
      await database.delete();
    }
  });

  it('keeps colliding outbox IDs, transport submissions, retries, cursors and accepted bases independent', async () => {
    const transportA = {
      submitOperation: vi.fn().mockRejectedValue(new SynchronizationUnavailableError()),
      pullChanges: vi.fn(async () => ({ ...emptyPage, nextCursor: 'A cursor' })),
    };
    const transportB = {
      submitOperation: vi.fn(async (operation) => accepted(operation)),
      pullChanges: vi.fn(async () => ({ ...emptyPage, nextCursor: 'B cursor' })),
    } satisfies SynchronizationTransport;
    const a = scope(transportA);
    const b = scope(transportB);
    const createA = { ...testOperation, value: 'private A' };
    const createB = { ...testOperation, value: 'private B' };
    await a.persistence.commitCreate(createA);
    await b.persistence.commitCreate(createB);
    expect(await a.database.requireLocalDataSetId()).not.toBe(
      await b.database.requireLocalDataSetId(),
    );
    await expect(a.service.pushOnePendingOperation()).resolves.toMatchObject({
      status: 'failed',
      reason: 'unavailable',
    });
    await expect(b.service.pushOnePendingOperation()).resolves.toMatchObject({
      status: 'accepted',
    });
    expect(transportA.submitOperation).toHaveBeenCalledWith(createA);
    expect(transportB.submitOperation).toHaveBeenCalledWith(createB);
    expect(await a.database.outboxOperations.count()).toBe(1);
    expect(await b.database.outboxOperations.count()).toBe(0);
    expect(await a.retries.current()).toMatchObject({
      operationId: createA.operationId,
      attemptCount: 1,
    });
    expect(await b.retries.current()).toBeUndefined();
    expect(await a.database.acceptedTechnicalRecords.count()).toBe(0);
    expect(await b.database.acceptedTechnicalRecords.get(createB.recordId)).toMatchObject({
      value: 'private B',
    });
    expect(await a.database.acceptedOperationResults.count()).toBe(0);
    expect(await b.database.acceptedOperationResults.count()).toBe(1);
    await a.persistence.commitPulledPage({ ...emptyPage, nextCursor: 'A cursor' });
    await b.persistence.commitPulledPage({ ...emptyPage, nextCursor: 'B cursor' });
    a.database.close();
    await a.database.open();
    expect(await a.persistence.synchronizationCursor()).toBe('A cursor');
    expect(await b.persistence.synchronizationCursor()).toBe('B cursor');
    expect(await a.database.technicalRecords.get(createA.recordId)).toMatchObject({
      value: 'private A',
    });
  });

  it('rejects another data set lease even when owner, fencing token, expiry and operation IDs collide', async () => {
    const a = scope();
    const b = scope();
    await a.persistence.commitCreate(testOperation);
    await b.persistence.commitCreate(testOperation);
    const ownerA = await ownership(a);
    const ownerB = await ownership(b);
    expect(ownerA.lease.ownerId).toBe(ownerB.lease.ownerId);
    expect(ownerA.lease.fencingToken).toBe(ownerB.lease.fencingToken);
    expect(ownerA.lease.expiresAt).toBe(ownerB.lease.expiresAt);
    await expect(b.coordinator.assertOwner(ownerA)).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    await expect(b.persistence.preparePendingOperation(ownerA, 10000)).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    await expect(
      b.persistence.commitAcceptedResult(testOperation, accepted(testOperation), ownerA),
    ).rejects.toThrow('Synchronization ownership was lost.');
    await expect(b.persistence.commitPulledPage(emptyPage, undefined, ownerA)).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    await expect(
      b.retries.reserveAttempt(TECHNICAL_PULL_RETRY_WORK_ID, 'pull', undefined, ownerA, 10000),
    ).rejects.toThrow('Synchronization ownership was lost.');
    await b.coordinator.release(ownerA.lease);
    expect(await b.database.synchronizationLeases.get('technical-records')).toEqual(ownerB.lease);
    const lost = vi.fn();
    const stop = b.coordinator.keepAlive(ownerA.lease, lost);
    b.scheduled.shift()?.();
    await vi.waitFor(() => expect(lost).toHaveBeenCalledOnce());
    stop();
    expect(await b.database.outboxOperations.get(testOperation.operationId)).toEqual(testOperation);
    expect(await b.database.acceptedOperationResults.count()).toBe(0);
    expect(await b.database.synchronizationState.count()).toBe(0);
    expect(await b.retries.current()).toBeUndefined();
  });

  it('fences a live connection after identity change without accepting a response or local edit', async () => {
    const a = scope();
    await a.persistence.commitCreate(testOperation);
    const owner = await ownership(a);
    const before = await a.database.localDataSetIdentity.get(LOCAL_DATA_SET_KEY);
    if (!before) throw new Error('Expected identity.');
    await a.database.localDataSetIdentity.put({
      ...before,
      localDataSetId: parseAccessContract('LocalDataSetId', crypto.randomUUID()),
    });
    await expect(
      a.persistence.commitAcceptedResult(testOperation, accepted(testOperation), owner),
    ).rejects.toThrow('Local data-set identity is unavailable.');
    await expect(
      a.persistence.commitCreate({
        ...testOperation,
        operationId: crypto.randomUUID(),
        recordId: crypto.randomUUID(),
      }),
    ).rejects.toThrow('Local data-set identity is unavailable.');
    expect(await a.database.outboxOperations.count()).toBe(1);
    expect(await a.database.technicalRecords.count()).toBe(1);
    expect(await a.database.acceptedOperationResults.count()).toBe(0);
    a.database.close();
    await expect(a.database.open()).rejects.toThrow('Local data-set identity is unavailable.');
  });

  it('keeps tombstone retirement and quarantined intent inside their owning databases', async () => {
    const a = scope();
    const b = scope();
    await a.persistence.commitCreate(testOperation);
    await b.persistence.commitCreate({ ...testOperation, value: 'B' });
    await a.persistence.commitAcceptedResult(testOperation, accepted(testOperation));
    const createB = { ...testOperation, value: 'B' };
    await b.persistence.commitAcceptedResult(createB, accepted(createB));
    const changeId = crypto.randomUUID();
    const deletion = await b.persistence.commitDelete(changeId, testOperation.recordId);
    if (!('kind' in deletion) || deletion.kind !== 'delete' || deletion.expectedRevision === null)
      throw new Error('Expected ready deletion.');
    await b.persistence.commitAcceptedResult(
      {
        operationId: deletion.operationId,
        recordId: deletion.recordId,
        kind: 'delete',
        expectedRevision: deletion.expectedRevision,
      },
      {
        outcome: 'accepted',
        operationId: deletion.operationId,
        sequence: '2',
        tombstone: { recordId: deletion.recordId, revision: '2', deletedAtSequence: '2' },
      },
    );
    const replaceA = await a.persistence.commitReplace(
      changeId,
      testOperation.recordId,
      'quarantined A',
    );
    await a.persistence.commitRejectedOperation(
      {
        operationId: changeId,
        recordId: testOperation.recordId,
        kind: 'replace',
        value: 'quarantined A',
        expectedRevision: '1',
      },
      'invalid-request',
    );
    expect(replaceA.operation.operationId).toBe(deletion.operationId);
    expect(await a.database.technicalTombstones.count()).toBe(0);
    expect(await b.database.technicalTombstones.count()).toBe(1);
    expect(await a.database.rejectedOperations.count()).toBe(1);
    expect(await b.database.rejectedOperations.count()).toBe(0);
    expect(await a.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
      value: 'quarantined A',
    });
    expect(await b.database.technicalRecords.get(testOperation.recordId)).toBeUndefined();
    expect(await a.database.acceptedOperationResults.get(changeId)).toBeUndefined();
    expect(await b.database.acceptedOperationResults.get(changeId)).toMatchObject({
      tombstone: { revision: '2' },
    });
  });

  it('serializes two connections to one data set without blocking an independent data set', async () => {
    const a = scope(undefined, 'A1');
    const a2 = scope(undefined, 'A2', a.database.name);
    const b = scope(undefined, 'B');
    const ownerA = await ownership(a);
    expect(await a2.database.requireLocalDataSetId()).toBe(ownerA.lease.localDataSetId);
    await expect(a2.coordinator.tryAcquire()).resolves.toEqual({
      acquired: false,
      retryAt: ownerA.lease.expiresAt,
    });
    expect((await b.coordinator.tryAcquire()).acquired).toBe(true);
    a.clock.value = ownerA.lease.expiresAt + 1;
    a2.clock.value = a.clock.value;
    const ownerA2 = await ownership(a2);
    await expect(a.coordinator.assertOwner(ownerA)).rejects.toThrow(
      'Synchronization ownership was lost.',
    );
    expect(ownerA2.lease.localDataSetId).toBe(ownerA.lease.localDataSetId);
    expect(ownerA2.lease.fencingToken).toBe(ownerA.lease.fencingToken + 1);
  });

  it('keeps conflict evidence and pending deletion bases isolated for colliding identifiers', async () => {
    const a = scope();
    const b = scope();
    const createB = { ...testOperation, value: 'B accepted base' };
    await a.persistence.commitCreate(testOperation);
    await b.persistence.commitCreate(createB);
    await a.persistence.commitAcceptedResult(testOperation, accepted(testOperation));
    await b.persistence.commitAcceptedResult(createB, accepted(createB));
    const operationId = crypto.randomUUID();
    const replacement = await a.persistence.commitReplace(
      operationId,
      testOperation.recordId,
      'A pending proposal',
    );
    const submitted = toSubmittedTechnicalRecordOperation(replacement.operation);
    if (!submitted || submitted.kind !== 'replace') throw new Error('Expected ready replacement.');
    await a.persistence.commitRevisionConflict(submitted, {
      code: 'REVISION_CONFLICT',
      message: 'The record revision has changed.',
      operationId,
      expectedRevision: '1',
      currentRecord: { recordId: testOperation.recordId, revision: '2', value: 'A server base' },
    });
    await b.persistence.commitDelete(operationId, testOperation.recordId);
    expect(await a.database.pendingDeletionRecords.count()).toBe(0);
    expect(await b.database.pendingDeletionRecords.get(testOperation.recordId)).toMatchObject({
      value: 'B accepted base',
    });
    expect(await a.database.revisionConflicts.get(operationId)).toMatchObject({
      currentRecord: { value: 'A server base' },
    });
    expect(await b.database.revisionConflicts.count()).toBe(0);
    await a.persistence.commitPulledPage({
      changes: [
        {
          operationId: crypto.randomUUID(),
          sequence: '3',
          tombstone: { recordId: testOperation.recordId, revision: '3', deletedAtSequence: '3' },
        },
      ],
      nextCursor: 'A deletion cursor',
      hasMore: false,
    });
    expect(await a.database.deletionConflicts.get(operationId)).toMatchObject({
      localRecord: { value: 'A pending proposal' },
    });
    expect(await b.database.deletionConflicts.count()).toBe(0);
    expect(await b.database.acceptedTechnicalRecords.get(testOperation.recordId)).toMatchObject({
      value: 'B accepted base',
    });
    expect(await b.database.pendingDeletionRecords.get(testOperation.recordId)).toMatchObject({
      value: 'B accepted base',
    });
    expect(await b.database.technicalTombstones.count()).toBe(0);
    expect(await b.persistence.synchronizationCursor()).toBeUndefined();
  });

  function scope(
    transport: SynchronizationTransport = {
      submitOperation: async (operation) => accepted(operation),
      pullChanges: async () => emptyPage,
    },
    owner = 'colliding-owner',
    locator = `hortinis-s5-isolation-${crypto.randomUUID()}`,
  ) {
    const database = new HortinisDatabase(locator);
    databases.push(database);
    const clock = { value: 0 };
    const scheduled: (() => void)[] = [];
    const injector = createEnvironmentInjector(
      [
        { provide: HortinisDatabase, useValue: database },
        TechnicalRecordPersistence,
        SynchronizationRetryPersistence,
        SynchronizationCoordinator,
        TechnicalRecordSynchronizationService,
        { provide: SYNCHRONIZATION_TRANSPORT, useValue: transport },
        { provide: NETWORK_STATUS, useValue: { isOnline: () => true } },
        { provide: SYNCHRONIZATION_CLOCK, useValue: { now: () => clock.value } },
        { provide: SYNCHRONIZATION_JITTER, useValue: { sample: () => 0 } },
        { provide: SYNCHRONIZATION_SCHEDULER, useValue: inertScheduler },
        {
          provide: SYNCHRONIZATION_COORDINATION_SCHEDULER,
          useValue: {
            schedule: (run: () => void) => {
              scheduled.push(run);
              return () => undefined;
            },
          },
        },
        { provide: SYNCHRONIZATION_OWNER_ID, useValue: owner },
      ],
      TestBed.inject(EnvironmentInjector),
    );
    injectors.push(injector);
    return {
      database,
      clock,
      scheduled,
      coordinator: injector.get(SynchronizationCoordinator),
      persistence: injector.get(TechnicalRecordPersistence),
      retries: injector.get(SynchronizationRetryPersistence),
      service: injector.get(TechnicalRecordSynchronizationService),
    };
  }

  async function ownership(context: ReturnType<typeof scope>): Promise<SynchronizationOwnership> {
    const attempt = await context.coordinator.tryAcquire();
    if (!attempt.acquired) throw new Error('Expected lease.');
    return { lease: attempt.lease, now: () => context.clock.value, isLost: () => false };
  }
});
