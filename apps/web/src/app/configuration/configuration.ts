import { inject, Service } from '@angular/core';
import { ConfigurationPersistence } from '../persistence/configuration/configuration-persistence';

export interface ConfigurationModel {
  sync: false;
  serverUrl?: string;
}

@Service()
export class Configuration {
  private readonly persistence = inject(ConfigurationPersistence);

  public async configured(): Promise<boolean> {
    return await this.persistence.configured();
  }
  public async configure(): Promise<void> {
    return await this.persistence.configure();
  }
}
