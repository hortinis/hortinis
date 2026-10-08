import type { SynchronizationOwnership } from '../persistence/synchronization-ownership';
import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import type { SynchronizationCoordinator } from './synchronization-coordinator';
import type { SynchronizationClock } from './synchronization-runtime';
import type { SynchronizationTriggers } from './synchronization-triggers';
import type { SynchronizationStatusStore } from './synchronization-status-store';
import { SynchronizationPersistenceError } from './synchronization-failure';
import type { PushOutcome, PullOutcome, RecoveryOutcome } from './synchronization-outcomes';

export class SynchronizationExecution {
  private flight?: {
    completion: Promise<void>;
    phase: 'preparing' | 'running' | 'releasing';
    kind: 'recovery' | 'standalone';
  };
  get active(): boolean {
    return this.flight !== undefined;
  }

  constructor(
    private readonly coordinator: SynchronizationCoordinator,
    private readonly clock: SynchronizationClock,
    private readonly triggers: SynchronizationTriggers,
    private readonly status: SynchronizationStatusStore,
  ) {}

  async run<Outcome extends PushOutcome | PullOutcome | RecoveryOutcome>(
    work: (ownership: SynchronizationOwnership, started: () => void) => Promise<Outcome>,
    busy: Outcome,
    scheduleWhenBusy: boolean,
    released: (completed: boolean) => void,
    kind: 'recovery' | 'standalone' = 'recovery',
  ): Promise<Outcome> {
    while (this.flight) {
      if (
        this.flight.phase === 'running' &&
        (kind === 'standalone' || this.flight.kind === 'recovery')
      )
        return busy;
      await this.flight.completion;
    }
    if (!this.triggers.active) return busy;
    let complete!: () => void;
    const flight = {
      completion: new Promise<void>((resolve) => {
        complete = resolve;
      }),
      phase: 'preparing' as 'preparing' | 'running' | 'releasing',
      kind,
    };
    this.flight = flight;
    let completed = false;
    try {
      const attempt = await this.coordinator.tryAcquire();
      if (!attempt.acquired) {
        if (scheduleWhenBusy) this.triggers.coordinationAt(attempt.retryAt);
        return busy;
      }
      this.triggers.clearCoordination();
      let lost = false;
      const ownership: SynchronizationOwnership = {
        lease: attempt.lease,
        now: () => this.clock.now(),
        isLost: () => lost,
      };
      const stop = this.coordinator.keepAlive(attempt.lease, () => {
        lost = true;
        this.triggers.clearRetry();
      });
      try {
        if (!this.triggers.active) return busy;
        const outcome = await work(ownership, () => {
          flight.phase = 'running';
        });
        completed =
          outcome.status === 'completed' ||
          outcome.status === 'accepted' ||
          outcome.status === 'applied';
        return outcome;
      } finally {
        flight.phase = 'releasing';
        stop();
        await this.coordinator.release(attempt.lease);
      }
    } catch (error) {
      if (!(error instanceof SynchronizationOwnershipLostError)) {
        throw error instanceof SynchronizationPersistenceError ? error.cause : error;
      }
      this.triggers.clearRetry();
      this.status.settle({ status: 'ownership-lost' });
      return { status: 'ownership-lost' } as Outcome;
    } finally {
      this.flight = undefined;
      complete();
      released(completed);
    }
  }
}
