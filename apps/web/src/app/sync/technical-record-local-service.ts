import { inject, Injectable } from '@angular/core';
import type { CreateTechnicalRecordOperation } from './conformance';
import { generateUuidV7 } from './uuid-v7';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import type { LocalTechnicalRecord } from '../persistence/local-technical-record';
import type { LocalTechnicalRecordOperation } from '../persistence/local-technical-record-operation';
import { TechnicalRecordSynchronizationService } from './technical-record-synchronization-service';

export interface LocalTechnicalRecordCreate {
  record: LocalTechnicalRecord;
  operation: CreateTechnicalRecordOperation;
}

export interface LocalTechnicalRecordReplace {
  record: LocalTechnicalRecord;
  operation: LocalTechnicalRecordOperation;
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
    void this.synchronization.startBackgroundRecovery();
    return { record, operation };
  }

  async replace(recordId: string, value: string): Promise<LocalTechnicalRecordReplace> {
    const result = await this.persistence.commitReplace(generateUuidV7(), recordId, value);
    void this.synchronization.startBackgroundRecovery();
    return result;
  }

  async delete(recordId: string): Promise<LocalTechnicalRecordOperation> {
    const operation = await this.persistence.commitDelete(generateUuidV7(), recordId);
    void this.synchronization.startBackgroundRecovery();
    return operation;
  }
}
