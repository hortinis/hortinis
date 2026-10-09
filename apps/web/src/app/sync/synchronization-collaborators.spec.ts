import { afterEach, describe, expect, it, vi } from 'vitest';
import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import { SynchronizationRetryBlockedError } from '../persistence/synchronization-retry-persistence';
import {
  pushRetryWorkId,
  TECHNICAL_PULL_RETRY_WORK_ID,
} from '../persistence/local-synchronization-retry-state';
import {
  SynchronizationBoundaryError,
  SynchronizationProtocolError,
  SynchronizationUnavailableError,
  SynchronizationUnexpectedResponseError,
} from './synchronization-transport';
import {
  synchronizationFailureReason,
  synchronizationPersistence,
  SynchronizationPersistenceError,
} from './synchronization-failure';
import { collaboratorHarness } from './testing/synchronization-collaborator-harness';
import {
  accepted,
  emptyPage,
  SynchronizationTestHarness,
  testOperation,
} from './testing/synchronization-test-harness';

describe('synchronization collaborators', () => {
  let harness: SynchronizationTestHarness;
  afterEach(async () => {
    if (harness) await harness.close();
  });
  function rig(options: Parameters<typeof collaboratorHarness>[1] = {}) {
    harness = new SynchronizationTestHarness();
    return collaboratorHarness(harness, options);
  }

  it('reserves the request before dispatch and clears the reservation with an accepted push', async () => {
    const r = rig();
    await r.persistence.commitCreate(testOperation);
    vi.mocked(r.transport.submitOperation).mockImplementation(async (operation) => {
      expect(
        await harness.database.synchronizationRetryState.get(
          pushRetryWorkId(operation.operationId),
        ),
      ).toMatchObject({ attemptCount: 1, inFlight: true });
      return accepted(operation);
    });
    await expect(r.push.run(await r.ownership())).resolves.toMatchObject({ status: 'accepted' });
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
    expect(r.status.status()).toEqual({ status: 'idle' });
  });

  it('pulls from the exact cursor and clears its reservation atomically with the page', async () => {
    const r = rig();
    await r.persistence.commitPulledPage({ ...emptyPage, nextCursor: 'opaque-before' });
    const ownership = await r.ownership();
    vi.mocked(r.transport.pullChanges).mockImplementation(async (cursor) => {
      expect(cursor).toBe('opaque-before');
      expect(
        await harness.database.synchronizationRetryState.get(TECHNICAL_PULL_RETRY_WORK_ID),
      ).toMatchObject({ inFlight: true });
      return emptyPage;
    });
    await expect(r.pull.run(ownership)).resolves.toEqual({ status: 'applied', page: emptyPage });
    expect(await r.persistence.synchronizationCursor()).toBe('cursor');
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
    expect(r.status.status()).toEqual({ status: 'idle' });
  });

  it.each(['push', 'pull'] as const)(
    'keeps %s offline without reserving an attempt',
    async (phase) => {
      const r = rig({ network: { isOnline: () => false } });
      await r.persistence.commitCreate(testOperation);
      await expect(r[phase].run(await r.ownership())).resolves.toEqual({ status: 'offline' });
      expect(await harness.database.synchronizationRetryState.count()).toBe(0);
      expect(r.transport.submitOperation).not.toHaveBeenCalled();
      expect(r.transport.pullChanges).not.toHaveBeenCalled();
    },
  );

  it.each(['push', 'pull'] as const)(
    'retains %s work after result persistence fails',
    async (phase) => {
      const r = rig();
      await r.persistence.commitCreate(testOperation);
      const failure = new Error('controlled commit failure');
      if (phase === 'push')
        vi.spyOn(r.persistence, 'commitAcceptedResult').mockRejectedValueOnce(failure);
      else vi.spyOn(r.persistence, 'commitPulledPage').mockRejectedValueOnce(failure);
      await expect(r[phase].run(await r.ownership())).resolves.toMatchObject({
        status: 'failed',
        error: failure,
        reason: 'local-persistence',
        retryCategory: 'local-persistence',
      });
      expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual({
        ...testOperation,
        ...(phase === 'push' ? { submittedAt: 0 } : {}),
      });
      expect(await harness.database.synchronizationRetryState.count()).toBe(1);
    },
  );

  it('persists a deletion conflict without retrying it', async () => {
    const conflict = {
      code: 'RECORD_IDENTIFIER_RETIRED' as const,
      message: 'retired',
      operationId: testOperation.operationId,
      recordId: testOperation.recordId,
    };
    const r = rig({
      transport: {
        submitOperation: async () => {
          throw new SynchronizationProtocolError(409, conflict);
        },
        pullChanges: vi.fn(),
      },
    });
    await r.persistence.commitCreate(testOperation);
    await expect(r.push.run(await r.ownership())).resolves.toMatchObject({
      status: 'conflict',
      conflict,
    });
    expect(await harness.database.deletionConflicts.count()).toBe(1);
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
  });

  it('keeps an unavailable request reserved and deletes a non-retryable reservation', async () => {
    const r = rig();
    const ownership = await r.ownership();
    await r.exchange.reserve(ownership, TECHNICAL_PULL_RETRY_WORK_ID, 'pull');
    await expect(
      r.exchange.transportFailure(
        new SynchronizationUnavailableError(),
        TECHNICAL_PULL_RETRY_WORK_ID,
        ownership,
      ),
    ).resolves.toMatchObject({ retryCategory: 'unavailable' });
    expect(await harness.database.synchronizationRetryState.count()).toBe(1);
    await expect(
      r.exchange.transportFailure(
        new Error('unknown transport failure'),
        TECHNICAL_PULL_RETRY_WORK_ID,
        ownership,
      ),
    ).resolves.toMatchObject({ reason: 'unknown' });
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
  });

  it('blocks an exhausted attempt and refuses to dispatch with a lease expiring after reservation', async () => {
    const r = rig();
    const ownership = await r.ownership();
    await harness.database.synchronizationRetryState.put({
      workId: TECHNICAL_PULL_RETRY_WORK_ID,
      scope: 'technical-records',
      phase: 'pull',
      attemptCount: 5,
      nextEligibleAt: null,
      exhausted: true,
      failureCategory: 'unavailable',
    });
    await expect(
      r.exchange.reserve(ownership, TECHNICAL_PULL_RETRY_WORK_ID, 'pull'),
    ).rejects.toBeInstanceOf(SynchronizationRetryBlockedError);
    await harness.database.synchronizationRetryState.clear();
    const reserve = r.exchange.reserve.bind(r.exchange);
    // Simulate suspension after the durable reservation, before the dispatch guard.
    const write = harness.database.synchronizationRetryState.put.bind(
      harness.database.synchronizationRetryState,
    );
    vi.spyOn(harness.database.synchronizationRetryState, 'put').mockImplementationOnce((...args) =>
      write(...args).then((key) => {
        r.clock.value = 15001;
        return key;
      }),
    );
    await expect(reserve(ownership, TECHNICAL_PULL_RETRY_WORK_ID, 'pull')).rejects.toBeInstanceOf(
      SynchronizationOwnershipLostError,
    );
  });

  it('restores an interrupted fifth attempt as exhausted and only manual reset makes it eligible', async () => {
    const r = rig();
    const ownership = await r.ownership();
    await harness.database.synchronizationRetryState.put({
      workId: TECHNICAL_PULL_RETRY_WORK_ID,
      scope: 'technical-records',
      phase: 'pull',
      attemptCount: 5,
      nextEligibleAt: 10000,
      inFlight: true,
      exhausted: false,
      failureCategory: 'unavailable',
    });
    await expect(r.retries.restore(ownership)).resolves.toMatchObject({
      status: 'exhausted',
      attemptCount: 5,
    });
    const state = await harness.database.synchronizationRetryState.get(
      TECHNICAL_PULL_RETRY_WORK_ID,
    );
    expect(state).not.toHaveProperty('inFlight');
    await r.retries.reset(ownership);
    expect(
      await harness.database.synchronizationRetryState.get(TECHNICAL_PULL_RETRY_WORK_ID),
    ).toMatchObject({ attemptCount: 0, exhausted: false, nextEligibleAt: 0 });
  });

  it('persists settlement before scheduling and restores its remaining delay', async () => {
    const r = rig({ jitter: () => 250 });
    const ownership = await r.ownership();
    await r.exchange.reserve(ownership, TECHNICAL_PULL_RETRY_WORK_ID, 'pull');
    await expect(
      r.retries.recordFailure(ownership, 'pull', undefined, 'unavailable', {
        pushed: 0,
        pulled: 0,
      }),
    ).resolves.toMatchObject({ status: 'scheduled', nextEligibleAt: 250 });
    expect(
      await harness.database.synchronizationRetryState.get(TECHNICAL_PULL_RETRY_WORK_ID),
    ).toMatchObject({ attemptCount: 1, nextEligibleAt: 250 });
    r.clock.value = 100;
    await r.retries.restore(ownership);
    expect(r.scheduler.delays()).toEqual([150]);
  });

  it('repairs accepted state before uploading and aggregates only accepted exchanges', async () => {
    const order: string[] = [];
    const r = rig();
    await r.persistence.commitCreate(testOperation);
    await harness.database.synchronizationState.put({
      scope: 'technical-records',
      repairRequired: true,
    });
    vi.mocked(r.transport.pullChanges).mockImplementation(async () => {
      order.push('pull');
      return emptyPage;
    });
    vi.mocked(r.transport.submitOperation).mockImplementation(async (operation) => {
      order.push('push');
      return accepted(operation);
    });
    await expect(r.recovery.recover(await r.ownership(), vi.fn(), false)).resolves.toEqual({
      status: 'completed',
      pushed: 1,
      pulled: 2,
    });
    expect(order).toEqual(['pull', 'push', 'pull']);
  });

  it('labels invalid jitter as unknown and retains the original error', async () => {
    const r = rig({
      jitter: () => Infinity,
      transport: {
        submitOperation: async () => {
          throw new SynchronizationUnavailableError();
        },
        pullChanges: vi.fn(),
      },
    });
    await r.persistence.commitCreate(testOperation);
    await expect(r.recovery.recover(await r.ownership(), vi.fn(), false)).resolves.toMatchObject({
      status: 'failed',
      error: expect.any(Error),
    });
    expect(r.status.status()).toEqual({ status: 'failed', reason: 'unknown' });
    expect(r.scheduler.delays()).toEqual([]);
  });

  it('labels a persistence read failure by origin without losing its error identity', async () => {
    const r = rig();
    const failure = new Error('controlled read failure');
    vi.spyOn(r.persistence, 'pullBoundary').mockRejectedValueOnce(failure);
    await expect(r.recovery.recover(await r.ownership(), vi.fn(), false)).resolves.toEqual({
      status: 'failed',
      pushed: 0,
      pulled: 0,
      error: failure,
    });
    expect(r.status.status()).toEqual({ status: 'failed', reason: 'local-persistence' });
  });
});

describe('synchronization failure origins', () => {
  it('classifies known boundaries and keeps unknown errors separate', async () => {
    expect(
      synchronizationFailureReason(new SynchronizationBoundaryError('invalid', 'request')),
    ).toBe('boundary');
    expect(synchronizationFailureReason(new SynchronizationUnexpectedResponseError(500))).toBe(
      'unexpected-response',
    );
    expect(synchronizationFailureReason(new Error('unrelated'))).toBe('unknown');
    expect(synchronizationFailureReason('unrelated')).toBe('unknown');
    const failure = new Error('storage failure');
    const tagged = await synchronizationPersistence(async () => {
      throw failure;
    }).catch((error: unknown) => error);
    expect(tagged).toBeInstanceOf(SynchronizationPersistenceError);
    expect(synchronizationFailureReason(tagged)).toBe('local-persistence');
    const lost = new SynchronizationOwnershipLostError();
    await expect(
      synchronizationPersistence(async () => {
        throw lost;
      }),
    ).rejects.toBe(lost);
  });
});
