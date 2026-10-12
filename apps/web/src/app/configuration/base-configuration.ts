import { Component, inject } from '@angular/core';
import { Configuration } from './configuration';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { CreateGarden } from '../features/garden/create-garden/create-garden';
import { Router } from '@angular/router';

@Component({
  imports: [ReactiveFormsModule, MatButton],
  selector: 'hortinis-configuration',
  styleUrl: './base-configuration.scss',
  templateUrl: './base-configuration.html',
})
export class BaseConfiguration {
  private readonly configuration = inject(Configuration);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  protected readonly gardenForm = new FormGroup({
    name: new FormControl<string>('My garden', {
      validators: Validators.required,
    }),
  });
  protected readonly serverForm = new FormGroup({
    address: new FormControl<string>('http://hortinis.test', Validators.required),
  });

  protected newGarden(): void {
    const dialogRef = this.dialog.open(CreateGarden, {
      panelClass: 'hortinis-dialog',
      data: {
        initialConfiguration: true,
      },
    });

    dialogRef.afterClosed().subscribe(async (result) => {
      console.log('The dialog was closed');
      if (result?.type === 'success') {
        await this.router.navigate(['/']);
      }
    });
  }
}
