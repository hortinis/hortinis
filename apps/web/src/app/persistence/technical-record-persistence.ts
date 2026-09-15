import { inject, Injectable } from '@angular/core';
import type { CreateTechnicalRecordOperation } from '../sync/conformance';
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
}
