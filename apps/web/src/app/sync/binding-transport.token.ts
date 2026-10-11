import { inject, InjectionToken } from '@angular/core';
import { HttpBindingTransport } from './http-binding-transport';
import type { BindingTransport } from './binding-transport';

export const BINDING_TRANSPORT = new InjectionToken<BindingTransport>(
  'Hortinis binding transport',
  {
    providedIn: 'root',
    factory: () => inject(HttpBindingTransport),
  },
);
