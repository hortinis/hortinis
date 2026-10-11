import { Inject, Injectable, InjectionToken } from '@angular/core';
import Dexie, { Table } from 'dexie';
import { HORTINIS_DATABASE_SCHEMA_V8 } from './database-schema';
import { generateUuidV7 } from '../sync/uuid-v7';
import {
  parseAccessContract,
  type LocalDataSetId,
  type LocalServerBinding,
  type LocalExchangeState,
} from '../sync/access-contracts.rule';
import {
  FreshLocalDatabaseRequiredError,
  LocalDataSetUnavailableError,
  LOCAL_DATA_SET_KEY,
  readLocalDataSetIdentity,
  type LocalDataSetIdentity,
} from './local-data-set-identity';
import {
  readLocalExchangeState,
  type OwnedLocalExchangeState,
  type LocalInitialAdmission,
} from './local-exchange-metadata';
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
  readonly localDataSetIdentity!: Table<LocalDataSetIdentity, string>;
  readonly localServerBindings!: Table<LocalServerBinding, LocalDataSetId>;
  readonly localExchangeState!: Table<OwnedLocalExchangeState, LocalDataSetId>;
  readonly localInitialAdmissions!: Table<LocalInitialAdmission, LocalDataSetId>;
  private openedDataSetId: LocalDataSetId | undefined;
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

    this.version(8)
      .stores(HORTINIS_DATABASE_SCHEMA_V8)
      .upgrade(() => {
        throw new FreshLocalDatabaseRequiredError();
      });
    // Keep initial generation synchronous and let Dexie track the write in its creation transaction.
    this.on('populate', () => {
      const localDataSetId = parseAccessContract('LocalDataSetId', generateUuidV7());
      return Dexie.Promise.all([
        this.localDataSetIdentity.add({
          key: LOCAL_DATA_SET_KEY,
          version: '1',
          localDataSetId,
        }),
        this.localExchangeState.add({
          localDataSetId,
          value: { state: 'offline', exchangeFence: '1', logoutPending: false },
        }),
      ]);
    });
    this.on.ready.subscribe(async () => {
      this.openedDataSetId = await this.requireLocalDataSetId();
      await this.requireLocalExchangeState(this.openedDataSetId);
    }, true);
  }

  // Also read inside each workflow transaction to fence stale handles after reset or identity change.
  async requireLocalDataSetId(): Promise<LocalDataSetId> {
    const identity = readLocalDataSetIdentity(await this.localDataSetIdentity.toArray());
    if (this.openedDataSetId !== undefined && this.openedDataSetId !== identity) {
      throw new LocalDataSetUnavailableError();
    }
    return identity;
  }

  requireLocalExchangeState(owner: LocalDataSetId): Promise<LocalExchangeState> {
    return this.localExchangeState.toArray().then((rows) => readLocalExchangeState(rows, owner));
  }
}
