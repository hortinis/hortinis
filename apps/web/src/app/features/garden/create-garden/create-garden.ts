import { Component, inject, signal, ViewEncapsulation } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormField, MatLabel, MatError } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { CreateGardenOperation } from '../../../sync/garden-operation';
import { generateUuidV7 } from '../../../sync/uuid-v7';
import { GardenPersistence } from '../../../persistence/features/garden-persistence';
import { form, pattern, required, FormRoot, FormField } from '@angular/forms/signals';
import { ConfigurationPersistence } from '../../../persistence/configuration/configuration-persistence';
import { Configuration } from '../../../configuration/configuration';
import { GardenRecord } from '../../../persistence/features/garden-record';

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
  private readonly persistence = inject(GardenPersistence);
  private readonly configuration = inject(Configuration);

  private readonly gardenModel = signal({
    name: 'Mon jardin',
  });
  protected readonly gardenForm = form(
    this.gardenModel,
    (schema) => {
      required(schema.name, {
        message: 'pas content required',
      });
      pattern(schema.name, /\S/, {
        message: 'pas content pattern',
      });
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

          try {
            const garden = await this.persistGarden(operation);
            console.log('Successfuly created garden: ' + garden.id);

            this.dialog.close({ type: 'success', garden });
          } catch (error) {
            console.error('something broke', error);
          }
        },
      },
    },
  );

  private async persistGarden(operation: CreateGardenOperation) {
    if (this.initialConfiguration) {
      return await this.configuration.configure(operation);
    } else {
      return await this.persistence.createGarden(operation);
    }
  }
}
