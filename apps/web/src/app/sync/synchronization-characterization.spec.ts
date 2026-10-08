import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChangePage } from './conformance';
import { SynchronizationUnavailableError } from './synchronization-transport';
import {
  accepted,
  ControlledSynchronizationScheduler,
  deferred,
  emptyPage,
  SynchronizationTestHarness,
  testOperation,
} from './testing/synchronization-test-harness';

describe('synchronization status characterization', () => {
  let harness: SynchronizationTestHarness;
  afterEach(async () => {
    await harness.close();
  });

  it('publishes only the recovery start and aggregate completion on success', async () => {
    harness = new SynchronizationTestHarness();
    const order: string[] = [];
    const scope = harness.scope({
      submitOperation: async (operation) => {
        order.push('push');
        return accepted(operation);
      },
      pullChanges: async () => {
        order.push('pull');
        return emptyPage;
      },
    });
    await scope.persistence.commitCreate(testOperation);
    await expect(scope.service.recoverAfterReload()).resolves.toEqual({
      status: 'completed',
      pushed: 1,
      pulled: 1,
    });
    expect(scope.trace).toEqual([
      { status: 'idle' },
      { status: 'synchronizing' },
      { status: 'completed', pushed: 1, pulled: 1 },
    ]);
    expect(order).toEqual(['push', 'pull']);
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
  });

  it('observes offline without reserving an attempt and resumes on the online event', async () => {
    harness = new SynchronizationTestHarness();
    let online = false;
    let resume!: () => void;
    const listen = vi.fn((listener: () => void) => {
      resume = listener;
      return () => undefined;
    });
    const submit = vi.fn(async () => accepted(testOperation));
    const scope = harness.scope(
      { submitOperation: submit, pullChanges: async () => emptyPage },
      {
        network: { isOnline: () => online, onOnline: listen },
      },
    );
    await scope.persistence.commitCreate(testOperation);
    await expect(scope.service.recoverAfterReload()).resolves.toEqual({
      status: 'offline',
      pushed: 0,
      pulled: 0,
    });
    expect(scope.trace).toEqual([
      { status: 'idle' },
      { status: 'synchronizing' },
      { status: 'offline' },
    ]);
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
    expect(submit).not.toHaveBeenCalled();
    online = true;
    resume();
    await vi.waitFor(() => expect(scope.service.status().status).toBe('completed'));
    expect(scope.trace.slice(3)).toEqual([
      { status: 'synchronizing' },
      { status: 'completed', pushed: 1, pulled: 1 },
    ]);
    expect(listen).toHaveBeenCalledOnce();
  });

  it('records all bounded retry transitions, exhaustion, and explicit manual recovery', async () => {
    harness = new SynchronizationTestHarness();
    const clock = { value: 0 };
    const scheduler = new ControlledSynchronizationScheduler();
    let available = false;
    const submit = vi.fn(async () => {
      if (!available) throw new SynchronizationUnavailableError();
      return accepted(testOperation);
    });
    const scope = harness.scope(
      { submitOperation: submit, pullChanges: async () => emptyPage },
      {
        clock,
        scheduler,
        jitter: (maximum) => maximum,
      },
    );
    await scope.persistence.commitCreate(testOperation);
    await scope.service.recoverAfterReload();
    for (let attempt = 2; attempt <= 5; attempt++) {
      scheduler.runNext(clock);
      await vi.waitFor(() =>
        expect(scope.service.status()).toMatchObject({ attemptCount: attempt }),
      );
      // Status settlement precedes asynchronous lease release.
      await vi.waitFor(async () =>
        expect(
          (await harness.database.synchronizationLeases.get('technical-records'))?.expiresAt,
        ).toBe(0),
      );
    }
    expect(scope.trace).toEqual([
      { status: 'idle' },
      ...[1, 2, 3, 4].flatMap((attemptCount) => [
        { status: 'synchronizing' },
        {
          status: 'scheduled',
          phase: 'push',
          attemptCount,
          nextEligibleAt: [1000, 3000, 7000, 15000][attemptCount - 1],
        },
      ]),
      { status: 'synchronizing' },
      { status: 'exhausted', phase: 'push', attemptCount: 5, reason: 'unavailable' },
    ]);
    await expect(scope.service.recoverAfterReload()).resolves.toMatchObject({
      status: 'exhausted',
    });
    expect(submit).toHaveBeenCalledTimes(5);
    available = true;
    await expect(scope.service.retryNow()).resolves.toEqual({
      status: 'completed',
      pushed: 1,
      pulled: 1,
    });
    expect(scope.trace.slice(-2)).toEqual([
      { status: 'manual-recovery' },
      { status: 'completed', pushed: 1, pulled: 1 },
    ]);
    expect(scheduler.delays()).toEqual([]);
    expect(await harness.database.outboxOperations.count()).toBe(0);
  });

  it('keeps a competing tab idle and schedules one recovery at lease expiry', async () => {
    harness = new SynchronizationTestHarness();
    const response = deferred<ChangePage>();
    const first = harness.scope({ submitOperation: vi.fn(), pullChanges: () => response.promise });
    const scheduler = new ControlledSynchronizationScheduler();
    const pull = vi.fn(async () => emptyPage);
    const second = harness.scope({ submitOperation: vi.fn(), pullChanges: pull }, { scheduler });
    const recovery = first.service.recoverAfterReload();
    await vi.waitFor(() => expect(first.service.status().status).toBe('synchronizing'));
    await expect(second.service.recoverAfterReload()).resolves.toEqual({
      status: 'already-running',
    });
    await expect(second.service.recoverAfterReload()).resolves.toEqual({
      status: 'already-running',
    });
    expect(second.trace).toEqual([{ status: 'idle' }]);
    expect(scheduler.delays()).toEqual([15000]);
    expect(pull).not.toHaveBeenCalled();
    response.resolve(emptyPage);
    await recovery;
    scheduler.runNext({ value: 0 });
    await vi.waitFor(() => expect(second.service.status().status).toBe('completed'));
    expect(second.trace).toEqual([
      { status: 'idle' },
      { status: 'synchronizing' },
      { status: 'completed', pushed: 0, pulled: 1 },
    ]);
  });
});
