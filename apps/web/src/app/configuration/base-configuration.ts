import { Component, inject } from '@angular/core';
import { Configuration } from './configuration';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatCard, MatCardContent, MatCardHeader, MatCardTitle } from '@angular/material/card';
import { MatButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { CreateGarden } from '../features/garden/create-garden/create-garden';

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
    MatButton,
  ],
  selector: 'hortinis-configuration',
  styleUrl: './base-configuration.scss',
  templateUrl: './base-configuration.html',
})
export class BaseConfiguration {
  private readonly configuration = inject(Configuration);
  private readonly dialog = inject(MatDialog);

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
  protected newGarden() {
    const dialogRef = this.dialog.open(CreateGarden, {
      panelClass: 'create-garden-dialog',
    });

    dialogRef.afterClosed().subscribe(() => {
      console.log('The dialog was closed');
    });
  }
}
