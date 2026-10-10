import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { StoragePersistence } from './storage-persistence';

type OutboxObservation =
  { status: 'loading' | 'unavailable'; count: null } | { status: 'available'; count: number };

@Injectable({ providedIn: 'root' })
export class StorageSafetyService {
  readonly persistenceStatus = inject(StoragePersistence).status;
  private readonly observation = signal<OutboxObservation>({ status: 'loading', count: null });
  readonly outbox = this.observation.asReadonly();
  readonly pendingStorageWarning = computed(
    () => this.persistenceStatus() !== 'persistent' && (this.outbox().count ?? 0) > 0,
  );

  constructor() {
    const stopObserving = inject(TechnicalRecordPersistence).observeOutboxCount((count) => {
      this.observation.set(
        count === null ? { status: 'unavailable', count } : { status: 'available', count },
      );
    });
    inject(DestroyRef).onDestroy(stopObserving);
  }
}
