import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
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
} from './synchronization-transport';
import { SYNC_API_BASE_URL } from './sync-api-config';

@Injectable({ providedIn: 'root' })
export class HttpSynchronizationTransport implements SynchronizationTransport {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(SYNC_API_BASE_URL);

  async submitOperation(operation: TechnicalRecordOperation): Promise<OperationResult> {
    if (!isTechnicalRecordOperation(operation)) {
      throw new SynchronizationBoundaryError(
        'The operation does not satisfy the synchronization contract.',
        'request',
        operation,
      );
    }

    try {
      const response = await firstValueFrom(
        this.http.post<unknown>(`${this.baseUrl}/operations`, operation, { observe: 'response' }),
      );
      return this.readSuccess(response, isOperationResult);
    } catch (error) {
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

    try {
      const response = await firstValueFrom(
        this.http.get<unknown>(`${this.baseUrl}/changes`, {
          observe: 'response',
          params: cursor === undefined ? undefined : { cursor },
        }),
      );
      return this.readSuccess(response, isChangePage);
    } catch (error) {
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
      error instanceof SynchronizationUnavailableError
    ) {
      return error;
    }

    if (!(error instanceof HttpErrorResponse)) {
      return new SynchronizationUnavailableError(error);
    }

    if (error.status === 0) {
      return new SynchronizationUnavailableError(error);
    }

    const protocolError = this.readProtocolError(error.status, error.error);
    if (protocolError) {
      return new SynchronizationProtocolError(error.status as 400 | 404 | 409, protocolError);
    }
    return new SynchronizationUnexpectedResponseError(error.status, error.error);
  }

  private readProtocolError(status: number, body: unknown): SynchronizationError | undefined {
    if (status === 400 && isInvalidRequestError(body)) return body;
    if (status === 404 && isRecordNotFoundError(body)) return body;
    if (status === 409 && isConflictError(body)) return body;
    return undefined;
  }
}
