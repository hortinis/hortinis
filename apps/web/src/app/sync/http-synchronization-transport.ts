import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import {
  isChangePage,
  isConflictError,
  isInvalidRequestError,
  isRecordNotFoundError,
  isTechnicalRecordOperation,
  type ChangePage,
  type OperationResult,
  type SynchronizationError,
  type TechnicalRecordOperation,
  isOperationResult,
} from './conformance';
import {
  SynchronizationBoundaryError,
  SynchronizationProtocolError,
  SynchronizationUnexpectedResponseError,
  SynchronizationUnavailableError,
  type SynchronizationTransport,
  type ExchangeAttemptBoundary,
} from './synchronization-transport';
import { SYNC_API_BASE_URL, SYNC_REQUEST_TIMEOUT_MILLISECONDS } from './sync-api-config';
import { BindingSession, type BindingRequestContext } from './binding-session';
import { SynchronizationAccessError } from './binding-transport';
import { bindingTransportFailure } from './http-binding-transport';
import { SynchronizationOwnershipLostError } from '../persistence/synchronization-ownership';
import { SAME_ORIGIN_CSRF } from '../security/same-origin-csrf.port';

@Injectable({ providedIn: 'root' })
export class HttpSynchronizationTransport implements SynchronizationTransport {
  private readonly http = inject(HttpClient);
  private readonly csrf = inject(SAME_ORIGIN_CSRF);
  private readonly baseUrl = inject(SYNC_API_BASE_URL);
  private readonly requestTimeoutMilliseconds = inject(SYNC_REQUEST_TIMEOUT_MILLISECONDS);
  private readonly binding = inject(BindingSession);

  prepareExchange(phase: 'push' | 'pull', boundary: ExchangeAttemptBoundary): Promise<void> {
    return this.binding.prepare(phase, boundary);
  }

  async submitOperation(operation: TechnicalRecordOperation): Promise<OperationResult> {
    if (!isTechnicalRecordOperation(operation)) {
      throw new SynchronizationBoundaryError(
        'The operation does not satisfy the synchronization contract.',
        'request',
        operation,
      );
    }

    let context: BindingRequestContext | undefined;
    try {
      context = await this.binding.requestContext();
      const securityHeaders =
        context.binding.identity.accessMode === 'single-user-auth'
          ? await this.csrf.csrfHeaders()
          : {};
      const response = await firstValueFrom(
        this.http
          .post<unknown>(`${this.baseUrl}/operations`, operation, {
            observe: 'response',
            headers: { ...securityHeaders, 'Sync-Expectation': context.expectation },
          })
          .pipe(timeout(this.requestTimeoutMilliseconds)),
      );
      await this.binding.assertCurrent(context);
      return this.readSuccess(response, isOperationResult);
    } catch (error) {
      await this.checkBindingFailure(error, context);
      throw this.translateError(error);
    }
  }

  async pullChanges(cursor?: string): Promise<ChangePage> {
    if (cursor !== undefined && (typeof cursor !== 'string' || cursor.length === 0)) {
      throw new SynchronizationBoundaryError(
        'The synchronization cursor must be a non-empty string.',
        'request',
        cursor,
      );
    }

    let context: BindingRequestContext | undefined;
    try {
      context = await this.binding.requestContext();
      const response = await firstValueFrom(
        this.http
          .get<unknown>(`${this.baseUrl}/changes`, {
            observe: 'response',
            params: cursor === undefined ? undefined : { cursor },
            headers: { 'Sync-Expectation': context.expectation },
          })
          .pipe(timeout(this.requestTimeoutMilliseconds)),
      );
      await this.binding.assertCurrent(context);
      return this.readSuccess(response, isChangePage);
    } catch (error) {
      await this.checkBindingFailure(error, context);
      throw this.translateError(error);
    }
  }

  private readSuccess<T>(
    response: HttpResponse<unknown>,
    guard: (value: unknown) => value is T,
  ): T {
    if (response.status !== 200 || !guard(response.body)) {
      throw new SynchronizationUnexpectedResponseError(response.status, response.body);
    }
    return response.body;
  }

  private translateError(error: unknown): Error {
    if (
      error instanceof SynchronizationBoundaryError ||
      error instanceof SynchronizationProtocolError ||
      error instanceof SynchronizationUnexpectedResponseError ||
      error instanceof SynchronizationUnavailableError ||
      error instanceof SynchronizationOwnershipLostError
    ) {
      return error;
    }

    if (!(error instanceof HttpErrorResponse)) {
      return new SynchronizationUnavailableError(error);
    }

    if (error.status === 0) {
      return new SynchronizationUnavailableError(error);
    }
    if (error.status === 502 || error.status === 503 || error.status === 504) {
      return new SynchronizationUnavailableError(error);
    }

    const protocolError = this.readProtocolError(error.status, error.error);
    if (protocolError) {
      return new SynchronizationProtocolError(error.status as 400 | 404 | 409, protocolError);
    }
    return new SynchronizationUnexpectedResponseError(error.status, error.error);
  }

  private async checkBindingFailure(
    error: unknown,
    context: BindingRequestContext | undefined,
  ): Promise<void> {
    if (!context) return;
    await this.binding.assertCurrent(context);
    const translated =
      error instanceof HttpErrorResponse &&
      (error.status === 401 ||
        error.status === 403 ||
        (error.status === 409 && error.error?.code === 'BINDING_PRECONDITION_FAILED'))
        ? bindingTransportFailure(error)
        : error;
    if (translated instanceof SynchronizationAccessError) {
      await this.binding.denyAccess(context, translated.body.code);
      // Established destinations may renew only after rechecking the retained identity; retry stays bounded.
      throw new SynchronizationUnavailableError();
    }
  }

  private readProtocolError(status: number, body: unknown): SynchronizationError | undefined {
    if (status === 400 && isInvalidRequestError(body)) return body;
    if (status === 404 && isRecordNotFoundError(body)) return body;
    if (status === 409 && isConflictError(body)) return body;
    return undefined;
  }
}
