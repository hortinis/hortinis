import { InjectionToken } from '@angular/core';

export type AuthenticationJsonObject = Record<string, unknown>;

/** Small replaceable transport boundary; only its adapter depends on Angular HTTP. */
export interface AuthenticationHttpPort {
  ensureCsrf(): Promise<void>;
  clearCsrf(): void;
  refreshCsrf(): Promise<void>;
  get<T>(path: string): Promise<T>;
  post<T = AuthenticationJsonObject>(
    path: string,
    body: AuthenticationJsonObject,
    refresh?: boolean,
  ): Promise<T>;
  postBodyless<T>(path: string): Promise<T>;
}

export const AUTHENTICATION_HTTP = new InjectionToken<AuthenticationHttpPort>(
  'AUTHENTICATION_HTTP',
);
