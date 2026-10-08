import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChangePage, OperationResult } from './conformance';
import {
  SynchronizationUnavailableError,
  SynchronizationUnexpectedResponseError,
} from './synchronization-transport';
import {
  accepted,
  ControlledSynchronizationScheduler,
  deferred,
  emptyPage,
  SynchronizationTestHarness,
  testOperation,
} from './testing/synchronization-test-harness';

describe('synchronization facade admission', () => {
  let harness: SynchronizationTestHarness;
  afterEach(async () => {
    await harness.close();
  });

  it.each(['push', 'pull', 'recovery'] as const)(
    'returns busy for standalone calls while %s is running',
    async (phase) => {
      harness = new SynchronizationTestHarness();
      const pushResponse = deferred<OperationResult>();
      const pullResponse = deferred<ChangePage>();
      const transport = {
        submitOperation: vi.fn(() => pushResponse.promise),
        pullChanges: vi.fn(() => pullResponse.promise),
      };
      const scope = harness.scope(transport);
      if (phase !== 'pull') await scope.persistence.commitCreate(testOperation);
      const run =
        phase === 'push'
          ? scope.service.pushOnePendingOperation()
          : phase === 'pull'
            ? scope.service.pullOnePage()
            : scope.service.recoverAfterReload();
      await vi.waitFor(() =>
        expect(
          phase === 'pull' ? transport.pullChanges : transport.submitOperation,
        ).toHaveBeenCalledOnce(),
      );
      const before = await harness.database.synchronizationRetryState.toArray();
      const trace = [...scope.trace];
      await expect(scope.service.pushOnePendingOperation()).resolves.toEqual({ status: 'busy' });
      await expect(scope.service.pullOnePage()).resolves.toEqual({ status: 'busy' });
      if (phase === 'recovery')
        await expect(scope.service.retryNow()).resolves.toEqual({ status: 'already-running' });
      expect(await harness.database.synchronizationRetryState.toArray()).toEqual(before);
      expect(scope.trace).toEqual(trace);
      pushResponse.resolve(accepted(testOperation));
      pullResponse.resolve(emptyPage);
      await run;
    },
  );

  it.each([
    ['push', 'recoverAfterReload'],
    ['push', 'retryNow'],
    ['pull', 'recoverAfterReload'],
    ['pull', 'retryNow'],
  ] as const)('waits for standalone %s before %s', async (phase, method) => {
    harness = new SynchronizationTestHarness();
    const pushResponse = deferred<OperationResult>();
    const pullResponse = deferred<ChangePage>();
    const transport = {
      submitOperation: vi.fn(() => pushResponse.promise),
      pullChanges: vi.fn().mockResolvedValue(emptyPage),
    };
    if (phase === 'pull') transport.pullChanges.mockImplementationOnce(() => pullResponse.promise);
    const scope = harness.scope(transport);
    if (phase === 'push') await scope.persistence.commitCreate(testOperation);
    const standalone =
      phase === 'push' ? scope.service.pushOnePendingOperation() : scope.service.pullOnePage();
    await vi.waitFor(() =>
      expect(
        phase === 'push' ? transport.submitOperation : transport.pullChanges,
      ).toHaveBeenCalledOnce(),
    );
    const before = await harness.database.synchronizationRetryState.toArray();
    const trace = [...scope.trace];
    const acquire = vi.spyOn(scope.coordinator, 'tryAcquire');
    let settled = false;
    const recovery = scope.service[method]().then((outcome) => {
      settled = true;
      return outcome;
    });
    await Promise.resolve();
    expect(settled).toBe(false);
    expect(acquire).not.toHaveBeenCalled();
    expect(await harness.database.synchronizationRetryState.toArray()).toEqual(before);
    expect(scope.trace).toEqual(trace);
    pushResponse.resolve(accepted(testOperation));
    pullResponse.resolve(emptyPage);
    await standalone;
    await expect(recovery).resolves.toEqual({ status: 'completed', pushed: 0, pulled: 1 });
    expect(transport.submitOperation).toHaveBeenCalledTimes(phase === 'push' ? 1 : 0);
    expect(transport.pullChanges).toHaveBeenCalledTimes(phase === 'pull' ? 2 : 1);
    expect(scope.trace).toContainEqual({
      status: method === 'retryNow' ? 'manual-recovery' : 'synchronizing',
    });
  });

  it('preserves manual reset when a standalone push fails before queued retry starts', async () => {
    harness = new SynchronizationTestHarness();
    const response = deferred<OperationResult>();
    const scheduler = new ControlledSynchronizationScheduler();
    const transport = {
      submitOperation: vi
        .fn()
        .mockImplementationOnce(() => response.promise)
        .mockResolvedValue(accepted(testOperation)),
      pullChanges: vi.fn().mockResolvedValue(emptyPage),
    };
    const scope = harness.scope(transport, { scheduler, jitter: () => 100 });
    await scope.persistence.commitCreate(testOperation);
    const standalone = scope.service.pushOnePendingOperation();
    await vi.waitFor(() => expect(transport.submitOperation).toHaveBeenCalledOnce());
    const recovery = scope.service.retryNow();
    response.reject(new SynchronizationUnavailableError());
    await expect(standalone).resolves.toMatchObject({ status: 'failed' });
    await expect(recovery).resolves.toEqual({ status: 'completed', pushed: 1, pulled: 1 });
    expect(transport.submitOperation).toHaveBeenCalledTimes(2);
    expect(scheduler.delays()).toEqual([]);
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
  });

  it.each(['push', 'recovery'] as const)(
    'does not rearm retry after destruction during %s',
    async (phase) => {
      harness = new SynchronizationTestHarness();
      const response = deferred<OperationResult>();
      const scheduler = new ControlledSynchronizationScheduler();
      const transport = {
        submitOperation: vi.fn(() => response.promise),
        pullChanges: vi.fn(),
      };
      const scope = harness.scope(transport, { scheduler, jitter: () => 100 });
      await scope.persistence.commitCreate(testOperation);
      const run =
        phase === 'push'
          ? scope.service.pushOnePendingOperation()
          : scope.service.recoverAfterReload();
      await vi.waitFor(() => expect(transport.submitOperation).toHaveBeenCalledOnce());
      scope.destroy();
      response.reject(new SynchronizationUnavailableError());
      await expect(run).resolves.toMatchObject({
        status: phase === 'push' ? 'failed' : 'scheduled',
      });
      expect(scheduler.delays()).toEqual([]);
      expect(await scope.retries.current()).toMatchObject({ attemptCount: 1, nextEligibleAt: 100 });
      expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
        testOperation,
      );
      expect(transport.pullChanges).not.toHaveBeenCalled();
      await expect(scope.service.recoverAfterReload()).resolves.toEqual({
        status: 'already-running',
      });
      await expect(scope.service.pushOnePendingOperation()).resolves.toEqual({ status: 'busy' });
      expect(transport.submitOperation).toHaveBeenCalledOnce();
    },
  );

  it('does not start queued recovery when its scope is destroyed during standalone work', async () => {
    harness = new SynchronizationTestHarness();
    const response = deferred<OperationResult>();
    const transport = {
      submitOperation: vi.fn(() => response.promise),
      pullChanges: vi.fn().mockResolvedValue(emptyPage),
    };
    const scope = harness.scope(transport);
    await scope.persistence.commitCreate(testOperation);
    const standalone = scope.service.pushOnePendingOperation();
    await vi.waitFor(() => expect(transport.submitOperation).toHaveBeenCalledOnce());
    const recovery = scope.service.recoverAfterReload();
    const acquire = vi.spyOn(scope.coordinator, 'tryAcquire');
    scope.destroy();
    response.resolve(accepted(testOperation));
    await expect(standalone).resolves.toMatchObject({ status: 'accepted' });
    await expect(recovery).resolves.toEqual({ status: 'already-running' });
    expect(acquire).not.toHaveBeenCalled();
    expect(transport.pullChanges).not.toHaveBeenCalled();
    expect(await harness.database.outboxOperations.count()).toBe(0);
  });

  it('does not restart pending background recovery after destruction during lease release', async () => {
    harness = new SynchronizationTestHarness();
    const response = deferred<ChangePage>();
    const transport = {
      submitOperation: vi.fn(),
      pullChanges: vi.fn(() => response.promise),
    };
    const scope = harness.scope(transport);
    const release = scope.coordinator.release.bind(scope.coordinator);
    vi.spyOn(scope.coordinator, 'release').mockImplementationOnce(async (lease) => {
      await expect(scope.service.startBackgroundRecovery()).resolves.toEqual({
        status: 'already-running',
      });
      scope.destroy();
      await release(lease);
    });
    const acquire = vi.spyOn(scope.coordinator, 'tryAcquire');
    const run = scope.service.recoverAfterReload();
    await vi.waitFor(() => expect(transport.pullChanges).toHaveBeenCalledOnce());
    response.resolve(emptyPage);
    await expect(run).resolves.toEqual({ status: 'completed', pushed: 0, pulled: 1 });
    expect(acquire).toHaveBeenCalledOnce();
    expect(transport.pullChanges).toHaveBeenCalledOnce();
  });

  it('distinguishes an empty outbox from another tab owning the lease', async () => {
    harness = new SynchronizationTestHarness();
    const first = harness.scope({ submitOperation: vi.fn(), pullChanges: async () => emptyPage });
    await expect(first.service.pushOnePendingOperation()).resolves.toEqual({ status: 'empty' });
    const owner = await first.coordinator.tryAcquire();
    expect(owner.acquired).toBe(true);
    const second = harness.scope({ submitOperation: vi.fn(), pullChanges: vi.fn() });
    await expect(second.service.pushOnePendingOperation()).resolves.toEqual({ status: 'busy' });
    await expect(second.service.pullOnePage()).resolves.toEqual({ status: 'busy' });
    expect(second.trace).toEqual([{ status: 'idle' }]);
  });

  it('cancels pending retry timers when its Angular scope is destroyed', async () => {
    harness = new SynchronizationTestHarness();
    const scheduler = new ControlledSynchronizationScheduler();
    const scope = harness.scope(
      {
        submitOperation: async () => {
          throw new SynchronizationUnavailableError();
        },
        pullChanges: vi.fn(),
      },
      { scheduler, jitter: () => 100 },
    );
    await scope.persistence.commitCreate(testOperation);
    await scope.service.recoverAfterReload();
    expect(scheduler.delays()).toEqual([100]);
    scope.destroy();
    expect(scheduler.delays()).toEqual([]);
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
      testOperation,
    );
  });

  it('publishes a permanent failure without scheduling a retry or dropping work', async () => {
    harness = new SynchronizationTestHarness();
    const error = new SynchronizationUnexpectedResponseError(500);
    const scheduler = new ControlledSynchronizationScheduler();
    const scope = harness.scope(
      {
        submitOperation: async () => {
          throw error;
        },
        pullChanges: vi.fn(),
      },
      { scheduler },
    );
    await scope.persistence.commitCreate(testOperation);
    await expect(scope.service.recoverAfterReload()).resolves.toEqual({
      status: 'failed',
      pushed: 0,
      pulled: 0,
      error,
    });
    expect(scope.trace).toEqual([
      { status: 'idle' },
      { status: 'synchronizing' },
      { status: 'failed', reason: 'unexpected-response' },
    ]);
    expect(scheduler.delays()).toEqual([]);
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
      testOperation,
    );
  });
});
