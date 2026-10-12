import { inject, Service } from '@angular/core';
import { ConfigurationPersistence } from '../persistence/configuration/configuration-persistence';
import { Router } from '@angular/router';
import { GardenPersistence } from '../persistence/features/garden-persistence';
import { CreateGardenOperation } from '../sync/garden-operation';
import { GardenRecord } from '../persistence/features/garden-record';
import { HortinisDatabase } from '../persistence/hortinis-database';

export interface ConfigurationModel {
  sync: false;
  serverUrl?: string;
}

@Service()
export class Configuration {
  private readonly persistence = inject(ConfigurationPersistence);
  private readonly gardenPersistence = inject(GardenPersistence);
  private readonly database = inject(HortinisDatabase);

  public async configured(): Promise<boolean> {
    return await this.persistence.configured();
  }
  public async configure(operation: CreateGardenOperation): Promise<GardenRecord> {
    return await this.database.transaction(
      'rw',
      this.database.garden,
      this.database.outboxOperations,
      this.database.configuration,
      async () => {
        await this.persistence.configure();
        return await this.gardenPersistence.createGarden(operation);
      },
    );
  }
}
