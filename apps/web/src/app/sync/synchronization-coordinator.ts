import { inject, Injectable, InjectionToken } from '@angular/core';
import { HortinisDatabase } from '../persistence/hortinis-database';
import type { LocalSynchronizationLease } from '../persistence/local-synchronization-lease';
import {
  BrowserSynchronizationScheduler,
  SYNCHRONIZATION_CLOCK,
  type SynchronizationScheduler,
} from './synchronization-runtime';

const SCOPE = 'technical-records' as const;
const LEASE_DURATION_MILLISECONDS = 15_000;
const LEASE_RENEWAL_MILLISECONDS = 5_000;

export const SYNCHRONIZATION_OWNER_ID = new InjectionToken<string>('Synchronization owner ID', {
  providedIn: 'root',
  factory: () => crypto.randomUUID(),
});

export const SYNCHRONIZATION_COORDINATION_SCHEDULER = new InjectionToken<SynchronizationScheduler>(
  'Synchronization coordination scheduler',
  {
    providedIn: 'root',
    factory: () => inject(BrowserSynchronizationScheduler),
  },
);

export type SynchronizationLeaseAttempt =
  { acquired: true; lease: LocalSynchronizationLease } | { acquired: false; retryAt: number };

@Injectable({ providedIn: 'root' })
export class SynchronizationCoordinator {
  private readonly database = inject(HortinisDatabase);
  private readonly ownerId = inject(SYNCHRONIZATION_OWNER_ID);
  private readonly clock = inject(SYNCHRONIZATION_CLOCK);
  private readonly scheduler = inject(SYNCHRONIZATION_COORDINATION_SCHEDULER);

  async tryAcquire(): Promise<SynchronizationLeaseAttempt> {
    return this.database.transaction('rw', this.database.synchronizationLeases, async () => {
      const existing = await this.database.synchronizationLeases.get(SCOPE);
      const now = this.clock.now();
      if (existing && existing.ownerId !== this.ownerId && existing.expiresAt > now) {
        return { acquired: false, retryAt: existing.expiresAt };
      }
      const lease: LocalSynchronizationLease = {
        scope: SCOPE,
        ownerId: this.ownerId,
        fencingToken: (existing?.fencingToken ?? 0) + 1,
        expiresAt: now + LEASE_DURATION_MILLISECONDS,
      };
      await this.database.synchronizationLeases.put(lease);
      return { acquired: true, lease };
    });
  }

  keepAlive(lease: LocalSynchronizationLease): () => void {
    let cancelled = false;
    let cancelScheduled: (() => void) | undefined;
    const schedule = () => {
      cancelScheduled = this.scheduler.schedule(() => {
        void this.renew(lease).then((renewed) => {
          if (!cancelled && renewed) schedule();
        });
      }, LEASE_RENEWAL_MILLISECONDS);
    };
    schedule();
    return () => {
      cancelled = true;
      cancelScheduled?.();
    };
  }

  async release(lease: LocalSynchronizationLease): Promise<void> {
    await this.database.transaction('rw', this.database.synchronizationLeases, async () => {
      const current = await this.database.synchronizationLeases.get(SCOPE);
      if (current?.ownerId === lease.ownerId && current.fencingToken === lease.fencingToken) {
        await this.database.synchronizationLeases.delete(SCOPE);
      }
    });
  }

  private async renew(lease: LocalSynchronizationLease): Promise<boolean> {
    return this.database.transaction('rw', this.database.synchronizationLeases, async () => {
      const current = await this.database.synchronizationLeases.get(SCOPE);
      if (current?.ownerId !== lease.ownerId || current.fencingToken !== lease.fencingToken) {
        return false;
      }
      current.expiresAt = this.clock.now() + LEASE_DURATION_MILLISECONDS;
      lease.expiresAt = current.expiresAt;
      await this.database.synchronizationLeases.put(current);
      return true;
    });
  }
}
