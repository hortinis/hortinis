import { inject, Injectable } from '@angular/core';
import { HortinisDatabase } from './hortinis-database';
import { MAXIMUM_SYNCHRONIZATION_ATTEMPTS } from './local-synchronization-retry-state';
import type {
  LocalSynchronizationRetryState,
  SynchronizationRetryPhase,
} from './local-synchronization-retry-state';
import {
  assertSynchronizationOwnership,
  type SynchronizationOwnership,
} from './synchronization-ownership';

export class SynchronizationRetryBlockedError extends Error {
  constructor(readonly state: LocalSynchronizationRetryState) {
    super('Synchronization retry is not eligible.');
  }
}

@Injectable({ providedIn: 'root' })
export class SynchronizationRetryPersistence {
  private readonly database = inject(HortinisDatabase);

  async current(): Promise<LocalSynchronizationRetryState | undefined> {
    return this.database.synchronizationRetryState.orderBy('workId').first();
  }

  async get(workId: string): Promise<LocalSynchronizationRetryState | undefined> {
    return this.database.synchronizationRetryState.get(workId);
  }

  async reserveAttempt(
    workId: string,
    phase: SynchronizationRetryPhase,
    operationId: string | undefined,
    ownership: SynchronizationOwnership,
    timeoutMilliseconds: number,
  ): Promise<void> {
    const blocked = await this.database.transaction(
      'rw',
      this.database.synchronizationRetryState,
      this.database.synchronizationLeases,
      () =>
        this.reserveAttemptInTransaction(
          workId,
          phase,
          operationId,
          ownership,
          timeoutMilliseconds,
        ),
    );
    if (blocked) throw new SynchronizationRetryBlockedError(blocked);
  }

  // The caller includes retry state and leases in its transaction, allowing outbox preparation to be atomic.
  async reserveAttemptInTransaction(
    workId: string,
    phase: SynchronizationRetryPhase,
    operationId: string | undefined,
    ownership: SynchronizationOwnership,
    timeoutMilliseconds: number,
  ): Promise<LocalSynchronizationRetryState | undefined> {
    await assertSynchronizationOwnership(this.database, ownership);
    const current = await this.get(workId);
    if (
      current &&
      (current.exhausted || current.attemptCount >= MAXIMUM_SYNCHRONIZATION_ATTEMPTS)
    ) {
      const exhausted = { ...current, exhausted: true, nextEligibleAt: null };
      delete exhausted.inFlight;
      await this.database.synchronizationRetryState.put(exhausted);
      return exhausted;
    }
    if (
      current?.nextEligibleAt !== null &&
      current?.nextEligibleAt !== undefined &&
      current.nextEligibleAt > ownership.now()
    ) {
      return current;
    }
    await this.database.synchronizationRetryState.put({
      workId,
      scope: 'technical-records',
      phase,
      ...(operationId ? { operationId } : {}),
      attemptCount: (current?.attemptCount ?? 0) + 1,
      nextEligibleAt: ownership.now() + timeoutMilliseconds,
      failureCategory: current?.failureCategory ?? 'unavailable',
      exhausted: false,
      inFlight: true,
    });
    return undefined;
  }

  async put(
    state: LocalSynchronizationRetryState,
    ownership?: SynchronizationOwnership,
  ): Promise<void> {
    await this.database.transaction(
      'rw',
      this.database.synchronizationRetryState,
      this.database.synchronizationLeases,
      async () => {
        await assertSynchronizationOwnership(this.database, ownership);
        await this.database.synchronizationRetryState.put(state);
      },
    );
  }

  async delete(workId: string, ownership?: SynchronizationOwnership): Promise<void> {
    await this.database.transaction(
      'rw',
      this.database.synchronizationRetryState,
      this.database.synchronizationLeases,
      async () => {
        await assertSynchronizationOwnership(this.database, ownership);
        await this.database.synchronizationRetryState.delete(workId);
      },
    );
  }
}
