import type { NetworkStatus } from './network-status';
import type { SynchronizationClock, SynchronizationScheduler } from './synchronization-runtime';

export class SynchronizationTriggers {
  private disposed = false;
  get active(): boolean {
    return !this.disposed;
  }

  private cancelRetry?: () => void;
  private cancelOnline?: () => void;
  private cancelCoordination?: () => void;

  constructor(
    private readonly scheduler: SynchronizationScheduler,
    private readonly network: NetworkStatus,
    private readonly clock: SynchronizationClock,
    private readonly recover: () => void,
  ) {}

  retryAt(time: number): void {
    if (this.disposed) return;
    this.clearRetry();
    this.cancelRetry = this.scheduler.schedule(
      () => {
        this.cancelRetry = undefined;
        if (!this.disposed) this.recover();
      },
      Math.max(0, time - this.clock.now()),
    );
  }

  coordinationAt(time: number): void {
    if (this.disposed || this.cancelCoordination) return;
    this.cancelCoordination = this.scheduler.schedule(
      () => {
        this.cancelCoordination = undefined;
        if (!this.disposed) this.recover();
      },
      Math.max(0, time - this.clock.now()),
    );
  }

  waitForOnline(): void {
    if (this.disposed || this.cancelOnline || !this.network.onOnline) return;
    this.cancelOnline = this.network.onOnline(() => {
      this.cancelOnline = undefined;
      if (!this.disposed) this.recover();
    });
  }

  clearRetry(): void {
    this.cancelRetry?.();
    this.cancelRetry = undefined;
  }
  clearCoordination(): void {
    this.cancelCoordination?.();
    this.cancelCoordination = undefined;
  }

  dispose(): void {
    this.disposed = true;
    this.clearRetry();
    this.clearCoordination();
    this.cancelOnline?.();
    this.cancelOnline = undefined;
  }
}
