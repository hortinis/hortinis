import { inject, Injectable } from '@angular/core';
import type { CreateTechnicalRecordOperation } from './conformance';
import { generateUuidV7 } from './uuid-v7';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import type { LocalTechnicalRecord } from '../persistence/local-technical-record';

export interface LocalTechnicalRecordCreate {
  record: LocalTechnicalRecord;
  operation: CreateTechnicalRecordOperation;
}

@Injectable({ providedIn: 'root' })
export class TechnicalRecordLocalService {
  private readonly persistence: TechnicalRecordPersistence;

  // The optional argument provides a test seam while production uses Angular's inject() function.
  // eslint-disable-next-line @angular-eslint/prefer-inject
  constructor(persistence?: TechnicalRecordPersistence) {
    this.persistence = persistence ?? inject(TechnicalRecordPersistence);
  }

  async create(value: string): Promise<LocalTechnicalRecordCreate> {
    const operation: CreateTechnicalRecordOperation = {
      operationId: generateUuidV7(),
      recordId: generateUuidV7(),
      value,
      kind: 'create',
    };

    const record = await this.persistence.commitCreate(operation);
    return { record, operation };
  }
}
