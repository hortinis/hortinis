import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import {
  parseAccessContract,
  type AccessContractValues,
  type ConfirmEmptyBindingRequest,
} from './access-contracts.rule';
import { SynchronizationAccessError, type BindingTransport } from './binding-transport';
import { SYNC_API_BASE_URL, SYNC_REQUEST_TIMEOUT_MILLISECONDS } from './sync-api-config';
import { normalizeServerOrigin } from './server-origin.rule';
import { SAME_ORIGIN_CSRF } from '../security/same-origin-csrf.port';
import {
  SynchronizationUnavailableError,
  SynchronizationBoundaryError,
} from './synchronization-transport';

@Injectable({ providedIn: 'root' })
export class HttpBindingTransport implements BindingTransport {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(SAME_ORIGIN_CSRF);
  private readonly timeoutMilliseconds = inject(SYNC_REQUEST_TIMEOUT_MILLISECONDS);
  readonly serverOrigin = this.resolveOrigin(inject(SYNC_API_BASE_URL));

  capabilities() {
    return this.get('ServerCapabilities', '/api/v1/server/capabilities');
  }

  bootstrap(expectation?: string) {
    const token =
      expectation === undefined
        ? undefined
        : parseAccessContract('ExchangeExpectation', expectation);
    return this.get('SynchronizationBootstrap', '/api/v1/sync/bootstrap', token);
  }

  async confirm(
    request: ConfirmEmptyBindingRequest,
    accessMode: 'single-user-no-auth' | 'single-user-auth' | 'multi-user-auth',
  ) {
    const body = parseAccessContract('ConfirmEmptyBindingRequest', request);
    try {
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${this.serverOrigin}/api/v1/sync/bindings/confirm`, body, {
            observe: 'response',
            headers: accessMode === 'single-user-auth' ? await this.csrf.csrfHeaders() : undefined,
          })
          .pipe(timeout(this.timeoutMilliseconds)),
      );
      return this.read('SynchronizationBootstrap', response);
    } catch (error) {
      throw bindingTransportFailure(error);
    }
  }

  private async get<Name extends 'ServerCapabilities' | 'SynchronizationBootstrap'>(
    name: Name,
    path: string,
    expectation?: string,
  ) {
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${this.serverOrigin}${path}`, {
            observe: 'response',
            headers: expectation ? { 'Sync-Expectation': expectation } : undefined,
          })
          .pipe(timeout(this.timeoutMilliseconds)),
      );
      return this.read(name, response);
    } catch (error) {
      throw bindingTransportFailure(error);
    }
  }

  private read<Name extends keyof AccessContractValues>(
    name: Name,
    response: HttpResponse<unknown>,
  ): AccessContractValues[Name] {
    if (
      response.status !== 200 ||
      !response.headers
        .get('Cache-Control')
        ?.split(',')
        .some((value) => value.trim().toLowerCase() === 'no-store')
    ) {
      throw new SynchronizationBoundaryError('The binding response is invalid.', 'response');
    }
    try {
      return parseAccessContract(name, response.body);
    } catch {
      throw new SynchronizationBoundaryError('The binding response is invalid.', 'response');
    }
  }

  private resolveOrigin(baseUrl: string): string {
    const current = normalizeServerOrigin(globalThis.location.origin);
    const url = new URL(baseUrl, `${current}/`);
    if (
      url.origin !== current ||
      url.pathname !== '/api/v1/sync' ||
      url.search ||
      url.hash ||
      url.username ||
      url.password
    ) {
      throw new SynchronizationBoundaryError('The binding destination is unsupported.', 'request');
    }
    return normalizeServerOrigin(url.origin);
  }
}

export function bindingTransportFailure(error: unknown): Error {
  if (
    error instanceof SynchronizationBoundaryError ||
    error instanceof SynchronizationAccessError ||
    error instanceof SynchronizationUnavailableError
  )
    return error;
  if (!(error instanceof HttpErrorResponse)) return new SynchronizationUnavailableError();
  const schemas = {
    401: 'AuthenticationRequiredError',
    403: 'AccessDeniedError',
    409: 'BindingPreconditionFailedError',
  } as const;
  if (error.status === 401 || error.status === 403 || error.status === 409) {
    try {
      return new SynchronizationAccessError(
        parseAccessContract(schemas[error.status], error.error),
      );
    } catch {
      return new SynchronizationBoundaryError('The binding response is invalid.', 'response');
    }
  }
  if (error.status === 0 || error.status === 502 || error.status === 503 || error.status === 504)
    return new SynchronizationUnavailableError();
  return new SynchronizationBoundaryError('The binding response is invalid.', 'response');
}
