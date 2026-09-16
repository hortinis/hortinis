import { inject, Injectable } from '@angular/core';
import type {
  CreateTechnicalRecordOperation,
  OperationResult,
  TechnicalRecordOperation,
} from '../sync/conformance';
import { HortinisDatabase } from './hortinis-database';
import type { LocalTechnicalRecord } from './local-technical-record';

@Injectable({ providedIn: 'root' })
export class TechnicalRecordPersistence {
  private readonly database: HortinisDatabase;

  // The optional argument provides a test seam while production uses Angular's inject() function.
  // eslint-disable-next-line @angular-eslint/prefer-inject
  constructor(database?: HortinisDatabase) {
    this.database = database ?? inject(HortinisDatabase);
  }

  async commitCreate(operation: CreateTechnicalRecordOperation): Promise<LocalTechnicalRecord> {
    const localRecord: LocalTechnicalRecord = {
      recordId: operation.recordId,
      value: operation.value,
      lastAcceptedRevision: null,
    };

    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
      this.database.outboxOperations,
      async () => {
        await this.database.technicalRecords.add(localRecord);
        await this.database.outboxOperations.add(operation);
      },
    );

    return localRecord;
  }

  async firstPendingOperation(): Promise<TechnicalRecordOperation | undefined> {
    return this.database.outboxOperations.orderBy('operationId').first();
  }

  async commitAcceptedResult(
    operation: TechnicalRecordOperation,
    result: OperationResult,
  ): Promise<void> {
    if (
      result.operationId !== operation.operationId ||
      result.record.recordId !== operation.recordId
    ) {
      throw new Error('The accepted result does not correspond to the submitted operation.');
    }

    await this.database.transaction(
      'rw',
      this.database.technicalRecords,
      this.database.outboxOperations,
      this.database.acceptedOperationResults,
      async () => {
        await this.database.acceptedOperationResults.add(result);
        const localRecord = await this.database.technicalRecords.get(operation.recordId);
        if (!localRecord) {
          throw new Error('The local record for the accepted operation is missing.');
        }
        await this.database.technicalRecords.put({
          ...localRecord,
          value: result.record.value,
          lastAcceptedRevision: result.record.revision,
        });
        await this.database.outboxOperations.delete(operation.operationId);
      },
    );
  }
}
