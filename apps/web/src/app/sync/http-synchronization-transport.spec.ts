import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import {
  SynchronizationBoundaryError,
  SynchronizationProtocolError,
  SynchronizationUnexpectedResponseError,
  SynchronizationUnavailableError,
} from './synchronization-transport';
import { HttpSynchronizationTransport } from './http-synchronization-transport';
import type { CreateTechnicalRecordOperation } from './conformance';

describe('HttpSynchronizationTransport', () => {
  let transport: HttpSynchronizationTransport;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    transport = TestBed.inject(HttpSynchronizationTransport);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('submits a validated operation to the versioned endpoint', async () => {
    const operation = createOperation();
    const resultPromise = transport.submitOperation(operation);
    const request = http.expectOne('/api/v1/sync/operations');

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(operation);
    request.flush(acceptedResult());

    await expect(resultPromise).resolves.toEqual(acceptedResult());
  });

  it('pulls a page and preserves an opaque cursor', async () => {
    const resultPromise = transport.pullChanges('cursor / with spaces');
    const request = http.expectOne(
      (candidate) =>
        candidate.url === '/api/v1/sync/changes' &&
        candidate.params.get('cursor') === 'cursor / with spaces',
    );

    expect(request.request.method).toBe('GET');
    request.flush(changePage());

    await expect(resultPromise).resolves.toEqual(changePage());
  });

  it('rejects invalid requests before making an HTTP call', async () => {
    const invalid = {
      ...createOperation(),
      kind: 'invalid',
    } as unknown as CreateTechnicalRecordOperation;

    await expect(transport.submitOperation(invalid)).rejects.toBeInstanceOf(
      SynchronizationBoundaryError,
    );
    http.expectNone('/api/v1/sync/operations');
    await expect(transport.pullChanges('')).rejects.toBeInstanceOf(SynchronizationBoundaryError);
    http.expectNone('/api/v1/sync/changes');
  });

  it.each([
    [400, { code: 'INVALID_REQUEST', message: 'invalid request' }],
    [
      404,
      {
        code: 'RECORD_NOT_FOUND',
        message: 'missing',
        operationId: operationId(),
        recordId: recordId(),
      },
    ],
    [
      409,
      {
        code: 'OPERATION_ID_REUSED',
        message: 'reused',
        operationId: operationId(),
      },
    ],
    [
      409,
      {
        code: 'RECORD_ALREADY_EXISTS',
        message: 'exists',
        operationId: operationId(),
        currentRecord: { recordId: recordId(), revision: '1', value: 'server' },
      },
    ],
    [
      409,
      {
        code: 'REVISION_CONFLICT',
        message: 'stale',
        operationId: operationId(),
        expectedRevision: '1',
        currentRecord: { recordId: recordId(), revision: '2', value: 'server' },
      },
    ],
  ])('maps a valid %s protocol error to a typed error', async (status, body) => {
    const resultPromise = transport.submitOperation(createOperation());
    const request = http.expectOne('/api/v1/sync/operations');
    request.flush(body, { status, statusText: 'Rejected' });

    await expect(resultPromise).rejects.toMatchObject({
      kind: 'protocol',
      status,
      body,
    });
    await expect(resultPromise).rejects.toBeInstanceOf(SynchronizationProtocolError);
  });

  it('rejects malformed success and error bodies', async () => {
    const successPromise = transport.submitOperation(createOperation());
    http.expectOne('/api/v1/sync/operations').flush({ outcome: 'accepted' });
    await expect(successPromise).rejects.toBeInstanceOf(SynchronizationUnexpectedResponseError);

    const errorPromise = transport.submitOperation(createOperation());
    http
      .expectOne('/api/v1/sync/operations')
      .flush({ code: 'INVALID_REQUEST' }, { status: 400, statusText: 'Rejected' });
    await expect(errorPromise).rejects.toBeInstanceOf(SynchronizationUnexpectedResponseError);
  });

  it('classifies network failures without retrying', async () => {
    const resultPromise = transport.submitOperation(createOperation());
    const request = http.expectOne('/api/v1/sync/operations');
    request.error(new ProgressEvent('error'));

    await expect(resultPromise).rejects.toBeInstanceOf(SynchronizationUnavailableError);
    http.expectNone('/api/v1/sync/operations');
  });
});

function operationId(): string {
  return '01890f3e-7c5a-7b12-8abc-0123456789ab';
}

function recordId(): string {
  return '01890f3e-7c5a-7b13-8abc-0123456789ab';
}

function createOperation(): CreateTechnicalRecordOperation {
  return { operationId: operationId(), recordId: recordId(), value: 'first value', kind: 'create' };
}

function acceptedResult() {
  return {
    outcome: 'accepted',
    operationId: operationId(),
    record: { recordId: recordId(), revision: '1', value: 'first value' },
    sequence: '1',
  };
}

function changePage() {
  return {
    changes: [
      {
        operationId: operationId(),
        record: { recordId: recordId(), revision: '1', value: 'first value' },
        sequence: '1',
      },
    ],
    nextCursor: 'opaque-cursor',
    hasMore: false,
  };
}
