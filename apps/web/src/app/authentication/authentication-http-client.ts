import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { AccountRequestError, type AuthenticationHttpPort } from './authentication-http.port';
import { AuthenticationCookieGate } from '../sync/authentication-cookie-gate';

interface CsrfBootstrap {
  readonly token: string;
  readonly headerName: 'X-CSRF-TOKEN';
}

@Injectable({ providedIn: 'root' })
export class AuthenticationHttpClient implements AuthenticationHttpPort {
  private readonly http = inject(HttpClient);
  private readonly gate = inject(AuthenticationCookieGate);
  private readonly origin = location.origin;
  private csrf: CsrfBootstrap | undefined;
  private csrfRevision = '';
  private generation = 0;

  async ensureCsrf(): Promise<void> {
    if (!this.csrf || this.csrfRevision !== this.gate.revision()) await this.refreshCsrf();
  }

  async csrfHeaders(): Promise<Readonly<Record<string, string>>> {
    await this.ensureCsrf();
    if (!this.csrf) throw new AccountRequestError(0);
    return { [this.csrf.headerName]: this.csrf.token };
  }

  clearCsrf(): void {
    this.generation++;
    this.csrf = undefined;
  }

  async refreshCsrf(): Promise<void> {
    this.clearCsrf();
    const generation = this.generation;
    const revision = this.gate.revision();
    const value = await this.get<CsrfBootstrap>('/csrf');
    if (generation !== this.generation || !this.gate.current(revision))
      throw new AccountRequestError(0);
    if (
      !value ||
      Object.keys(value).sort().join(',') !== 'headerName,token' ||
      value.headerName !== 'X-CSRF-TOKEN' ||
      typeof value.token !== 'string' ||
      !/^[A-Za-z0-9_+/=-]{1,256}(?![\s\S])/.test(value.token)
    )
      throw new AccountRequestError(0);
    this.csrf = value;
    this.csrfRevision = revision;
  }

  async sessionLost(): Promise<void> {
    const revision = this.gate.markPending();
    this.clearCsrf();
    if (navigator.locks)
      await navigator.locks
        .request('hortinis.authentication-cookie-workflow.v2', { mode: 'exclusive' }, async () => {
          if (this.gate.current(revision)) await this.refreshCsrf();
        })
        .catch(() => undefined);
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path);
  }

  async post<T>(path: string, body: Record<string, unknown>, status = 200): Promise<T> {
    return this.request<T>('POST', path, body, await this.csrfHeaders(), status);
  }

  async login<T>(email: string, password: string): Promise<T> {
    for (const value of [email, password])
      for (const character of value) {
        const code = character.codePointAt(0)!;
        if (code >= 0xd800 && code <= 0xdfff) throw new AccountRequestError(400);
      }
    const body = new URLSearchParams({ email, password }).toString();
    return this.request<T>('POST', '/login', body, {
      ...(await this.csrfHeaders()),
      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
    });
  }

  async postBodyless<T>(path: string): Promise<T> {
    return this.request<T>('POST', path, undefined, await this.csrfHeaders());
  }

  private async request<T>(
    method: 'GET' | 'POST',
    path: string,
    body?: unknown,
    headers?: Readonly<Record<string, string>>,
    status = 200,
  ): Promise<T> {
    const revision = this.gate.revision();
    try {
      const response = await firstValueFrom(
        this.http
          .request<T>(method, `${this.origin}/api/v1/auth${path}`, {
            body,
            headers: new HttpHeaders({ Accept: 'application/json', ...headers }),
            observe: 'response',
            withCredentials: true,
          })
          .pipe(timeout(15000)),
      );
      if (!this.gate.current(revision)) throw new AccountRequestError(0);
      return this.read(response, status);
    } catch (error) {
      if (!this.gate.current(revision)) throw new AccountRequestError(0);
      const code = this.failureStatus(error);
      if (code === 401 || code === 403) this.clearCsrf();
      throw new AccountRequestError(code);
    }
  }

  private failureStatus(error: unknown): number {
    if (!(error instanceof HttpErrorResponse)) return 0;
    const codes: Record<number, readonly string[]> = {
      400: ['INVALID_REQUEST'],
      401: ['AUTHENTICATION_FAILED', 'AUTHENTICATION_REQUIRED'],
      403: ['ACCESS_DENIED'],
      409: ['AUTHENTICATION_PRECONDITION_FAILED'],
      429: ['AUTHENTICATION_THROTTLED'],
      503: ['ACCESS_UNAVAILABLE'],
    };
    const messages: Record<string, string> = {
      INVALID_REQUEST: 'The request is invalid.',
      AUTHENTICATION_FAILED: 'Authentication failed.',
      AUTHENTICATION_REQUIRED: 'Authentication is required.',
      ACCESS_DENIED: 'Access is denied.',
      AUTHENTICATION_PRECONDITION_FAILED: 'The authentication precondition no longer holds.',
      AUTHENTICATION_THROTTLED: 'Authentication attempts are temporarily limited.',
      ACCESS_UNAVAILABLE: 'The access service is unavailable.',
    };
    const body: unknown = error.error;
    if (
      typeof body !== 'object' ||
      body === null ||
      Array.isArray(body) ||
      Object.keys(body).sort().join(',') !== 'code,message'
    )
      return 0;
    const fixed = body as { code: string; message: string };
    if (
      !codes[error.status]?.includes(fixed.code) ||
      messages[fixed.code] !== fixed.message ||
      error.headers.get('Content-Type')?.split(';')[0]?.trim() !== 'application/json' ||
      !error.headers
        .get('Cache-Control')
        ?.toLowerCase()
        .split(',')
        .some((v) => v.trim() === 'no-store')
    )
      return 0;
    return error.status;
  }

  private read<T>(response: HttpResponse<T>, status: number): T {
    if (
      response.status !== status ||
      response.body === null ||
      response.headers.get('Content-Type')?.split(';')[0]?.trim() !== 'application/json' ||
      !response.headers
        .get('Cache-Control')
        ?.toLowerCase()
        .split(',')
        .some((v) => v.trim() === 'no-store')
    ) {
      throw new AccountRequestError(0);
    }
    return response.body;
  }
}
