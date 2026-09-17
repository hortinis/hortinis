import { inject, Injectable } from '@angular/core';
import type {
  ChangePage,
  OperationResult,
  RevisionConflictError,
  TechnicalRecordOperation,
} from './conformance';
import { SynchronizationProtocolError } from './synchronization-transport';
import { SYNCHRONIZATION_TRANSPORT } from './synchronization-transport.token';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { NETWORK_STATUS } from './network-status';

export type PushOutcome =
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'accepted'; operation: TechnicalRecordOperation; result: OperationResult }
  | { status: 'conflict'; operation: TechnicalRecordOperation; conflict: RevisionConflictError }
  | { status: 'failed'; operation: TechnicalRecordOperation; error: unknown };

export type PullOutcome =
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'applied'; page: ChangePage }
  | { status: 'failed'; error: unknown };

export type RecoveryOutcome =
  | { status: 'completed'; pushed: number; pulled: number }
  | { status: 'offline'; pushed: number; pulled: number }
  | { status: 'failed'; pushed: number; pulled: number; error: unknown }
  | { status: 'already-running' };

@Injectable({ providedIn: 'root' })
export class TechnicalRecordSynchronizationService {
  private readonly persistence = inject(TechnicalRecordPersistence);
  private readonly transport = inject(SYNCHRONIZATION_TRANSPORT);
  private readonly network = inject(NETWORK_STATUS);
  private pushInProgress = false;
  private pullInProgress = false;
  private recoveryInProgress = false;

  async recoverAfterReload(): Promise<RecoveryOutcome> {
    if (this.recoveryInProgress) {
      return { status: 'already-running' };
    }

    this.recoveryInProgress = true;
    let pushed = 0;
    let pulled = 0;
    try {
      while (true) {
        const outcome = await this.pushOnePendingOperation();
        if (outcome.status === 'accepted') {
          pushed += 1;
          continue;
        }
        if (outcome.status === 'empty') {
          break;
        }
        if (outcome.status === 'conflict') {
          continue;
        }
        if (outcome.status === 'offline') {
          return { status: 'offline', pushed, pulled };
        }
        return { status: 'failed', pushed, pulled, error: outcome.error };
      }

      while (true) {
        const outcome = await this.pullOnePage();
        if (outcome.status === 'applied') {
          pulled += 1;
          if (outcome.page.hasMore) {
            continue;
          }
          return { status: 'completed', pushed, pulled };
        }
        if (outcome.status === 'offline') {
          return { status: 'offline', pushed, pulled };
        }
        if (outcome.status === 'empty') {
          return { status: 'completed', pushed, pulled };
        }
        return { status: 'failed', pushed, pulled, error: outcome.error };
      }
    } catch (error) {
      return { status: 'failed', pushed, pulled, error };
    } finally {
      this.recoveryInProgress = false;
    }
  }

  async pushOnePendingOperation(): Promise<PushOutcome> {
    if (this.pushInProgress) {
      return { status: 'empty' };
    }
    if (!this.network.isOnline()) {
      return { status: 'offline' };
    }

    this.pushInProgress = true;
    try {
      const operation = await this.persistence.firstPendingOperation();
      if (!operation) {
        return { status: 'empty' };
      }

      try {
        const result = await this.transport.submitOperation(operation);
        await this.persistence.commitAcceptedResult(operation, result);
        return { status: 'accepted', operation, result };
      } catch (error) {
        if (
          error instanceof SynchronizationProtocolError &&
          error.body.code === 'REVISION_CONFLICT'
        ) {
          await this.persistence.commitRevisionConflict(operation, error.body);
          return { status: 'conflict', operation, conflict: error.body };
        }
        return { status: 'failed', operation, error };
      }
    } finally {
      this.pushInProgress = false;
    }
  }

  async pullOnePage(): Promise<PullOutcome> {
    if (this.pullInProgress) {
      return { status: 'empty' };
    }
    if (!this.network.isOnline()) {
      return { status: 'offline' };
    }

    this.pullInProgress = true;
    try {
      const cursor = await this.persistence.synchronizationCursor();
      try {
        const page = await this.transport.pullChanges(cursor);
        await this.persistence.commitPulledPage(page);
        return { status: 'applied', page };
      } catch (error) {
        return { status: 'failed', error };
      }
    } finally {
      this.pullInProgress = false;
    }
  }
}
