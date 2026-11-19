import { Component } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink } from '@angular/router';

@Component({
  imports: [MatButton, RouterLink],
  selector: 'hortinis-home',
  styleUrl: './home.scss',
  templateUrl: './home.html',
})
export class Home {}
