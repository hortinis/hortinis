import { Inject, Injectable, InjectionToken } from '@angular/core';
import Dexie, { Table } from 'dexie';
import {
  HORTINIS_DATABASE_SCHEMA,
  HORTINIS_DATABASE_SCHEMA_V1,
  HORTINIS_DATABASE_SCHEMA_V3,
} from './database-schema';
import type { LocalTechnicalRecord } from './local-technical-record';
import type {
  OperationResult,
  RevisionConflictError,
  TechnicalTombstone,
} from '../sync/conformance';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';
import type { LocalSynchronizationState } from './local-synchronization-state';
import type { LocalDeletionConflict } from './local-deletion-conflict';
import type { LocalSynchronizationRetryState } from './local-synchronization-retry-state';

export const HORTINIS_DATABASE_NAME = new InjectionToken<string>('Hortinis database name', {
  providedIn: 'root',
  factory: () => 'hortinis',
});

@Injectable({ providedIn: 'root' })
export class HortinisDatabase extends Dexie {
  readonly technicalRecords!: Table<LocalTechnicalRecord, string>;
  readonly technicalTombstones!: Table<TechnicalTombstone, string>;
  readonly pendingDeletionRecords!: Table<LocalTechnicalRecord, string>;
  readonly deletionConflicts!: Table<LocalDeletionConflict, string>;
  readonly outboxOperations!: Table<LocalTechnicalRecordOperation, string>;
  readonly acceptedOperationResults!: Table<OperationResult, string>;
  readonly revisionConflicts!: Table<RevisionConflictError, string>;
  readonly synchronizationState!: Table<LocalSynchronizationState, string>;
  readonly synchronizationRetryState!: Table<LocalSynchronizationRetryState, string>;

  // The constructor must pass the injected name to Dexie before the database is initialized.
  // eslint-disable-next-line @angular-eslint/prefer-inject
  constructor(@Inject(HORTINIS_DATABASE_NAME) databaseName: string) {
    super(databaseName);

    this.version(1).stores(HORTINIS_DATABASE_SCHEMA_V1);
    this.version(2).stores(HORTINIS_DATABASE_SCHEMA);
    this.version(3).stores(HORTINIS_DATABASE_SCHEMA_V3);
  }
}
