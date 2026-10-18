import { InjectionToken } from '@angular/core';
import type { SameOriginCsrfPort } from '../security/same-origin-csrf.port';

export interface AuthenticationHttpPort extends SameOriginCsrfPort {
  ensureCsrf(): Promise<void>;
  clearCsrf(): void;
  refreshCsrf(): Promise<void>;
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body: Record<string, unknown>, status?: number): Promise<T>;
  login<T>(email: string, password: string): Promise<T>;
  postBodyless<T>(path: string, allowDispatch?: () => boolean): Promise<T>;
}

export const AUTHENTICATION_HTTP = new InjectionToken<AuthenticationHttpPort>(
  'AUTHENTICATION_HTTP',
);

/** Fixed failures retain no Angular error, request body or reflected server detail. */
export class AccountRequestError extends Error {
  constructor(readonly status: number) {
    super('The account request could not be completed.');
  }
}
