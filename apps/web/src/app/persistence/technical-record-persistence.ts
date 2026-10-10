import { inject, Injectable } from '@angular/core';
import { liveQuery } from 'dexie';
import type {
  CreateTechnicalRecordOperation,
  TechnicalRecordOperation,
  OperationResult,
  RevisionConflictError,
  RecordNotFoundError,
  RecordIdentifierRetiredError,
  ChangePage,
} from '../sync/conformance';
import { HortinisDatabase } from './hortinis-database';
import { SynchronizationRetryPersistence } from './synchronization-retry-persistence';
import type { SynchronizationOwnership } from './synchronization-ownership';
import type { OperationRejectionCategory, RejectionSummary } from './local-rejected-operation';
import { TechnicalRecordTransactions } from './technical-record-transactions';
import { TechnicalRecordLocalCommits } from './technical-record-local-commits';
import { TechnicalRecordOutbox } from './technical-record-outbox';
import { TechnicalRecordProjection } from './technical-record-projection';
import { TechnicalRecordAcceptedResults } from './technical-record-accepted-results';
import { TechnicalRecordPulledPages } from './technical-record-pulled-pages';
import { TechnicalRecordConflicts } from './technical-record-conflicts';
import { TechnicalRecordRejections } from './technical-record-rejections';

@Injectable({ providedIn: 'root' })
export class TechnicalRecordPersistence {
  private readonly database = inject(HortinisDatabase);
  private readonly transactions = new TechnicalRecordTransactions(this.database);
  private readonly projection = new TechnicalRecordProjection(this.database);
  private readonly local = new TechnicalRecordLocalCommits(this.database, this.transactions);
  private readonly outbox = new TechnicalRecordOutbox(
    this.database,
    this.transactions,
    inject(SynchronizationRetryPersistence),
  );
  private readonly accepted = new TechnicalRecordAcceptedResults(
    this.database,
    this.projection,
    this.transactions,
  );
  private readonly pulled = new TechnicalRecordPulledPages(
    this.database,
    this.projection,
    this.transactions,
  );
  private readonly conflicts = new TechnicalRecordConflicts(this.database, this.transactions);
  private readonly rejections = new TechnicalRecordRejections(this.database, this.transactions);

  commitCreate(operation: CreateTechnicalRecordOperation) {
    return this.local.commitCreate(operation);
  }
  commitReplace(operationId: string, recordId: string, value: string) {
    return this.local.commitReplace(operationId, recordId, value);
  }
  commitDelete(operationId: string, recordId: string) {
    return this.local.commitDelete(operationId, recordId);
  }
  observeOutboxCount(publish: (count: number | null) => void): () => void {
    const subscription = liveQuery(async () => {
      try {
        return await this.database.outboxOperations.count();
      } catch {
        // Return a value before Dexie suppresses aborted reads so observation can recover.
        return null;
      }
    }).subscribe({
      next: publish,
      error: () => publish(null),
    });
    return () => subscription.unsubscribe();
  }
  firstPendingOperation() {
    return this.outbox.firstPendingOperation();
  }
  preparePendingOperation(ownership: SynchronizationOwnership, timeoutMilliseconds: number) {
    return this.outbox.preparePendingOperation(ownership, timeoutMilliseconds);
  }
  commitAcceptedResult(
    operation: TechnicalRecordOperation,
    result: OperationResult,
    ownership?: SynchronizationOwnership,
  ) {
    return this.accepted.commitAcceptedResult(operation, result, ownership);
  }
  commitRevisionConflict(
    operation: TechnicalRecordOperation,
    conflict: RevisionConflictError,
    ownership?: SynchronizationOwnership,
  ) {
    return this.conflicts.commitRevisionConflict(operation, conflict, ownership);
  }
  commitDeletionConflict(
    operation: TechnicalRecordOperation,
    error: RecordNotFoundError | RecordIdentifierRetiredError,
    ownership?: SynchronizationOwnership,
  ) {
    return this.conflicts.commitDeletionConflict(operation, error, ownership);
  }
  commitPulledPage(
    page: ChangePage,
    boundary?: { expectedCursor: string | undefined; repair?: boolean },
    ownership?: SynchronizationOwnership,
  ) {
    return this.pulled.commitPulledPage(page, boundary, ownership);
  }
  synchronizationCursor() {
    return this.pulled.synchronizationCursor();
  }
  pullBoundary() {
    return this.pulled.pullBoundary();
  }
  commitRejectedOperation(
    operation: TechnicalRecordOperation,
    category: OperationRejectionCategory,
    ownership?: SynchronizationOwnership,
  ) {
    return this.rejections.commitRejectedOperation(operation, category, ownership);
  }
  observeRejections(publish: (summary: RejectionSummary) => void) {
    return this.rejections.observe(publish);
  }
  rejectionSummary() {
    return this.rejections.summary();
  }
}
