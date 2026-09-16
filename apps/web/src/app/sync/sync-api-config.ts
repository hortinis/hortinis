import { InjectionToken } from '@angular/core';

export const SYNC_API_BASE_URL = new InjectionToken<string>(
  'Hortinis synchronization API base URL',
  {
    providedIn: 'root',
    factory: () => '/api/v1/sync',
  },
);
