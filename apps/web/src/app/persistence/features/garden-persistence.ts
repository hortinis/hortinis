import { inject, Service } from '@angular/core';
import { HortinisDatabase } from '../hortinis-database';
import { GardenRecord } from './garden-record';
import { CreateGardenOperation } from '../../sync/garden-operation';
import { PendingGardenOperation } from '../local-synchronization-operation';
import { generateUuidV7, HortinisUuid } from '../../sync/uuid-v7';

export class RecordNotFound extends Error {
  public constructor(
    private readonly entityId: HortinisUuid,
    private readonly entityType: string,
    message?: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
  }
}

@Service()
export class GardenPersistence {
  private readonly database = inject(HortinisDatabase);

  public async listEnabledGardens(): Promise<GardenRecord[]> {
    return await this.database.transaction('r', this.database.garden, async () => {
      return await this.database.garden.filter((r) => r.enabled).toArray();
    });
  }
  public async createGarden(operation: CreateGardenOperation): Promise<GardenRecord> {
    const garden: GardenRecord = {
      id: operation.gardenId,
      name: operation.name,
      enabled: true,
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

  public async deleteGarden(garden: GardenRecord) {
    const operationId = generateUuidV7();
    const operation: PendingGardenOperation = {
      operationId: operationId,
      entityId: garden.id,
      entityType: 'garden',
      operation: {
        kind: 'deleteGarden',
        gardenId: garden.id,
        operationId: operationId,
      },
    };
    await this.database.transaction(
      'rw',
      this.database.garden,
      this.database.outboxOperations,
      async () => {
        const record = await this.database.garden.get(garden.id);
        if (!record) {
          throw new RecordNotFound(operation.entityId, operation.entityType);
        }
        record.enabled = false;
        await this.database.garden.update(record.id, record);
        await this.database.outboxOperations.add(operation);
      },
    );
  }
}
