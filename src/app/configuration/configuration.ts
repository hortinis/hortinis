import { inject, Service } from '@angular/core';
import { ConfigurationPersistence } from '../persistence/configuration/configuration-persistence';
import { GardenPersistence } from '../persistence/features/garden-persistence';
import { CreateGardenInput } from '../features/garden';
import { GardenRecord } from '../persistence/features/garden-record';
import { HortinisDatabase } from '../persistence/hortinis-database';

@Service()
export class Configuration {
  private readonly persistence = inject(ConfigurationPersistence);
  private readonly gardenPersistence = inject(GardenPersistence);
  private readonly database = inject(HortinisDatabase);

  public async configured(): Promise<boolean> {
    return await this.persistence.configured();
  }
  public async configure(input: CreateGardenInput): Promise<GardenRecord> {
    return await this.database.transaction(
      'rw',
      this.database.garden,
      this.database.configuration,
      async () => {
        await this.persistence.configure();
        return await this.gardenPersistence.createGarden(input);
      },
    );
  }
}
