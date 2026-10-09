import { DestroyRef, inject, Injectable } from '@angular/core';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { SynchronizationRetryPersistence } from '../persistence/synchronization-retry-persistence';
import { NETWORK_STATUS } from './network-status';
import {
  SYNCHRONIZATION_CLOCK,
  SYNCHRONIZATION_JITTER,
  SYNCHRONIZATION_SCHEDULER,
} from './synchronization-runtime';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { SynchronizationCoordinator } from './synchronization-coordinator';
import { SYNC_REQUEST_TIMEOUT_MILLISECONDS } from './sync-api-config';
import { SynchronizationStatusStore } from './synchronization-status-store';
import { SynchronizationTriggers } from './synchronization-triggers';
import { SynchronizationExecution } from './synchronization-execution';
import { SynchronizationExchange } from './synchronization-exchange';
import { SynchronizationRetryController } from './synchronization-retry-controller';
import { TechnicalRecordPush } from './technical-record-push';
import { TechnicalRecordPull } from './technical-record-pull';
import { TechnicalRecordRecovery } from './technical-record-recovery';
import type { PushOutcome, PullOutcome, RecoveryOutcome } from './synchronization-outcomes';
import type { SynchronizationOwnership } from '../persistence/synchronization-ownership';

export type {
  PushOutcome,
  PullOutcome,
  RecoveryOutcome,
  SynchronizationFailureReason,
  SynchronizationStatus,
} from './synchronization-outcomes';

@Injectable({ providedIn: 'root' })
export class TechnicalRecordSynchronizationService {
  private readonly persistence = inject(TechnicalRecordPersistence);
  private readonly coordinator = inject(SynchronizationCoordinator);
  private readonly clock = inject(SYNCHRONIZATION_CLOCK);
  private readonly network = inject(NETWORK_STATUS);
  private readonly transport = inject(SYNCHRONIZATION_TRANSPORT);
  private readonly store = new SynchronizationStatusStore();
  private readonly triggers = new SynchronizationTriggers(
    inject(SYNCHRONIZATION_SCHEDULER),
    this.network,
    this.clock,
    () => {
      void this.recoverAfterReload();
    },
  );
  private readonly retries = new SynchronizationRetryController(
    inject(SynchronizationRetryPersistence),
    this.coordinator,
    this.clock,
    inject(SYNCHRONIZATION_JITTER),
    this.store,
    this.triggers,
  );
  private readonly exchange = new SynchronizationExchange(
    inject(SynchronizationRetryPersistence),
    this.coordinator,
    this.network,
    inject(SYNC_REQUEST_TIMEOUT_MILLISECONDS),
  );
  private readonly push = new TechnicalRecordPush(this.persistence, this.transport, this.exchange);
  private readonly pull = new TechnicalRecordPull(this.persistence, this.transport, this.exchange);
  private readonly recovery = new TechnicalRecordRecovery(
    this.persistence,
    this.push,
    this.pull,
    this.retries,
    this.store,
    this.triggers,
  );
  private readonly execution = new SynchronizationExecution(
    this.coordinator,
    this.clock,
    this.triggers,
    this.store,
  );
  readonly status = this.store.status;
  readonly rejections = this.store.rejections;

  constructor() {
    const stopObserving = this.persistence.observeRejections((summary) =>
      this.store.rejections.set(summary),
    );
    inject(DestroyRef).onDestroy(() => {
      stopObserving();
      this.triggers.dispose();
    });
  }

  recoverAfterReload(): Promise<RecoveryOutcome> {
    return this.recover(false);
  }
  retryNow(): Promise<RecoveryOutcome> {
    return this.recover(true);
  }

  startBackgroundRecovery(): Promise<RecoveryOutcome> {
    this.recovery.request();
    return this.execution.active
      ? Promise.resolve({ status: 'already-running' })
      : this.recoverAfterReload();
  }

  private recover(manual: boolean): Promise<RecoveryOutcome> {
    return this.execution.run(
      (ownership, started) => this.recovery.recover(ownership, started, manual),
      { status: 'already-running' } as RecoveryOutcome,
      !manual,
      (completed) => this.afterRelease(completed),
    );
  }

  pushOnePendingOperation(): Promise<PushOutcome> {
    return this.standalone((ownership) => this.push.run(ownership), 'push');
  }

  pullOnePage(): Promise<PullOutcome> {
    return this.standalone((ownership) => this.pull.run(ownership), 'pull');
  }

  private standalone<Outcome extends PushOutcome | PullOutcome>(
    work: (ownership: SynchronizationOwnership) => Promise<Outcome>,
    phase: 'push' | 'pull',
  ): Promise<Outcome> {
    return this.execution.run(
      async (ownership, started) => {
        started();
        const outcome = await work(ownership);
        this.store.standalone(outcome);
        if (outcome.status === 'offline') this.triggers.waitForOnline();
        if (outcome.status === 'failed') {
          await this.retries.recordFailure(
            ownership,
            phase,
            'operation' in outcome ? outcome.operation.operationId : undefined,
            outcome.retryCategory,
            { pushed: 0, pulled: 0 },
          );
        }
        return outcome;
      },
      { status: 'busy' } as Outcome,
      false,
      (completed) => this.afterRelease(completed),
      'standalone',
    );
  }

  private afterRelease(completed: boolean): void {
    if (this.triggers.active && completed && this.recovery.pendingRequest)
      void this.recoverAfterReload();
  }
}
