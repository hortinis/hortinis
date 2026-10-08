import { describe, expect, it, vi } from 'vitest';
import { SynchronizationTriggers } from './synchronization-triggers';
import { ControlledSynchronizationScheduler } from './testing/synchronization-test-harness';

describe('synchronization triggers', () => {
  it('replaces retry timers and clamps elapsed deadlines to zero', () => {
    const scheduler = new ControlledSynchronizationScheduler();
    const recover = vi.fn();
    const clock = { value: 100 };
    const triggers = new SynchronizationTriggers(
      scheduler,
      { isOnline: () => true },
      { now: () => clock.value },
      recover,
    );
    triggers.retryAt(200);
    triggers.retryAt(50);
    expect(scheduler.delays()).toEqual([0]);
    scheduler.runNext(clock);
    expect(recover).toHaveBeenCalledOnce();
    expect(scheduler.delays()).toEqual([]);
  });
  it('deduplicates lease waits and allows another wait after firing', () => {
    const scheduler = new ControlledSynchronizationScheduler();
    const recover = vi.fn();
    const clock = { value: 0 };
    const triggers = new SynchronizationTriggers(
      scheduler,
      { isOnline: () => true },
      { now: () => clock.value },
      recover,
    );
    triggers.coordinationAt(15000);
    triggers.coordinationAt(16000);
    expect(scheduler.delays()).toEqual([15000]);
    scheduler.runNext(clock);
    triggers.coordinationAt(20000);
    expect(scheduler.delays()).toEqual([5000]);
    triggers.clearCoordination();
    expect(scheduler.delays()).toEqual([]);
  });
  it('deduplicates online listeners and disposes every pending trigger', () => {
    const scheduler = new ControlledSynchronizationScheduler();
    let listener!: () => void;
    const cancel = vi.fn();
    const onOnline = vi.fn((callback: () => void) => {
      listener = callback;
      return cancel;
    });
    const recover = vi.fn();
    const triggers = new SynchronizationTriggers(
      scheduler,
      { isOnline: () => false, onOnline },
      { now: () => 0 },
      recover,
    );
    triggers.waitForOnline();
    triggers.waitForOnline();
    expect(onOnline).toHaveBeenCalledOnce();
    listener();
    expect(recover).toHaveBeenCalledOnce();
    triggers.waitForOnline();
    expect(onOnline).toHaveBeenCalledTimes(2);
    triggers.retryAt(100);
    triggers.coordinationAt(1000);
    triggers.dispose();
    triggers.dispose();
    expect(scheduler.delays()).toEqual([]);
    expect(cancel).toHaveBeenCalledOnce();
  });
  it('makes disposal terminal even for callbacks already queued by the runtime', () => {
    const scheduler = new ControlledSynchronizationScheduler();
    let online!: () => void;
    const cancel = vi.fn();
    const onOnline = vi.fn((callback: () => void) => {
      online = callback;
      return cancel;
    });
    const recover = vi.fn();
    const triggers = new SynchronizationTriggers(
      scheduler,
      { isOnline: () => false, onOnline },
      { now: () => 0 },
      recover,
    );
    triggers.retryAt(100);
    triggers.coordinationAt(1000);
    triggers.waitForOnline();
    triggers.dispose();
    // The runtime may already have queued a callback when cancellation occurs.
    scheduler.tasks.forEach((task) => task.run());
    online();
    triggers.retryAt(200);
    triggers.coordinationAt(2000);
    triggers.waitForOnline();
    triggers.dispose();
    expect(recover).not.toHaveBeenCalled();
    expect(scheduler.tasks).toHaveLength(2);
    expect(scheduler.delays()).toEqual([]);
    expect(onOnline).toHaveBeenCalledOnce();
    expect(cancel).toHaveBeenCalledOnce();
  });
  it('supports a network boundary without online notifications', () => {
    const triggers = new SynchronizationTriggers(
      { schedule: vi.fn() },
      { isOnline: () => false },
      { now: () => 0 },
      vi.fn(),
    );
    expect(() => {
      triggers.waitForOnline();
      triggers.dispose();
    }).not.toThrow();
  });
});
