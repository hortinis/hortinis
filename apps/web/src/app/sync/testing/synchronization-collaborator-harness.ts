import { vi } from 'vitest';
import type { SynchronizationTransport } from '../synchronization-transport';
import type { NetworkStatus } from '../network-status';
import { SynchronizationStatusStore } from '../synchronization-status-store';
import { SynchronizationTriggers } from '../synchronization-triggers';
import { SynchronizationExecution } from '../synchronization-execution';
import { SynchronizationExchange } from '../synchronization-exchange';
import { SynchronizationRetryController } from '../synchronization-retry-controller';
import { TechnicalRecordPush } from '../technical-record-push';
import { TechnicalRecordPull } from '../technical-record-pull';
import { TechnicalRecordRecovery } from '../technical-record-recovery';
import {
  ControlledSynchronizationScheduler,
  SynchronizationTestHarness,
  accepted,
  emptyPage,
} from './synchronization-test-harness';

export function collaboratorHarness(
  harness: SynchronizationTestHarness,
  options: {
    transport?: SynchronizationTransport;
    network?: NetworkStatus;
    jitter?: (maximum: number) => number;
  } = {},
) {
  const transport = options.transport ?? {
    submitOperation: vi.fn(async (operation) => accepted(operation)),
    pullChanges: vi.fn(async () => emptyPage),
  };
  const network = options.network ?? { isOnline: () => true };
  const clock = { value: 0 };
  const scope = harness.scope(transport, { clock, network });
  const scheduler = new ControlledSynchronizationScheduler();
  const status = new SynchronizationStatusStore();
  const resume = vi.fn();
  const triggers = new SynchronizationTriggers(
    scheduler,
    network,
    { now: () => clock.value },
    resume,
  );
  const exchange = new SynchronizationExchange(scope.retries, scope.coordinator, network, 10000);
  const retries = new SynchronizationRetryController(
    scope.retries,
    scope.coordinator,
    { now: () => clock.value },
    { sample: options.jitter ?? (() => 0) },
    status,
    triggers,
  );
  const push = new TechnicalRecordPush(scope.persistence, transport, exchange);
  const pull = new TechnicalRecordPull(scope.persistence, transport, exchange);
  const recovery = new TechnicalRecordRecovery(
    scope.persistence,
    push,
    pull,
    retries,
    status,
    triggers,
  );
  const execution = new SynchronizationExecution(
    scope.coordinator,
    { now: () => clock.value },
    triggers,
    status,
  );
  return {
    ...scope,
    transport,
    clock,
    scheduler,
    status,
    resume,
    triggers,
    exchange,
    retries,
    push,
    pull,
    recovery,
    execution,
    async ownership() {
      const attempt = await scope.coordinator.tryAcquire();
      if (!attempt.acquired) throw new Error('Expected ownership.');
      return { lease: attempt.lease, now: () => clock.value, isLost: () => false };
    },
  };
}
