import { Inject, Injectable, InjectionToken } from '@angular/core';
import Dexie, { Table } from 'dexie';
import {
  HORTINIS_DATABASE_SCHEMA,
  HORTINIS_DATABASE_SCHEMA_V1,
  HORTINIS_DATABASE_SCHEMA_V3,
  HORTINIS_DATABASE_SCHEMA_V4,
  HORTINIS_DATABASE_SCHEMA_V5,
  HORTINIS_DATABASE_SCHEMA_V6,
} from './database-schema';
import type { LocalRejectedOperation } from './local-rejected-operation';
import type { LocalTechnicalRecord } from './local-technical-record';
import type {
  TechnicalRecord,
  OperationResult,
  RevisionConflictError,
  TechnicalTombstone,
} from '../sync/conformance';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';
import type { LocalSynchronizationState } from './local-synchronization-state';
import type { LocalDeletionConflict } from './local-deletion-conflict';
import type { LocalSynchronizationRetryState } from './local-synchronization-retry-state';
import type { LocalSynchronizationLease } from './local-synchronization-lease';

export const HORTINIS_DATABASE_NAME = new InjectionToken<string>('Hortinis database name', {
  providedIn: 'root',
  factory: () => 'hortinis',
});

@Injectable({ providedIn: 'root' })
export class HortinisDatabase extends Dexie {
  readonly rejectedOperations!: Table<LocalRejectedOperation, string>;
  readonly acceptedTechnicalRecords!: Table<TechnicalRecord, string>;
  readonly technicalRecords!: Table<LocalTechnicalRecord, string>;
  readonly technicalTombstones!: Table<TechnicalTombstone, string>;
  readonly pendingDeletionRecords!: Table<LocalTechnicalRecord, string>;
  readonly deletionConflicts!: Table<LocalDeletionConflict, string>;
  readonly outboxOperations!: Table<LocalTechnicalRecordOperation, string>;
  readonly acceptedOperationResults!: Table<OperationResult, string>;
  readonly revisionConflicts!: Table<RevisionConflictError, string>;
  readonly synchronizationState!: Table<LocalSynchronizationState, string>;
  readonly synchronizationRetryState!: Table<LocalSynchronizationRetryState, string>;
  readonly synchronizationLeases!: Table<LocalSynchronizationLease, string>;

  // The constructor must pass the injected name to Dexie before the database is initialized.
  // eslint-disable-next-line @angular-eslint/prefer-inject
  constructor(@Inject(HORTINIS_DATABASE_NAME) databaseName: string) {
    super(databaseName);

    this.version(1).stores(HORTINIS_DATABASE_SCHEMA_V1);
    this.version(2).stores(HORTINIS_DATABASE_SCHEMA);
    this.version(3).stores(HORTINIS_DATABASE_SCHEMA_V3);
    this.version(4).stores(HORTINIS_DATABASE_SCHEMA_V4);
    this.version(5)
      .stores(HORTINIS_DATABASE_SCHEMA_V5)
      .upgrade(async (transaction) => {
        // Legacy projections may contain a local value labelled with a newer server revision.
        // Preserve them and their normal cursor until a complete-history repair finishes.
        const state = await transaction.table('synchronizationState').get('technical-records');
        const acceptedCount = await transaction.table('acceptedOperationResults').count();
        const acceptedProjectionCount = await transaction
          .table('technicalRecords')
          .filter((record) => record.lastAcceptedRevision !== null)
          .count();
        const deletionCount = await transaction.table('pendingDeletionRecords').count();
        if (state || acceptedCount || acceptedProjectionCount || deletionCount) {
          await transaction
            .table('synchronizationState')
            .put({ scope: 'technical-records', ...state, repairRequired: true });
        }
      });
    this.version(6)
      .stores(HORTINIS_DATABASE_SCHEMA_V6)
      .upgrade(async (transaction) => {
        await transaction
          .table('outboxOperations')
          .toCollection()
          .modify((operation: LocalTechnicalRecordOperation) => {
            if (operation.kind === 'create' || operation.expectedRevision !== null) {
              operation.legacySubmissionUnknown = true;
            }
          });
      });
  }
}
