import { Component, inject, signal, ViewEncapsulation } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { CreateGardenOperation } from '../../../sync/garden-operation';
import { generateUuidV7 } from '../../../sync/uuid-v7';
import { GardenPersistence } from '../../../persistence/features/garden-persistence';
import { form, pattern, required, FormRoot, FormField } from '@angular/forms/signals';

@Component({
  imports: [
    MatButton,
    ReactiveFormsModule,
    MatFormField,
    MatInput,
    MatDialogModule,
    MatLabel,
    FormRoot,
    FormField,
  ],
  selector: 'hortinis-create-garden',
  styleUrl: './create-garden.scss',
  templateUrl: './create-garden.html',
  encapsulation: ViewEncapsulation.None,
})
export class CreateGarden {
  private readonly persistence = inject(GardenPersistence);
  protected readonly gardenModel = signal({
    name: 'Mon jardin',
  });
  protected readonly gardenForm = form(
    this.gardenModel,
    (schema) => {
      required(schema.name);
      pattern(schema.name, /S/);
    },
    {
      submission: {
        action: async (fields) => {
          const name = fields().value().name.trim();
          const operation: CreateGardenOperation = {
            operationId: generateUuidV7(),
            gardenId: generateUuidV7(),
            kind: 'createGarden',
            name: name,
          };

          const garden = await this.persistence.createGarden(operation);
        },
      },
    },
  );
}
