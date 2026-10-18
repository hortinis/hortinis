import { Routes } from '@angular/router';

// The shell owns the small account forms; these inert routes perform no account action.
export const routes: Routes = [
  { path: 'accept-invitation', children: [] },
  { path: 'reset-password', children: [] },
];
