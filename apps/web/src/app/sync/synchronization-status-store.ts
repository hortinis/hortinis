import type { RejectionSummary } from '../persistence/local-rejected-operation';
import { signal } from '@angular/core';
import type { SynchronizationStatus, PushOutcome, PullOutcome } from './synchronization-outcomes';

type RunningStatus = Extract<
  SynchronizationStatus,
  { status: 'synchronizing' | 'manual-recovery' }
>;
export type SettledSynchronizationStatus = Exclude<
  SynchronizationStatus,
  RunningStatus | { status: 'idle' }
>;

export class SynchronizationStatusStore {
  readonly rejections = signal<RejectionSummary>({ status: 'clear', count: 0 });
  readonly status = signal<SynchronizationStatus>({ status: 'idle' });

  startRecovery(manual: boolean): void {
    if (this.isRunning()) throw new Error('Synchronization recovery is already running.');
    this.status.set({ status: manual ? 'manual-recovery' : 'synchronizing' });
  }

  settle(status: SettledSynchronizationStatus): void {
    if (status.status === 'completed' && !this.isRunning()) {
      throw new Error('Only an active recovery can publish aggregate completion.');
    }
    this.status.set(status);
  }

  standalone(outcome: PushOutcome | PullOutcome): void {
    if (this.isRunning()) throw new Error('A standalone exchange cannot publish during recovery.');
    switch (outcome.status) {
      case 'offline':
      case 'ownership-lost':
        this.settle({ status: outcome.status });
        break;
      case 'failed':
        this.settle({ status: 'failed', reason: outcome.reason });
        break;
      case 'accepted':
        this.status.set({ status: 'completed', pushed: 1, pulled: 0 });
        break;
      case 'applied':
      case 'rejected':
      case 'conflict':
      case 'empty':
      case 'busy':
        break;
    }
  }

  private isRunning(): boolean {
    const status = this.status().status;
    return status === 'synchronizing' || status === 'manual-recovery';
  }
}
