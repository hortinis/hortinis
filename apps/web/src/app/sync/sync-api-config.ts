import { InjectionToken } from '@angular/core';

export const SYNC_API_BASE_URL = new InjectionToken<string>(
  'Hortinis synchronization API base URL',
  {
    providedIn: 'root',
    factory: () => '/api/v1/sync',
  },
);

export const SYNC_REQUEST_TIMEOUT_MILLISECONDS = new InjectionToken<number>(
  'Hortinis synchronization request timeout',
  { providedIn: 'root', factory: () => 10_000 },
);
