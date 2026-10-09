import { outboxTail } from '../sync/technical-record-outbox.rule';
import type { TechnicalRecordOperation } from '../sync/conformance';
import type { HortinisDatabase } from './hortinis-database';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';
import type { SynchronizationOwnership } from './synchronization-ownership';
import type { TechnicalRecordTransactions } from './technical-record-transactions';
import {
  SynchronizationRetryBlockedError,
  type SynchronizationRetryPersistence,
} from './synchronization-retry-persistence';
import { pushRetryWorkId } from './local-synchronization-retry-state';

export class TechnicalRecordOutbox {
  constructor(
    private readonly database: HortinisDatabase,
    private readonly transactions: TechnicalRecordTransactions,
    private readonly retries: SynchronizationRetryPersistence,
  ) {}

  async firstPendingOperation(): Promise<TechnicalRecordOperation | undefined> {
    return this.database.transaction(
      'r',
      [
        this.database.outboxOperations,
        this.database.acceptedOperationResults,
        this.database.revisionConflicts,
        this.database.deletionConflicts,
        this.database.rejectedOperations,
      ],
      async () => {
        const pending = await this.database.outboxOperations.orderBy('operationId').toArray();
        await this.assertChains(pending);
        for (const operation of pending) {
          const ready = await this.ready(operation);
          if (ready) return toSubmittedTechnicalRecordOperation(ready);
        }
        return undefined;
      },
    );
  }

  async preparePendingOperation(
    ownership: SynchronizationOwnership,
    timeoutMilliseconds: number,
  ): Promise<TechnicalRecordOperation | undefined> {
    const prepared = await this.transactions.run(
      [
        this.database.outboxOperations,
        this.database.acceptedOperationResults,
        this.database.revisionConflicts,
        this.database.deletionConflicts,
        this.database.rejectedOperations,
        this.database.synchronizationState,
        this.database.synchronizationRetryState,
      ],
      async () => {
        if ((await this.database.synchronizationState.get('technical-records'))?.repairRequired) {
          throw new Error('Accepted server state must be repaired before upload.');
        }
        const pending = await this.database.outboxOperations.orderBy('operationId').toArray();
        await this.assertChains(pending);
        for (const operation of pending) {
          const ready = await this.ready(operation);
          if (!ready) continue;
          const blocked = await this.retries.reserveAttemptInTransaction(
            pushRetryWorkId(ready.operationId),
            'push',
            ready.operationId,
            ownership,
            timeoutMilliseconds,
          );
          if (blocked) return { blocked };
          const marked = { ...ready, submittedAt: ready.submittedAt ?? ownership.now() };
          await this.database.outboxOperations.put(marked);
          return { operation: toSubmittedTechnicalRecordOperation(marked) };
        }
        return undefined;
      },
      ownership,
    );
    if (prepared?.blocked) throw new SynchronizationRetryBlockedError(prepared.blocked);
    return prepared?.operation;
  }

  private async assertChains(pending: LocalTechnicalRecordOperation[]): Promise<void> {
    const records = new Map<string, LocalTechnicalRecordOperation[]>();
    const rejected = await this.database.rejectedOperations.toArray();
    for (const operation of [...pending, ...rejected.map((entry) => entry.operation)]) {
      const chain = records.get(operation.recordId) ?? [];
      chain.push(operation);
      records.set(operation.recordId, chain);
    }
    for (const chain of records.values()) {
      outboxTail(chain);
      const identifiers = new Set(chain.map((operation) => operation.operationId));
      for (const operation of chain) {
        if (
          'predecessorOperationId' in operation &&
          !identifiers.has(operation.predecessorOperationId) &&
          !(await this.database.acceptedOperationResults.get(operation.predecessorOperationId))
        ) {
          throw new Error('The outbox chain has a missing predecessor.');
        }
      }
    }
  }

  private async ready(
    operation: LocalTechnicalRecordOperation,
  ): Promise<LocalTechnicalRecordOperation | undefined> {
    if (
      (await this.database.revisionConflicts.get(operation.operationId)) ||
      (await this.database.deletionConflicts.get(operation.operationId)) ||
      (await this.database.rejectedOperations.get(operation.operationId))
    )
      return undefined;
    if (!('predecessorOperationId' in operation))
      return toSubmittedTechnicalRecordOperation(operation) ? operation : undefined;
    const result = await this.database.acceptedOperationResults.get(
      operation.predecessorOperationId,
    );
    if (!result || !('record' in result)) return undefined;
    if (result.record.recordId !== operation.recordId)
      throw new Error('The predecessor receipt does not match the dependent operation.');
    if (operation.submittedAt !== undefined || operation.legacySubmissionUnknown) {
      if (operation.expectedRevision !== result.record.revision)
        throw new Error('The submitted dependency disagrees with its predecessor receipt.');
      return operation;
    }
    return { ...operation, expectedRevision: result.record.revision };
  }
}
