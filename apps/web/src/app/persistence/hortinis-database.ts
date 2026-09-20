import { Inject, Injectable, InjectionToken } from '@angular/core';
import Dexie, { Table } from 'dexie';
import { HORTINIS_DATABASE_SCHEMA } from './database-schema';
import type { LocalTechnicalRecord } from './local-technical-record';
import type { OperationResult, RevisionConflictError } from '../sync/conformance';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';
import type { LocalSynchronizationState } from './local-synchronization-state';
import type { GardenRecord } from './features/garden-record';
import type { SpaceRecord } from './features/space-record';
import type { ConfigurationRecord } from './features/configuration-record';

export const HORTINIS_DATABASE_NAME = new InjectionToken<string>('Hortinis database name', {
  providedIn: 'root',
  factory: () => 'hortinis',
});

@Injectable({ providedIn: 'root' })
export class HortinisDatabase extends Dexie {
  readonly technicalRecords!: Table<LocalTechnicalRecord, string>;
  readonly outboxOperations!: Table<LocalTechnicalRecordOperation, string>;
  readonly acceptedOperationResults!: Table<OperationResult, string>;
  readonly revisionConflicts!: Table<RevisionConflictError, string>;
  readonly synchronizationState!: Table<LocalSynchronizationState, string>;
  readonly gardens!: Table<GardenRecord, string>;
  readonly spaces!: Table<SpaceRecord, string>;
  readonly configuration!: Table<ConfigurationRecord, string>;

  // The constructor must pass the injected name to Dexie before the database is initialized.
  // eslint-disable-next-line @angular-eslint/prefer-inject
  constructor(@Inject(HORTINIS_DATABASE_NAME) databaseName: string) {
    super(databaseName);

    this.version(1).stores(HORTINIS_DATABASE_SCHEMA);
  }
}
