import { sameSubmittedOperation } from './technical-record-result-equality';
import { liveQuery } from 'dexie';
import type { RejectionSummary } from './local-rejected-operation';
import type { TechnicalRecordOperation } from '../sync/conformance';
import type { HortinisDatabase } from './hortinis-database';
import type { OperationRejectionCategory } from './local-rejected-operation';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';
import { pushRetryWorkId } from './local-synchronization-retry-state';
import type { SynchronizationOwnership } from './synchronization-ownership';
import type { TechnicalRecordTransactions } from './technical-record-transactions';

export class TechnicalRecordRejections {
  constructor(
    private readonly database: HortinisDatabase,
    private readonly transactions: TechnicalRecordTransactions,
  ) {}

  async commitRejectedOperation(
    operation: TechnicalRecordOperation,
    category: OperationRejectionCategory,
    ownership?: SynchronizationOwnership,
  ): Promise<void> {
    await this.transactions.run(
      [
        this.database.outboxOperations,
        this.database.rejectedOperations,
        this.database.technicalRecords,
        this.database.pendingDeletionRecords,
        this.database.synchronizationRetryState,
      ],
      async () => {
        const pending = await this.database.outboxOperations.get(operation.operationId);
        const existing = await this.database.rejectedOperations.get(operation.operationId);
        if (
          !pending &&
          existing?.category === category &&
          sameSubmittedOperation(toSubmittedTechnicalRecordOperation(existing.operation), operation)
        )
          return;
        if (
          !pending ||
          !sameSubmittedOperation(toSubmittedTechnicalRecordOperation(pending), operation)
        ) {
          throw new Error('The persisted operation does not match the rejected operation.');
        }
        const localRecord =
          (await this.database.technicalRecords.get(operation.recordId)) ??
          (await this.database.pendingDeletionRecords.get(operation.recordId));
        await this.database.rejectedOperations.add({
          localRecord,
          operationId: operation.operationId,
          recordId: operation.recordId,
          operation: pending,
          category,
        });
        await this.database.outboxOperations.delete(operation.operationId);
        await this.database.synchronizationRetryState.delete(
          pushRetryWorkId(operation.operationId),
        );
      },
      ownership,
    );
  }

  observe(publish: (summary: RejectionSummary) => void): () => void {
    const subscription = liveQuery(() => this.summary()).subscribe({
      next: publish,
      error: () => publish({ status: 'unavailable', count: null }),
    });
    return () => subscription.unsubscribe();
  }

  async summary(): Promise<{ status: 'clear' | 'rejected'; count: number }> {
    const count = await this.database.rejectedOperations.count();
    return { status: count === 0 ? 'clear' : 'rejected', count };
  }
}
