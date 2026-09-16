import { inject, InjectionToken } from '@angular/core';
import { HttpSynchronizationTransport } from './http-synchronization-transport';
import type { SynchronizationTransport } from './synchronization-transport';

export const SYNCHRONIZATION_TRANSPORT = new InjectionToken<SynchronizationTransport>(
  'Hortinis synchronization transport',
  {
    providedIn: 'root',
    factory: () => inject(HttpSynchronizationTransport),
  },
);
