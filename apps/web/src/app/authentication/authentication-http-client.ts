import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { AuthenticationHttpPort } from './authentication-http.port';

type JsonObject = Record<string, unknown>;

interface CsrfBootstrap {
  readonly token: string;
  readonly headerName: 'X-CSRF-TOKEN';
}

/** Narrow same-origin auth transport with closed cache and memory-only CSRF handling. */
@Injectable({ providedIn: 'root' })
export class AuthenticationHttpClient implements AuthenticationHttpPort {
  private readonly http = inject(HttpClient);
  private readonly origin = globalThis.location.origin;
  private csrf: CsrfBootstrap | undefined;

  async ensureCsrf(): Promise<void> {
    if (!this.csrf) await this.refreshCsrf();
  }

  async csrfHeaders(): Promise<Readonly<Record<string, string>>> {
    await this.ensureCsrf();
    const csrf = this.csrf;
    if (!csrf) throw new Error('Refresh account security before continuing.');
    return { [csrf.headerName]: csrf.token };
  }

  clearCsrf(): void {
    this.csrf = undefined;
  }

  async refreshCsrf(): Promise<void> {
    const value = await this.get<CsrfBootstrap>('/csrf');
    if (!value || value.headerName !== 'X-CSRF-TOKEN' || !/^[A-Za-z0-9_-]{43}$/.test(value.token)) {
      throw new Error('The account service returned an invalid security token.');
    }
    this.csrf = value;
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  post<T = JsonObject>(path: string, body: JsonObject, refresh = false): Promise<T> {
    if (!this.csrf) throw new Error('Refresh account security before continuing.');
    const headers = new HttpHeaders({ [this.csrf.headerName]: this.csrf.token });
    return this.request<T>('POST', path, body, headers).then(async (value) => {
      if (refresh) await this.refreshCsrf();
      return value;
    });
  }

  postBodyless<T>(path: string): Promise<T> {
    if (!this.csrf) throw new Error('Refresh account security before continuing.');
    const headers = new HttpHeaders({ [this.csrf.headerName]: this.csrf.token });
    return this.request<T>('POST', path, undefined, headers);
  }

  private async request<T>(
    method: 'GET' | 'POST',
    path: string,
    body?: JsonObject,
    headers?: HttpHeaders,
  ): Promise<T> {
    try {
      const response = await firstValueFrom(
        this.http.request<T>(method, `${this.origin}/api/v1/auth${path}`, {
          body,
          headers,
          observe: 'response',
          withCredentials: true,
        }),
      );
      return this.requireNoStore(response);
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        throw new Error('The account request could not be completed.', { cause: error });
      }
      throw error;
    }
  }

  private requireNoStore<T>(response: HttpResponse<T>): T {
    const directives = response.headers.get('Cache-Control')?.toLowerCase().split(',') ?? [];
    if (
      response.status !== 200 ||
      !directives.some((directive) => directive.trim() === 'no-store') ||
      response.body === null
    ) {
      throw new Error('The account service returned an invalid response.');
    }
    return response.body;
  }
}
