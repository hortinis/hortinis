import { inject, Injectable } from '@angular/core';
import { HortinisDatabase } from './hortinis-database';
import type { LocalSynchronizationRetryState } from './local-synchronization-retry-state';

@Injectable({ providedIn: 'root' })
export class SynchronizationRetryPersistence {
  private readonly database = inject(HortinisDatabase);

  async current(): Promise<LocalSynchronizationRetryState | undefined> {
    return this.database.synchronizationRetryState.orderBy('workId').first();
  }

  async get(workId: string): Promise<LocalSynchronizationRetryState | undefined> {
    return this.database.synchronizationRetryState.get(workId);
  }

  async put(state: LocalSynchronizationRetryState): Promise<void> {
    await this.database.synchronizationRetryState.put(state);
  }

  async delete(workId: string): Promise<void> {
    await this.database.synchronizationRetryState.delete(workId);
  }
}
