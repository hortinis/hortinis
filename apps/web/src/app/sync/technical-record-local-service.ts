import { inject, Injectable } from '@angular/core';
import type { CreateTechnicalRecordOperation } from './conformance';
import { generateUuidV7 } from './uuid-v7';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import type { LocalTechnicalRecord } from '../persistence/local-technical-record';
import { TechnicalRecordSynchronizationService } from './technical-record-synchronization-service';

export interface LocalTechnicalRecordCreate {
  record: LocalTechnicalRecord;
  operation: CreateTechnicalRecordOperation;
}

@Injectable({ providedIn: 'root' })
export class TechnicalRecordLocalService {
  private readonly persistence = inject(TechnicalRecordPersistence);
  private readonly synchronization = inject(TechnicalRecordSynchronizationService);

  async create(value: string): Promise<LocalTechnicalRecordCreate> {
    const operation: CreateTechnicalRecordOperation = {
      operationId: generateUuidV7(),
      recordId: generateUuidV7(),
      value,
      kind: 'create',
    };

    const record = await this.persistence.commitCreate(operation);
    void this.synchronization.pushOnePendingOperation();
    return { record, operation };
  }
}
