import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RecoveryOutcome } from './synchronization-outcomes';
import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import { collaboratorHarness } from './testing/synchronization-collaborator-harness';
import { deferred, SynchronizationTestHarness } from './testing/synchronization-test-harness';

const completed: RecoveryOutcome = { status: 'completed', pushed: 0, pulled: 0 };
const busy: RecoveryOutcome = { status: 'already-running' };

describe('synchronization execution ownership', () => {
  let harness: SynchronizationTestHarness;
  afterEach(async () => {
    await harness.close();
  });

  it('rejects a running contender without acquiring another lease', async () => {
    harness = new SynchronizationTestHarness();
    const rig = collaboratorHarness(harness);
    const response = deferred<RecoveryOutcome>();
    const acquire = vi.spyOn(rig.coordinator, 'tryAcquire');
    const release = vi.spyOn(rig.coordinator, 'release');
    const work = vi.fn();
    const first = rig.execution.run<RecoveryOutcome>(
      async (_owner, started) => {
        started();
        work();
        return response.promise;
      },
      busy,
      true,
      vi.fn(),
    );
    await vi.waitFor(() => expect(work).toHaveBeenCalledOnce());
    const contender = vi.fn(async () => completed);
    await expect(
      rig.execution.run<RecoveryOutcome>(contender, busy, true, vi.fn()),
    ).resolves.toEqual(busy);
    expect(contender).not.toHaveBeenCalled();
    expect(acquire).toHaveBeenCalledOnce();
    response.resolve(completed);
    await first;
    expect(release).toHaveBeenCalledOnce();
    expect(rig.execution.active).toBe(false);
  });

  it('waits on release completion and starts queued work afterwards', async () => {
    harness = new SynchronizationTestHarness();
    const rig = collaboratorHarness(harness);
    const releasing = deferred<void>();
    const release = rig.coordinator.release.bind(rig.coordinator);
    const spy = vi.spyOn(rig.coordinator, 'release').mockImplementationOnce(async (lease) => {
      await releasing.promise;
      await release(lease);
    });
    const first = rig.execution.run<RecoveryOutcome>(
      async (_owner, started) => {
        started();
        return completed;
      },
      busy,
      false,
      vi.fn(),
    );
    await vi.waitFor(() => expect(spy).toHaveBeenCalledOnce());
    const secondWork = vi.fn(async (_owner, started: () => void) => {
      started();
      return completed;
    });
    const second = rig.execution.run<RecoveryOutcome>(secondWork, busy, false, vi.fn());
    expect(secondWork).not.toHaveBeenCalled();
    releasing.resolve();
    await expect(first).resolves.toEqual(completed);
    await expect(second).resolves.toEqual(completed);
    expect(secondWork).toHaveBeenCalledOnce();
  });

  it.each(['acquire', 'work', 'release'] as const)(
    'unblocks admission after a failure during %s',
    async (phase) => {
      harness = new SynchronizationTestHarness();
      const rig = collaboratorHarness(harness);
      const failure = new Error('controlled failure');
      if (phase === 'acquire')
        vi.spyOn(rig.coordinator, 'tryAcquire').mockRejectedValueOnce(failure);
      if (phase === 'release') vi.spyOn(rig.coordinator, 'release').mockRejectedValueOnce(failure);
      await expect(
        rig.execution.run<RecoveryOutcome>(
          async (_owner, started) => {
            started();
            if (phase === 'work') throw failure;
            return completed;
          },
          busy,
          false,
          vi.fn(),
        ),
      ).rejects.toBe(failure);
      expect(rig.execution.active).toBe(false);
      await expect(
        rig.execution.run<RecoveryOutcome>(async () => completed, busy, false, vi.fn()),
      ).resolves.toEqual(completed);
    },
  );

  it('releases an acquired lease without starting work if disposal occurred during acquisition', async () => {
    harness = new SynchronizationTestHarness();
    const rig = collaboratorHarness(harness);
    const acquiring = deferred<void>();
    const acquire = rig.coordinator.tryAcquire.bind(rig.coordinator);
    const entered = vi.fn();
    vi.spyOn(rig.coordinator, 'tryAcquire').mockImplementationOnce(async () => {
      entered();
      await acquiring.promise;
      return acquire();
    });
    const release = vi.spyOn(rig.coordinator, 'release');
    const work = vi.fn(async () => completed);
    const run = rig.execution.run<RecoveryOutcome>(work, busy, false, vi.fn());
    await vi.waitFor(() => expect(entered).toHaveBeenCalledOnce());
    rig.triggers.dispose();
    acquiring.resolve();
    await expect(run).resolves.toEqual(busy);
    expect(work).not.toHaveBeenCalled();
    expect(release).toHaveBeenCalledOnce();
    expect(rig.execution.active).toBe(false);
    expect((await rig.coordinator.tryAcquire()).acquired).toBe(true);
  });

  it('marks heartbeat loss, clears its retry timer, and releases the lease', async () => {
    harness = new SynchronizationTestHarness();
    const rig = collaboratorHarness(harness);
    let lose!: () => void;
    const stop = vi.fn();
    vi.spyOn(rig.coordinator, 'keepAlive').mockImplementation((_lease, onLost) => {
      lose = onLost!;
      return stop;
    });
    const response = deferred<RecoveryOutcome>();
    const entered = vi.fn();
    rig.triggers.retryAt(100);
    const run = rig.execution.run<RecoveryOutcome>(
      async (ownership, started) => {
        started();
        entered();
        await response.promise;
        if (ownership.isLost()) throw new SynchronizationOwnershipLostError();
        return completed;
      },
      busy,
      true,
      vi.fn(),
    );
    await vi.waitFor(() => expect(entered).toHaveBeenCalledOnce());
    lose();
    expect(rig.scheduler.delays()).toEqual([]);
    response.resolve(completed);
    await expect(run).resolves.toEqual({ status: 'ownership-lost' });
    expect(rig.status.status()).toEqual({ status: 'ownership-lost' });
    expect(stop).toHaveBeenCalledOnce();
  });
});
