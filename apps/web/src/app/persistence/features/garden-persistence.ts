import { inject, Service } from '@angular/core';
import { HortinisDatabase } from '../hortinis-database';
import { GardenRecord } from './garden-record';
import { CreateGardenOperation } from '../../sync/garden-operation';
import { PendingGardenOperation } from '../local-synchronization-operation';

@Service()
export class GardenPersistence {
  private readonly database = inject(HortinisDatabase);

  public async createGarden(operation: CreateGardenOperation): Promise<GardenRecord> {
    const garden: GardenRecord = {
      id: operation.gardenId,
      name: operation.name,
      lastAcceptedRevision: null,
    };
    const pending: PendingGardenOperation = {
      operationId: operation.operationId,
      entityType: 'garden',
      entityId: operation.gardenId,
      operation,
    };
    await this.database.transaction(
      'rw',
      this.database.garden,
      this.database.outboxOperations,
      async () => {
        await this.database.garden.add(garden);
        await this.database.outboxOperations.add(pending);
      },
    );
    return garden;
  }
}
