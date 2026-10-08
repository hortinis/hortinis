import { describe, expect, it } from 'vitest';
import { SynchronizationStatusStore } from './synchronization-status-store';
import { accepted, testOperation } from './testing/synchronization-test-harness';

describe('synchronization status transitions', () => {
  it('rejects nested recovery and standalone publication during recovery', () => {
    const store = new SynchronizationStatusStore();
    store.startRecovery(false);
    expect(() => store.startRecovery(true)).toThrow('already running');
    expect(() => store.standalone({ status: 'offline' })).toThrow('cannot publish');
    expect(store.status()).toEqual({ status: 'synchronizing' });
  });
  it('requires a running recovery for aggregate completion', () => {
    const store = new SynchronizationStatusStore();
    expect(() => store.settle({ status: 'completed', pushed: 0, pulled: 1 })).toThrow(
      'active recovery',
    );
    store.startRecovery(true);
    store.settle({ status: 'completed', pushed: 0, pulled: 1 });
    expect(store.status()).toEqual({ status: 'completed', pushed: 0, pulled: 1 });
  });
  it('preserves standalone success and keeps busy or empty calls silent', () => {
    const store = new SynchronizationStatusStore();
    store.standalone({
      status: 'accepted',
      operation: testOperation,
      result: accepted(testOperation),
    });
    store.standalone({ status: 'busy' });
    store.standalone({ status: 'empty' });
    expect(store.status()).toEqual({ status: 'completed', pushed: 1, pulled: 0 });
  });
});
