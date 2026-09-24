import { Component } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';

@Component({
  imports: [MatButton, ReactiveFormsModule, MatFormField, MatInput, MatDialogModule, MatLabel],
  selector: 'hortinis-create-garden',
  styleUrl: './create-garden.scss',
  templateUrl: './create-garden.html',
})
export class CreateGarden {
  protected readonly garden = new FormGroup({
    name: new FormControl('Mon jardin', {
      nonNullable: true,
      validators: Validators.required,
    }),
  });

  protected validate(): void {}
}
