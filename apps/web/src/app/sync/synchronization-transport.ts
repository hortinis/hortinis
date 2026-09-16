import type {
  ChangePage,
  OperationResult,
  SynchronizationError,
  TechnicalRecordOperation,
} from './conformance';

export interface SynchronizationTransport {
  submitOperation(operation: TechnicalRecordOperation): Promise<OperationResult>;
  pullChanges(cursor?: string): Promise<ChangePage>;
}

export class SynchronizationBoundaryError extends Error {
  readonly kind = 'boundary';

  constructor(
    message: string,
    readonly direction: 'request' | 'response',
    readonly details?: unknown,
  ) {
    super(message);
    this.name = 'SynchronizationBoundaryError';
  }
}

export class SynchronizationProtocolError extends Error {
  readonly kind = 'protocol';

  constructor(
    readonly status: 400 | 404 | 409,
    readonly body: SynchronizationError,
  ) {
    super(body.message);
    this.name = 'SynchronizationProtocolError';
  }
}

export class SynchronizationUnavailableError extends Error {
  readonly kind = 'unavailable';

  constructor(override readonly cause?: unknown) {
    super('The synchronization service is unavailable.');
    this.name = 'SynchronizationUnavailableError';
  }
}

export class SynchronizationUnexpectedResponseError extends Error {
  readonly kind = 'unexpected-response';

  constructor(
    readonly status: number,
    readonly body?: unknown,
  ) {
    super(`The synchronization service returned an unexpected HTTP response (${status}).`);
    this.name = 'SynchronizationUnexpectedResponseError';
  }
}
