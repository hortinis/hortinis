import type { HortinisDatabase } from './hortinis-database';
import type { TechnicalRecord } from '../sync/conformance';

export class TechnicalRecordProjection {
  constructor(private readonly database: HortinisDatabase) {}
  async storeAcceptedRecord(record: TechnicalRecord): Promise<void> {
    const current = await this.database.acceptedTechnicalRecords.get(record.recordId);
    if (current && BigInt(current.revision) > BigInt(record.revision)) return;
    if (current?.revision === record.revision && current.value !== record.value) {
      throw new Error('Equal accepted revisions contain different values.');
    }
    await this.database.acceptedTechnicalRecords.put(record);
  }

  async projectAcceptedRecord(recordId: string): Promise<void> {
    if (await this.database.technicalTombstones.get(recordId)) return;
    const accepted = await this.database.acceptedTechnicalRecords.get(recordId);
    if (!accepted) return;
    const deletion = await this.database.pendingDeletionRecords.get(recordId);
    if (deletion) {
      await this.database.pendingDeletionRecords.put({
        ...deletion,
        lastAcceptedRevision: accepted.revision,
      });
      return;
    }
    const local = await this.database.technicalRecords.get(recordId);
    const pending = await this.database.outboxOperations.where('recordId').equals(recordId).count();
    const rejected = await this.database.rejectedOperations
      .where('recordId')
      .equals(recordId)
      .count();
    await this.database.technicalRecords.put({
      recordId,
      value: (pending > 0 || rejected > 0) && local ? local.value : accepted.value,
      lastAcceptedRevision: accepted.revision,
    });
  }
}
