import { inject, Service } from '@angular/core';
import { HortinisDatabase } from '../hortinis-database';
import { GardenRecord } from './garden-record';
import { CreateGardenInput } from '../../features/garden';
import { generateUuidV7 } from '../../utils/uuid-v7';

export class RecordNotFound extends Error {}

@Service()
export class GardenPersistence {
  private readonly database = inject(HortinisDatabase);

  public async listEnabledGardens(): Promise<GardenRecord[]> {
    return await this.database.garden.filter((record) => record.enabled).toArray();
  }

  public async createGarden(input: CreateGardenInput): Promise<GardenRecord> {
    const garden: GardenRecord = {
      id: generateUuidV7(),
      name: input.name,
      enabled: true,
    };
    await this.database.garden.add(garden);
    return garden;
  }

  public async deleteGarden(garden: GardenRecord): Promise<void> {
    await this.database.transaction('rw', this.database.garden, async () => {
      const record = await this.database.garden.get(garden.id);
      if (!record) {
        throw new RecordNotFound('Garden not found.');
      }
      await this.database.garden.update(record.id, { enabled: false });
    });
  }
}
