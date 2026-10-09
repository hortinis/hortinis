import type { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import {
  SynchronizationOwnershipLostError,
  type SynchronizationOwnership,
} from '../persistence/synchronization-ownership';
import { SynchronizationRetryBlockedError } from '../persistence/synchronization-retry-persistence';
import type { RecoveryOutcome } from './synchronization-outcomes';
import type { TechnicalRecordPush, OwnedPushOutcome } from './technical-record-push';
import type { TechnicalRecordPull, OwnedPullOutcome } from './technical-record-pull';
import type {
  SynchronizationRetryController,
  RecoveryCounts,
} from './synchronization-retry-controller';
import type { SynchronizationStatusStore } from './synchronization-status-store';
import type { SynchronizationTriggers } from './synchronization-triggers';
import {
  synchronizationFailureReason,
  synchronizationPersistence,
  SynchronizationPersistenceError,
} from './synchronization-failure';

export class TechnicalRecordRecovery {
  private requested = 0;
  private handled = 0;
  get pendingRequest(): boolean {
    return this.requested > this.handled;
  }
  request(): void {
    this.requested += 1;
  }

  constructor(
    private readonly persistence: TechnicalRecordPersistence,
    private readonly push: TechnicalRecordPush,
    private readonly pull: TechnicalRecordPull,
    private readonly retries: SynchronizationRetryController,
    private readonly status: SynchronizationStatusStore,
    private readonly triggers: SynchronizationTriggers,
  ) {}

  async recover(
    ownership: SynchronizationOwnership,
    started: () => void,
    manual: boolean,
  ): Promise<RecoveryOutcome> {
    if (manual) await this.retries.reset(ownership);
    else {
      const restored = await this.retries.restore(ownership);
      if (restored) return restored;
    }
    started();
    this.triggers.clearRetry();
    this.status.startRecovery(manual);
    const counts = { pushed: 0, pulled: 0 };
    try {
      while (true) {
        this.handled = this.requested;
        const repair = await this.repair(ownership, counts);
        if (repair) return repair;
        const pushed = await this.pushPending(ownership, counts);
        if (pushed) return pushed;
        const pulled = await this.pullPages(ownership, counts);
        if (pulled) return pulled;
      }
    } catch (error) {
      return this.handleError(error, counts);
    }
  }

  private async repair(
    ownership: SynchronizationOwnership,
    counts: RecoveryCounts,
  ): Promise<RecoveryOutcome | undefined> {
    while ((await synchronizationPersistence(() => this.persistence.pullBoundary())).repair) {
      const outcome = await this.pull.run(ownership);
      if (outcome.status === 'applied') counts.pulled += 1;
      else return this.stop(ownership, outcome, 'pull', counts);
    }
    return undefined;
  }

  private async pushPending(
    ownership: SynchronizationOwnership,
    counts: RecoveryCounts,
  ): Promise<RecoveryOutcome | undefined> {
    while (true) {
      const outcome = await this.push.run(ownership);
      switch (outcome.status) {
        case 'accepted':
          counts.pushed += 1;
          break;
        case 'rejected':
        case 'conflict':
          break;
        case 'empty':
          await this.retries.clearPhase(ownership, 'push');
          return undefined;
        case 'offline':
        case 'failed':
          return this.stop(ownership, outcome, 'push', counts);
      }
    }
  }

  private async pullPages(
    ownership: SynchronizationOwnership,
    counts: RecoveryCounts,
  ): Promise<RecoveryOutcome | undefined> {
    while (true) {
      const outcome = await this.pull.run(ownership);
      if (outcome.status !== 'applied') return this.stop(ownership, outcome, 'pull', counts);
      counts.pulled += 1;
      if (outcome.page.hasMore) continue;
      if (
        this.pendingRequest ||
        (await synchronizationPersistence(() => this.persistence.firstPendingOperation()))
      )
        return undefined;
      const completed = { status: 'completed' as const, ...counts };
      this.status.settle(completed);
      return completed;
    }
  }

  private async stop(
    ownership: SynchronizationOwnership,
    outcome: Extract<OwnedPushOutcome | OwnedPullOutcome, { status: 'offline' | 'failed' }>,
    phase: 'push' | 'pull',
    counts: RecoveryCounts,
  ): Promise<RecoveryOutcome> {
    if (outcome.status === 'offline') {
      this.status.settle({ status: 'offline' });
      this.triggers.waitForOnline();
      return { status: 'offline', ...counts };
    }
    const retry = await this.retries.recordFailure(
      ownership,
      phase,
      'operation' in outcome ? outcome.operation.operationId : undefined,
      outcome.retryCategory,
      counts,
    );
    if (retry) return retry;
    this.status.settle({ status: 'failed', reason: outcome.reason });
    return { status: 'failed', ...counts, error: outcome.error };
  }

  private handleError(error: unknown, counts: RecoveryCounts): RecoveryOutcome {
    if (error instanceof SynchronizationOwnershipLostError) {
      this.triggers.clearRetry();
      this.status.settle({ status: 'ownership-lost' });
      return { status: 'ownership-lost' };
    }
    if (error instanceof SynchronizationRetryBlockedError)
      return this.retries.blocked(error.state, counts);
    this.status.settle({ status: 'failed', reason: synchronizationFailureReason(error) });
    return {
      status: 'failed',
      ...counts,
      error: error instanceof SynchronizationPersistenceError ? error.cause : error,
    };
  }
}
