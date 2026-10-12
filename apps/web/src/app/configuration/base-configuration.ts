import { Component, inject } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { Router } from '@angular/router';
import { CreateGardenHandler } from '../features/garden/create-garden/create-garden-handler';

@Component({
  imports: [ReactiveFormsModule, MatButton],
  selector: 'hortinis-configuration',
  styleUrl: './base-configuration.scss',
  templateUrl: './base-configuration.html',
})
export class BaseConfiguration {
  private readonly createGardenHandler = inject(CreateGardenHandler);
  private readonly router = inject(Router);

  protected readonly gardenForm = new FormGroup({
    name: new FormControl<string>('My garden', {
      validators: Validators.required,
    }),
  });
  protected readonly serverForm = new FormGroup({
    address: new FormControl<string>('http://hortinis.test', Validators.required),
  });

  protected async newGarden(): Promise<void> {
    const result = await this.createGardenHandler.createGarden(true);
    if (result) {
      await this.router.navigate(['/']);
    }
  }
}
