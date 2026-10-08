import type {
  LocalSynchronizationRetryState,
  SynchronizationRetryFailureCategory,
  SynchronizationRetryPhase,
} from '../persistence/local-synchronization-retry-state';
import {
  pushRetryWorkId,
  TECHNICAL_PULL_RETRY_WORK_ID,
} from '../persistence/local-synchronization-retry-state';
import type { SynchronizationRetryPersistence } from '../persistence/synchronization-retry-persistence';
import type { SynchronizationOwnership } from '../persistence/synchronization-ownership';
import type { SynchronizationCoordinator } from './synchronization-coordinator';
import type { RecoveryOutcome } from './synchronization-outcomes';
import type { SynchronizationClock, SynchronizationJitter } from './synchronization-runtime';
import type { SynchronizationStatusStore } from './synchronization-status-store';
import type { SynchronizationTriggers } from './synchronization-triggers';
import {
  MAXIMUM_SYNCHRONIZATION_ATTEMPTS,
  synchronizationRetryDecision,
} from './synchronization-retry-policy.rule';
import { synchronizationPersistence } from './synchronization-failure';

export interface RecoveryCounts {
  pushed: number;
  pulled: number;
}

export class SynchronizationRetryController {
  constructor(
    private readonly persistence: SynchronizationRetryPersistence,
    private readonly coordinator: SynchronizationCoordinator,
    private readonly clock: SynchronizationClock,
    private readonly jitter: SynchronizationJitter,
    private readonly status: SynchronizationStatusStore,
    private readonly triggers: SynchronizationTriggers,
  ) {}

  async restore(ownership: SynchronizationOwnership): Promise<RecoveryOutcome | undefined> {
    let retry = await synchronizationPersistence(() => this.persistence.current());
    await synchronizationPersistence(() => this.coordinator.assertOwner(ownership));
    if (retry?.inFlight && retry.attemptCount >= MAXIMUM_SYNCHRONIZATION_ATTEMPTS) {
      retry = { ...retry, exhausted: true, nextEligibleAt: null };
      delete retry.inFlight;
      const exhausted = retry;
      await synchronizationPersistence(() => this.persistence.put(exhausted, ownership));
    }
    if (retry?.exhausted) return this.blocked(retry, { pushed: 0, pulled: 0 });
    if (
      retry?.nextEligibleAt !== null &&
      retry?.nextEligibleAt !== undefined &&
      retry.nextEligibleAt > this.clock.now()
    ) {
      return this.blocked(retry, { pushed: 0, pulled: 0 });
    }
    return undefined;
  }

  async reset(ownership: SynchronizationOwnership): Promise<void> {
    const retry = await synchronizationPersistence(() => this.persistence.current());
    if (retry)
      await synchronizationPersistence(() =>
        this.persistence.put(
          {
            ...retry,
            attemptCount: 0,
            nextEligibleAt: this.clock.now(),
            exhausted: false,
          },
          ownership,
        ),
      );
  }

  async recordFailure(
    ownership: SynchronizationOwnership,
    phase: SynchronizationRetryPhase,
    operationId: string | undefined,
    failureCategory: SynchronizationRetryFailureCategory | undefined,
    counts: RecoveryCounts,
  ): Promise<RecoveryOutcome | undefined> {
    if (!failureCategory) return undefined;
    const workId = operationId ? pushRetryWorkId(operationId) : TECHNICAL_PULL_RETRY_WORK_ID;
    const existing = await synchronizationPersistence(() => this.persistence.get(workId));
    const attemptCount = existing?.attemptCount ?? 1;
    const decision = synchronizationRetryDecision(attemptCount, (maximum) =>
      this.jitter.sample(maximum),
    );
    const state: LocalSynchronizationRetryState = {
      workId,
      scope: 'technical-records',
      phase,
      ...(operationId ? { operationId } : {}),
      attemptCount,
      failureCategory,
      exhausted: decision.status === 'exhausted',
      nextEligibleAt:
        decision.status === 'retry' ? this.clock.now() + decision.delayMilliseconds : null,
    };
    await synchronizationPersistence(() => this.persistence.put(state, ownership));
    return this.blocked(state, counts);
  }

  blocked(state: LocalSynchronizationRetryState, counts: RecoveryCounts): RecoveryOutcome {
    if (state.exhausted) {
      const status = {
        status: 'exhausted' as const,
        phase: state.phase,
        attemptCount: state.attemptCount,
        reason: state.failureCategory,
      };
      this.status.settle(status);
      return { ...status, ...counts };
    }
    if (state.nextEligibleAt === null) throw new Error('A scheduled retry requires a time.');
    this.triggers.clearRetry();
    const status = {
      status: 'scheduled' as const,
      phase: state.phase,
      attemptCount: state.attemptCount,
      nextEligibleAt: state.nextEligibleAt,
    };
    this.status.settle(status);
    this.triggers.retryAt(state.nextEligibleAt);
    return { ...status, ...counts };
  }

  async clearPhase(
    ownership: SynchronizationOwnership,
    phase: SynchronizationRetryPhase,
  ): Promise<void> {
    const current = await synchronizationPersistence(() => this.persistence.current());
    if (current?.phase === phase)
      await synchronizationPersistence(() => this.persistence.delete(current.workId, ownership));
  }
}
