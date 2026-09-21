import { inject, Service } from '@angular/core';
import { HortinisDatabase } from '../hortinis-database';
import { generateUuidV7 } from '../../sync/uuid-v7';

@Service()
export class ConfigurationPersistence {
  private readonly database = inject(HortinisDatabase);

  async configured(): Promise<boolean> {
    const configurationCount = await this.database.transaction(
      'r',
      this.database.configuration,
      async () => this.database.configuration.count(),
    );
    return configurationCount > 0;
  }
  async configure(): Promise<void> {
    await this.database.transaction('rw', this.database.configuration, async () =>
      this.database.configuration.add({
        sync: false,
      }),
    );
    console.log('persisted conf');
  }
}
