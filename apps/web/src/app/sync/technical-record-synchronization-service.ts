import { inject, Injectable, signal } from '@angular/core';
import type {
  ChangePage,
  OperationResult,
  RecordIdentifierRetiredError,
  RecordNotFoundError,
  RevisionConflictError,
  TechnicalRecordOperation,
} from './conformance';
import {
  SynchronizationBoundaryError,
  SynchronizationProtocolError,
  SynchronizationUnexpectedResponseError,
  SynchronizationUnavailableError,
} from './synchronization-transport';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { SynchronizationRetryPersistence } from '../persistence/synchronization-retry-persistence';
import type {
  LocalSynchronizationRetryState,
  SynchronizationRetryFailureCategory,
  SynchronizationRetryPhase,
} from '../persistence/local-synchronization-retry-state';
import {
  pushRetryWorkId,
  TECHNICAL_PULL_RETRY_WORK_ID,
} from '../persistence/local-synchronization-retry-state';
import { NETWORK_STATUS } from './network-status';
import {
  SYNCHRONIZATION_CLOCK,
  SYNCHRONIZATION_JITTER,
  SYNCHRONIZATION_SCHEDULER,
} from './synchronization-runtime';
import { SynchronizationCoordinator } from './synchronization-coordinator';

const MAXIMUM_ATTEMPTS = 5;
const MAXIMUM_RETRY_DELAY_MILLISECONDS = 8_000;

export type PushOutcome =
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'accepted'; operation: TechnicalRecordOperation; result: OperationResult }
  | {
      status: 'conflict';
      operation: TechnicalRecordOperation;
      conflict: RevisionConflictError | RecordNotFoundError | RecordIdentifierRetiredError;
    }
  | {
      status: 'failed';
      operation: TechnicalRecordOperation;
      error: unknown;
      reason: SynchronizationFailureReason;
      retryCategory?: SynchronizationRetryFailureCategory;
    };

export type PullOutcome =
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'applied'; page: ChangePage }
  | {
      status: 'failed';
      error: unknown;
      reason: SynchronizationFailureReason;
      retryCategory?: SynchronizationRetryFailureCategory;
    };

export type RecoveryOutcome =
  | { status: 'completed'; pushed: number; pulled: number }
  | { status: 'offline'; pushed: number; pulled: number }
  | { status: 'failed'; pushed: number; pulled: number; error: unknown }
  | {
      status: 'scheduled';
      pushed: number;
      pulled: number;
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      nextEligibleAt: number;
    }
  | {
      status: 'exhausted';
      pushed: number;
      pulled: number;
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      reason: SynchronizationRetryFailureCategory;
    }
  | { status: 'already-running' };

export type SynchronizationFailureReason =
  'unavailable' | 'protocol' | 'boundary' | 'unexpected-response' | 'local-persistence' | 'unknown';

export type SynchronizationStatus =
  | { status: 'idle' }
  | { status: 'synchronizing' }
  | { status: 'manual-recovery' }
  | { status: 'offline' }
  | {
      status: 'scheduled';
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      nextEligibleAt: number;
    }
  | {
      status: 'exhausted';
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      reason: SynchronizationRetryFailureCategory;
    }
  | { status: 'failed'; reason: SynchronizationFailureReason }
  | { status: 'completed'; pushed: number; pulled: number };

@Injectable({ providedIn: 'root' })
export class TechnicalRecordSynchronizationService {
  private readonly persistence = inject(TechnicalRecordPersistence);
  private readonly retryPersistence = inject(SynchronizationRetryPersistence);
  private readonly transport = inject(SYNCHRONIZATION_TRANSPORT);
  private readonly network = inject(NETWORK_STATUS);
  private readonly clock = inject(SYNCHRONIZATION_CLOCK);
  private readonly jitter = inject(SYNCHRONIZATION_JITTER);
  private readonly scheduler = inject(SYNCHRONIZATION_SCHEDULER);
  private readonly coordinator = inject(SynchronizationCoordinator);
  private pushInProgress = false;
  private pullInProgress = false;
  private recoveryInProgress = false;
  private coordinationInProgress = false;
  private coordinationCompletion: Promise<void> = Promise.resolve();
  private cancelScheduledRetry?: () => void;
  private cancelOnlineWait?: () => void;
  private cancelCoordinationWait?: () => void;
  readonly status = signal<SynchronizationStatus>({ status: 'idle' });

  async recoverAfterReload(): Promise<RecoveryOutcome> {
    const currentStatus = this.status();
    if (
      this.coordinationInProgress &&
      currentStatus.status !== 'synchronizing' &&
      currentStatus.status !== 'manual-recovery'
    ) {
      await this.coordinationCompletion;
    }
    return this.withCoordination(async () => this.recoverWhileOwner(), true);
  }

  private async recoverWhileOwner(): Promise<RecoveryOutcome> {
    while (this.recoveryInProgress) {
      const currentStatus = this.status();
      if (currentStatus.status === 'synchronizing' || currentStatus.status === 'manual-recovery') {
        return { status: 'already-running' };
      }
      await Promise.resolve();
    }

    const retry = await this.retryPersistence.current();
    if (retry?.exhausted) {
      const outcome = this.exhaustedOutcome(retry, 0, 0);
      this.status.set(this.exhaustedStatus(retry));
      return outcome;
    }
    if (
      retry?.nextEligibleAt !== null &&
      retry?.nextEligibleAt !== undefined &&
      retry.nextEligibleAt > this.clock.now()
    ) {
      this.scheduleRetry(retry);
      return this.scheduledOutcome(retry, 0, 0);
    }
    return this.runRecovery(false);
  }

  async startBackgroundRecovery(): Promise<RecoveryOutcome> {
    if (this.coordinationInProgress || this.recoveryInProgress) {
      return { status: 'already-running' };
    }
    return this.recoverAfterReload();
  }

  async retryNow(): Promise<RecoveryOutcome> {
    return this.withCoordination(async () => this.retryNowWhileOwner(), false);
  }

  private async retryNowWhileOwner(): Promise<RecoveryOutcome> {
    if (this.recoveryInProgress) return { status: 'already-running' };

    const retry = await this.retryPersistence.current();
    if (retry) {
      await this.retryPersistence.put({
        ...retry,
        attemptCount: 0,
        nextEligibleAt: this.clock.now(),
        exhausted: false,
      });
    }
    return this.runRecovery(true);
  }

  private async withCoordination(
    work: () => Promise<RecoveryOutcome>,
    scheduleWhenBusy: boolean,
  ): Promise<RecoveryOutcome> {
    while (this.coordinationInProgress) {
      const currentStatus = this.status();
      if (
        this.recoveryInProgress &&
        (currentStatus.status === 'synchronizing' || currentStatus.status === 'manual-recovery')
      ) {
        return { status: 'already-running' };
      }
      await this.coordinationCompletion;
    }
    while (this.recoveryInProgress) {
      const currentStatus = this.status();
      if (currentStatus.status === 'synchronizing' || currentStatus.status === 'manual-recovery') {
        return { status: 'already-running' };
      }
      await Promise.resolve();
    }
    let completeCoordination!: () => void;
    this.coordinationCompletion = new Promise<void>((resolve) => {
      completeCoordination = resolve;
    });
    this.coordinationInProgress = true;
    try {
      const attempt = await this.coordinator.tryAcquire();
      if (!attempt.acquired) {
        if (scheduleWhenBusy) this.scheduleCoordinationRetry(attempt.retryAt);
        return { status: 'already-running' };
      }
      this.cancelCoordinationWait?.();
      this.cancelCoordinationWait = undefined;
      const stopHeartbeat = this.coordinator.keepAlive(attempt.lease);
      try {
        return await work();
      } finally {
        stopHeartbeat();
        await this.coordinator.release(attempt.lease);
      }
    } finally {
      this.coordinationInProgress = false;
      completeCoordination();
    }
  }

  private scheduleCoordinationRetry(retryAt: number): void {
    if (this.cancelCoordinationWait) return;
    this.cancelCoordinationWait = this.scheduler.schedule(
      () => {
        this.cancelCoordinationWait = undefined;
        void this.recoverAfterReload();
      },
      Math.max(0, retryAt - this.clock.now()),
    );
  }

  private async runRecovery(manual: boolean): Promise<RecoveryOutcome> {
    if (this.recoveryInProgress) return { status: 'already-running' };

    this.clearScheduledRetry();
    this.recoveryInProgress = true;
    this.status.set({ status: manual ? 'manual-recovery' : 'synchronizing' });
    let pushed = 0;
    let pulled = 0;
    try {
      while (true) {
        const outcome = await this.pushOnePendingOperation();
        if (outcome.status === 'accepted') {
          await this.retryPersistence.delete(pushRetryWorkId(outcome.operation.operationId));
          pushed += 1;
          continue;
        }
        if (outcome.status === 'empty') {
          await this.clearCurrentRetryPhase('push');
          break;
        }
        if (outcome.status === 'conflict') {
          await this.retryPersistence.delete(pushRetryWorkId(outcome.operation.operationId));
          continue;
        }
        if (outcome.status === 'offline') return this.offlineOutcome(pushed, pulled);

        const retry = await this.recordRetryableFailure(
          'push',
          outcome.operation.operationId,
          outcome.retryCategory,
          pushed,
          pulled,
        );
        if (retry) return retry;
        this.status.set({ status: 'failed', reason: outcome.reason });
        return { status: 'failed', pushed, pulled, error: outcome.error };
      }

      while (true) {
        const outcome = await this.pullOnePage();
        if (outcome.status === 'applied') {
          await this.retryPersistence.delete(TECHNICAL_PULL_RETRY_WORK_ID);
          pulled += 1;
          if (outcome.page.hasMore) continue;
          this.status.set({ status: 'completed', pushed, pulled });
          return { status: 'completed', pushed, pulled };
        }
        if (outcome.status === 'offline') return this.offlineOutcome(pushed, pulled);
        if (outcome.status === 'empty') {
          await this.retryPersistence.delete(TECHNICAL_PULL_RETRY_WORK_ID);
          this.status.set({ status: 'completed', pushed, pulled });
          return { status: 'completed', pushed, pulled };
        }

        const retry = await this.recordRetryableFailure(
          'pull',
          undefined,
          outcome.retryCategory,
          pushed,
          pulled,
        );
        if (retry) return retry;
        this.status.set({ status: 'failed', reason: outcome.reason });
        return { status: 'failed', pushed, pulled, error: outcome.error };
      }
    } catch (error) {
      this.status.set({ status: 'failed', reason: this.failureReason(error) });
      return { status: 'failed', pushed, pulled, error };
    } finally {
      this.recoveryInProgress = false;
    }
  }

  async pushOnePendingOperation(): Promise<PushOutcome> {
    if (this.pushInProgress) return { status: 'empty' };
    if (!this.network.isOnline()) {
      if (!this.recoveryInProgress) this.status.set({ status: 'offline' });
      this.waitForOnline();
      return { status: 'offline' };
    }

    this.pushInProgress = true;
    try {
      const operation = await this.persistence.firstPendingOperation();
      if (!operation) return { status: 'empty' };

      let result: OperationResult;
      try {
        result = await this.transport.submitOperation(operation);
      } catch (error) {
        if (
          error instanceof SynchronizationProtocolError &&
          error.body.code === 'REVISION_CONFLICT'
        ) {
          await this.persistence.commitRevisionConflict(operation, error.body);
          return { status: 'conflict', operation, conflict: error.body };
        }
        if (
          error instanceof SynchronizationProtocolError &&
          (error.body.code === 'RECORD_NOT_FOUND' ||
            error.body.code === 'RECORD_IDENTIFIER_RETIRED')
        ) {
          await this.persistence.commitDeletionConflict(operation, error.body);
          return { status: 'conflict', operation, conflict: error.body };
        }
        const reason = this.transportFailureReason(error);
        if (!this.recoveryInProgress) this.status.set({ status: 'failed', reason });
        return {
          status: 'failed',
          operation,
          error,
          reason,
          ...(error instanceof SynchronizationUnavailableError
            ? { retryCategory: 'unavailable' as const }
            : {}),
        };
      }

      try {
        await this.persistence.commitAcceptedResult(operation, result);
        if (!this.recoveryInProgress) {
          this.status.set({ status: 'completed', pushed: 1, pulled: 0 });
        }
        return { status: 'accepted', operation, result };
      } catch (error) {
        if (!this.recoveryInProgress) {
          this.status.set({ status: 'failed', reason: 'local-persistence' });
        }
        return {
          status: 'failed',
          operation,
          error,
          reason: 'local-persistence',
          retryCategory: 'local-persistence',
        };
      }
    } finally {
      this.pushInProgress = false;
    }
  }

  async pullOnePage(): Promise<PullOutcome> {
    if (this.pullInProgress) return { status: 'empty' };
    if (!this.network.isOnline()) {
      if (!this.recoveryInProgress) this.status.set({ status: 'offline' });
      this.waitForOnline();
      return { status: 'offline' };
    }

    this.pullInProgress = true;
    try {
      const cursor = await this.persistence.synchronizationCursor();
      let page: ChangePage;
      try {
        page = await this.transport.pullChanges(cursor);
      } catch (error) {
        const reason = this.transportFailureReason(error);
        if (!this.recoveryInProgress) this.status.set({ status: 'failed', reason });
        return {
          status: 'failed',
          error,
          reason,
          ...(error instanceof SynchronizationUnavailableError
            ? { retryCategory: 'unavailable' as const }
            : {}),
        };
      }

      try {
        await this.persistence.commitPulledPage(page, { expectedCursor: cursor });
        return { status: 'applied', page };
      } catch (error) {
        if (!this.recoveryInProgress) {
          this.status.set({ status: 'failed', reason: 'local-persistence' });
        }
        return {
          status: 'failed',
          error,
          reason: 'local-persistence',
          retryCategory: 'local-persistence',
        };
      }
    } finally {
      this.pullInProgress = false;
    }
  }

  private async recordRetryableFailure(
    phase: SynchronizationRetryPhase,
    operationId: string | undefined,
    failureCategory: SynchronizationRetryFailureCategory | undefined,
    pushed: number,
    pulled: number,
  ): Promise<RecoveryOutcome | undefined> {
    if (!failureCategory) return undefined;

    const workId = operationId ? pushRetryWorkId(operationId) : TECHNICAL_PULL_RETRY_WORK_ID;
    const existing = await this.retryPersistence.get(workId);
    const attemptCount = (existing?.attemptCount ?? 0) + 1;
    if (attemptCount >= MAXIMUM_ATTEMPTS) {
      const exhausted: LocalSynchronizationRetryState = {
        workId,
        scope: 'technical-records',
        phase,
        ...(operationId ? { operationId } : {}),
        attemptCount,
        nextEligibleAt: null,
        failureCategory,
        exhausted: true,
      };
      await this.retryPersistence.put(exhausted);
      this.status.set(this.exhaustedStatus(exhausted));
      return this.exhaustedOutcome(exhausted, pushed, pulled);
    }

    const maximumDelay = Math.min(
      MAXIMUM_RETRY_DELAY_MILLISECONDS,
      1_000 * 2 ** (attemptCount - 1),
    );
    const sampledDelay = this.jitter.sample(maximumDelay);
    if (!Number.isFinite(sampledDelay) || sampledDelay < 0 || sampledDelay > maximumDelay) {
      throw new Error('The synchronization jitter returned an invalid delay.');
    }
    const state: LocalSynchronizationRetryState = {
      workId,
      scope: 'technical-records',
      phase,
      ...(operationId ? { operationId } : {}),
      attemptCount,
      nextEligibleAt: this.clock.now() + Math.floor(sampledDelay),
      failureCategory,
      exhausted: false,
    };
    await this.retryPersistence.put(state);
    this.scheduleRetry(state);
    return this.scheduledOutcome(state, pushed, pulled);
  }

  private scheduleRetry(state: LocalSynchronizationRetryState): void {
    if (state.nextEligibleAt === null) return;
    this.clearScheduledRetry();
    this.status.set({
      status: 'scheduled',
      phase: state.phase,
      attemptCount: state.attemptCount,
      nextEligibleAt: state.nextEligibleAt,
    });
    const delay = Math.max(0, state.nextEligibleAt - this.clock.now());
    this.cancelScheduledRetry = this.scheduler.schedule(() => {
      this.cancelScheduledRetry = undefined;
      void this.recoverAfterReload();
    }, delay);
  }

  private clearScheduledRetry(): void {
    this.cancelScheduledRetry?.();
    this.cancelScheduledRetry = undefined;
  }

  private waitForOnline(): void {
    if (this.cancelOnlineWait || !this.network.onOnline) return;
    this.cancelOnlineWait = this.network.onOnline(() => {
      this.cancelOnlineWait = undefined;
      void this.recoverAfterReload();
    });
  }

  private offlineOutcome(pushed: number, pulled: number): RecoveryOutcome {
    this.status.set({ status: 'offline' });
    this.waitForOnline();
    return { status: 'offline', pushed, pulled };
  }

  private async clearCurrentRetryPhase(phase: SynchronizationRetryPhase): Promise<void> {
    const current = await this.retryPersistence.current();
    if (current?.phase === phase) await this.retryPersistence.delete(current.workId);
  }

  private scheduledOutcome(
    state: LocalSynchronizationRetryState,
    pushed: number,
    pulled: number,
  ): RecoveryOutcome {
    if (state.nextEligibleAt === null) throw new Error('A scheduled retry requires a time.');
    return {
      status: 'scheduled',
      pushed,
      pulled,
      phase: state.phase,
      attemptCount: state.attemptCount,
      nextEligibleAt: state.nextEligibleAt,
    };
  }

  private exhaustedOutcome(
    state: LocalSynchronizationRetryState,
    pushed: number,
    pulled: number,
  ): RecoveryOutcome {
    return {
      status: 'exhausted',
      pushed,
      pulled,
      phase: state.phase,
      attemptCount: state.attemptCount,
      reason: state.failureCategory,
    };
  }

  private exhaustedStatus(state: LocalSynchronizationRetryState): SynchronizationStatus {
    return {
      status: 'exhausted',
      phase: state.phase,
      attemptCount: state.attemptCount,
      reason: state.failureCategory,
    };
  }

  private transportFailureReason(error: unknown): SynchronizationFailureReason {
    const reason = this.failureReason(error);
    return reason === 'local-persistence' ? 'unknown' : reason;
  }

  private failureReason(error: unknown): SynchronizationFailureReason {
    if (error instanceof SynchronizationUnavailableError) return 'unavailable';
    if (error instanceof SynchronizationProtocolError) return 'protocol';
    if (error instanceof SynchronizationBoundaryError) return 'boundary';
    if (error instanceof SynchronizationUnexpectedResponseError) return 'unexpected-response';
    if (error instanceof Error) return 'local-persistence';
    return 'unknown';
  }
}
