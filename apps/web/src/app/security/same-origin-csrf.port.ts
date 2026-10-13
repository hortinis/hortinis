import { InjectionToken } from '@angular/core';

/** Supplies the current in-memory CSRF header for protected same-origin application writes. */
export interface SameOriginCsrfPort {
  csrfHeaders(): Promise<Readonly<Record<string, string>>>;
}

export const SAME_ORIGIN_CSRF = new InjectionToken<SameOriginCsrfPort>('SAME_ORIGIN_CSRF');
