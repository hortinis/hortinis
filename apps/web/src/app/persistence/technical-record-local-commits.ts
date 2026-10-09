import type { CreateTechnicalRecordOperation } from '../sync/conformance';
import { assertTechnicalRecordValue } from '../sync/technical-record-value';
import { outboxTail, outboxMutation } from '../sync/technical-record-outbox.rule';
import type { HortinisDatabase } from './hortinis-database';
import type { LocalTechnicalRecord } from './local-technical-record';
import type { LocalTechnicalRecordOperation } from './local-technical-record-operation';
import type { TechnicalRecordTransactions } from './technical-record-transactions';

export interface CancelledLocalCreate {
  status: 'cancelled';
  recordId: string;
}
export type LocalDeleteCommit = LocalTechnicalRecordOperation | CancelledLocalCreate;

export class TechnicalRecordLocalCommits {
  constructor(
    private readonly database: HortinisDatabase,
    private readonly transactions: TechnicalRecordTransactions,
  ) {}

  async commitCreate(operation: CreateTechnicalRecordOperation): Promise<LocalTechnicalRecord> {
    assertTechnicalRecordValue(operation.value);
    const record: LocalTechnicalRecord = {
      recordId: operation.recordId,
      value: operation.value,
      lastAcceptedRevision: null,
    };
    return this.transactions.run(
      [
        this.database.technicalRecords,
        this.database.technicalTombstones,
        this.database.pendingDeletionRecords,
        this.database.outboxOperations,
        this.database.rejectedOperations,
      ],
      async () => {
        if (
          (await this.database.technicalTombstones.get(operation.recordId)) ||
          (await this.database.pendingDeletionRecords.get(operation.recordId)) ||
          (await this.database.rejectedOperations
            .where('recordId')
            .equals(operation.recordId)
            .count())
        ) {
          throw new Error('The local record identifier has been retired.');
        }
        await this.database.technicalRecords.add(record);
        await this.database.outboxOperations.add(operation);
        return record;
      },
    );
  }

  async commitReplace(
    operationId: string,
    recordId: string,
    value: string,
  ): Promise<{ record: LocalTechnicalRecord; operation: LocalTechnicalRecordOperation }> {
    assertTechnicalRecordValue(value);
    return this.transactions.run(this.tables(), async () => {
      const local = await this.database.technicalRecords.get(recordId);
      if (!local) throw new Error('The local record for the replacement is missing.');
      const { tail, blocked } = await this.tail(recordId);
      const mutation = outboxMutation(tail, 'replace', blocked);
      const operation: LocalTechnicalRecordOperation =
        mutation === 'coalesce' && tail
          ? this.replaceTail(tail, value)
          : { ...this.newOperation(operationId, local, tail), kind: 'replace', value };
      const record = { ...local, value };
      await this.database.technicalRecords.put(record);
      if (mutation === 'coalesce') await this.database.outboxOperations.put(operation);
      else await this.database.outboxOperations.add(operation);
      return { record, operation };
    });
  }

  async commitDelete(operationId: string, recordId: string): Promise<LocalDeleteCommit> {
    return this.transactions.run(this.tables(), async () => {
      const record = await this.database.technicalRecords.get(recordId);
      if (!record) throw new Error('The local record for deletion is missing.');
      const { tail, blocked } = await this.tail(recordId);
      const mutation = outboxMutation(tail, 'delete', blocked);
      if (mutation === 'cancel' && tail) {
        await this.database.outboxOperations.delete(tail.operationId);
        await this.database.technicalRecords.delete(recordId);
        return { status: 'cancelled', recordId };
      }
      const operation: LocalTechnicalRecordOperation =
        mutation === 'coalesce' && tail && tail.kind === 'replace'
          ? this.deleteTail(tail)
          : { ...this.newOperation(operationId, record, tail), kind: 'delete' };
      await this.database.pendingDeletionRecords.add(record);
      await this.database.technicalRecords.delete(recordId);
      if (mutation === 'coalesce') await this.database.outboxOperations.put(operation);
      else await this.database.outboxOperations.add(operation);
      return operation;
    });
  }

  private replaceTail(
    tail: LocalTechnicalRecordOperation,
    value: string,
  ): LocalTechnicalRecordOperation {
    if (tail.kind === 'delete') throw new Error('A deletion is already pending.');
    return { ...tail, value };
  }

  private deleteTail(tail: LocalTechnicalRecordOperation): LocalTechnicalRecordOperation {
    if (tail.kind !== 'replace')
      throw new Error('Only an unsent replacement can become a deletion.');
    const base = {
      operationId: tail.operationId,
      recordId: tail.recordId,
      kind: 'delete' as const,
    };
    if ('predecessorOperationId' in tail)
      return {
        ...base,
        expectedRevision: tail.expectedRevision,
        predecessorOperationId: tail.predecessorOperationId,
      };
    return { ...base, expectedRevision: tail.expectedRevision };
  }

  private newOperation(
    operationId: string,
    record: LocalTechnicalRecord,
    tail?: LocalTechnicalRecordOperation,
  ) {
    if (tail) {
      if (tail.kind === 'delete') throw new Error('A deletion is already pending.');
      return {
        operationId,
        recordId: record.recordId,
        expectedRevision: null,
        predecessorOperationId: tail.operationId,
      };
    }
    if (record.lastAcceptedRevision === null)
      throw new Error('The operation has no accepted predecessor revision.');
    return {
      operationId,
      recordId: record.recordId,
      expectedRevision: record.lastAcceptedRevision,
    };
  }

  private async tail(recordId: string) {
    const pending = await this.database.outboxOperations
      .where('recordId')
      .equals(recordId)
      .toArray();
    const rejected = await this.database.rejectedOperations
      .where('recordId')
      .equals(recordId)
      .toArray();
    const tail = outboxTail([...pending, ...rejected.map((entry) => entry.operation)]);
    const blocked =
      tail !== undefined &&
      Boolean(
        (await this.database.revisionConflicts.get(tail.operationId)) ||
        (await this.database.deletionConflicts.get(tail.operationId)) ||
        (await this.database.rejectedOperations.get(tail.operationId)),
      );
    return { tail, blocked };
  }

  private tables() {
    return [
      this.database.technicalRecords,
      this.database.pendingDeletionRecords,
      this.database.outboxOperations,
      this.database.rejectedOperations,
      this.database.revisionConflicts,
      this.database.deletionConflicts,
    ];
  }
}
