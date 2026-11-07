import { Component, inject, signal, ViewEncapsulation } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormField, MatLabel, MatError } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { CreateGardenInput } from '../../garden';
import { GardenService } from '../garden-service';
import { form, pattern, required, FormRoot, FormField } from '@angular/forms/signals';
import { Configuration } from '../../../configuration/configuration';

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
    MatError,
  ],
  selector: 'hortinis-create-garden',
  styleUrl: './create-garden.scss',
  templateUrl: './create-garden.html',
  encapsulation: ViewEncapsulation.None,
})
export class CreateGarden {
  private readonly dialog = inject(MatDialogRef);
  private readonly initialConfiguration = inject(MAT_DIALOG_DATA).initialConfiguration === true;
  private readonly persistence = inject(GardenService);
  private readonly configuration = inject(Configuration);

  private readonly gardenModel = signal({
    name: 'My garden',
  });
  protected readonly gardenForm = form(
    this.gardenModel,
    (schema) => {
      required(schema.name, {
        message: 'A garden name is required.',
      });
      pattern(schema.name, /\S/, {
        message: 'Enter a garden name.',
      });
    },
    {
      submission: {
        action: async (fields) => {
          const name = fields().value().name.trim();
          const input: CreateGardenInput = {
            name: name,
          };

          try {
            const garden = await this.persistGarden(input);

            this.dialog.close({ type: 'success', garden });
          } catch {
            console.error('Garden creation failed.');
          }
        },
      },
    },
  );

  private async persistGarden(input: CreateGardenInput) {
    if (this.initialConfiguration) {
      return await this.configuration.configure(input);
    } else {
      return await this.persistence.createGarden(input);
    }
  }
}
