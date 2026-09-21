import { Component, inject } from '@angular/core';
import { Configuration } from './configuration';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatCard, MatCardContent, MatCardHeader, MatCardTitle } from '@angular/material/card';

@Component({
  imports: [
    ReactiveFormsModule,
    MatFormField,
    MatLabel,
    MatInput,
    MatCard,
    MatCardTitle,
    MatCardHeader,
    MatCardContent,
  ],
  selector: 'hortinis-configuration',
  styleUrl: './base-configuration.scss',
  templateUrl: './base-configuration.html',
})
export class BaseConfiguration {
  private readonly configuration = inject(Configuration);

  protected readonly gardenForm = new FormGroup({
    name: new FormControl<string>('My garden', {
      validators: Validators.required,
    }),
  });
  protected readonly serverForm = new FormGroup({
    address: new FormControl<string>('http://hortinis.test', Validators.required),
  });

  protected async configure(): Promise<void> {
    await this.configuration.configure();
  }
}
