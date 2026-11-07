import { Inject, Injectable, InjectionToken } from '@angular/core';
import Dexie, { Table } from 'dexie';
import { HORTINIS_DATABASE_SCHEMA } from './database-schema';
import type { GardenRecord } from './features/garden-record';
import type { ConfigurationRecord } from './configuration/configuration-record';

export const HORTINIS_DATABASE_NAME = new InjectionToken<string>('Hortinis database name', {
  providedIn: 'root',
  factory: () => 'hortinis',
});

export type HortinisTables = keyof HortinisDatabase;

@Injectable({ providedIn: 'root' })
export class HortinisDatabase extends Dexie {
  readonly garden!: Table<GardenRecord, string>;
  readonly configuration!: Table<ConfigurationRecord, string>;

  // The constructor must pass the injected name to Dexie before the database is initialized.
  // eslint-disable-next-line @angular-eslint/prefer-inject
  constructor(@Inject(HORTINIS_DATABASE_NAME) databaseName: string) {
    super(databaseName);

    this.version(1).stores(HORTINIS_DATABASE_SCHEMA);
    // Preserve local application data while dropping obsolete synchronization stores.
    this.version(2).stores({
      ...HORTINIS_DATABASE_SCHEMA,
      technicalRecords: null,
      outboxOperations: null,
      acceptedOperationResults: null,
      revisionConflicts: null,
      synchronizationState: null,
    });
  }
}
