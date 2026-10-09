import type { SynchronizationOwnership } from '../persistence/synchronization-ownership';
import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import type { SynchronizationRetryPersistence } from '../persistence/synchronization-retry-persistence';
import type { SynchronizationRetryPhase } from '../persistence/local-synchronization-retry-state';
import type { SynchronizationCoordinator } from './synchronization-coordinator';
import type { NetworkStatus } from './network-status';
import { SynchronizationUnavailableError } from './synchronization-transport';
import {
  synchronizationFailureReason,
  synchronizationPersistence,
} from './synchronization-failure';

export class SynchronizationExchange {
  constructor(
    private readonly retries: SynchronizationRetryPersistence,
    private readonly coordinator: SynchronizationCoordinator,
    readonly network: NetworkStatus,
    readonly timeoutMilliseconds: number,
  ) {}

  assertOwner(ownership: SynchronizationOwnership): Promise<void> {
    return synchronizationPersistence(() => this.coordinator.assertOwner(ownership));
  }

  async reserve(
    ownership: SynchronizationOwnership,
    workId: string,
    phase: SynchronizationRetryPhase,
    operationId?: string,
  ): Promise<void> {
    await this.assertOwner(ownership);
    await synchronizationPersistence(() =>
      this.retries.reserveAttempt(workId, phase, operationId, ownership, this.timeoutMilliseconds),
    );
    if (ownership.isLost() || ownership.lease.expiresAt <= ownership.now())
      throw new SynchronizationOwnershipLostError();
  }

  async transportFailure(error: unknown, workId: string, ownership: SynchronizationOwnership) {
    await this.assertOwner(ownership);
    if (!(error instanceof SynchronizationUnavailableError)) {
      await synchronizationPersistence(() => this.retries.delete(workId, ownership));
    }
    return {
      status: 'failed' as const,
      error,
      reason: synchronizationFailureReason(error),
      ...(error instanceof SynchronizationUnavailableError
        ? { retryCategory: 'unavailable' as const }
        : {}),
    };
  }
}
