import { Component, inject } from '@angular/core';
import { Configuration } from './configuration';

@Component({
  imports: [],
  selector: 'hortinis-configuration',
  styleUrl: './base-configuration.scss',
  templateUrl: './base-configuration.html',
})
export class BaseConfiguration {
  private readonly configuration = inject(Configuration);

  protected async configure(): Promise<void> {
    return await this.configuration.configure();
  }
}
