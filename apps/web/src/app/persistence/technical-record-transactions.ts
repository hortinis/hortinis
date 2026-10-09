import type { Table } from 'dexie';
import type { HortinisDatabase } from './hortinis-database';
import {
  assertSynchronizationOwnership,
  type SynchronizationOwnership,
} from './synchronization-ownership';

export class TechnicalRecordTransactions {
  constructor(private readonly database: HortinisDatabase) {}

  run<T>(
    tables: Table[],
    work: () => Promise<T>,
    ownership?: SynchronizationOwnership,
  ): Promise<T> {
    return this.database.transaction(
      'rw',
      [...tables, this.database.synchronizationLeases],
      async () => {
        await assertSynchronizationOwnership(this.database, ownership);
        return work();
      },
    );
  }
}
