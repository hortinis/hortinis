import { inject, Service } from '@angular/core';
import { ConfigurationPersistence } from '../persistence/configuration/configuration-persistence';
import { Router } from '@angular/router';

export interface ConfigurationModel {
  sync: false;
  serverUrl?: string;
}

@Service()
export class Configuration {
  private readonly persistence = inject(ConfigurationPersistence);
  private readonly router = inject(Router);

  public async configured(): Promise<boolean> {
    return await this.persistence.configured();
  }
  public async configure(): Promise<void> {
    await this.persistence.configure();
    await this.router.navigate(['home']);
  }
}
