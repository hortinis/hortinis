import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HttpBindingTransport } from './http-binding-transport';
import { SynchronizationAccessError } from './binding-transport';
import { SynchronizationBoundaryError } from './synchronization-transport';
import { SYNC_API_BASE_URL } from './sync-api-config';
import { SAME_ORIGIN_CSRF } from '../security/same-origin-csrf.port';

const capabilities = {
  contractVersion: '1',
  serverInstanceId: '01890f3e-7c5a-7b12-8abc-0123456789ab',
  accessMode: 'single-user-no-auth',
  supportedBoundaries: ['generationless'],
  anchoredSnapshots: false,
};
const bootstrap = {
  contractVersion: '1',
  serverInstanceId: capabilities.serverInstanceId,
  identity: {
    authority: 'configured',
    accessMode: 'single-user-no-auth',
    synchronizationScopeId: '01890f3e-7c5a-7b13-8abc-0123456789ab',
  },
  population: 'empty',
  boundary: { kind: 'generationless' },
  expectation: 'synthetic-expectation',
};
const headers = { 'Cache-Control': 'no-store' };
const invalidCacheHeaders: Record<string, string>[] = [
  {},
  { 'Cache-Control': 'public, max-age=60' },
];

describe('binding HTTP adapter', () => {
  let transport: HttpBindingTransport;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: SAME_ORIGIN_CSRF,
          useValue: { csrfHeaders: async () => ({ 'X-CSRF-TOKEN': 'auth-token' }) },
        },
      ],
    });
    transport = TestBed.inject(HttpBindingTransport);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('reads implemented capabilities without submitting scope, account or content selectors', async () => {
    const result = transport.capabilities();
    const request = http.expectOne(`${transport.serverOrigin}/api/v1/server/capabilities`);
    expect(request.request.method).toBe('GET');
    expect(request.request.body).toBeNull();
    expect(request.request.params.keys()).toEqual([]);
    request.flush(capabilities, { headers });
    await expect(result).resolves.toEqual(capabilities);
  });

  it('reads authorized bootstrap and confirms only the closed empty expectation', async () => {
    const observed = transport.bootstrap();
    http.expectOne(`${transport.serverOrigin}/api/v1/sync/bootstrap`).flush(bootstrap, { headers });
    await expect(observed).resolves.toEqual(bootstrap);
    const body = { expectation: bootstrap.expectation, expectedPopulation: 'empty' as const };
    const confirmed = transport.confirm(body, 'single-user-no-auth');
    const request = http.expectOne(`${transport.serverOrigin}/api/v1/sync/bindings/confirm`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush(bootstrap, { headers });
    await expect(confirmed).resolves.toEqual(bootstrap);
  });

  it('attaches session CSRF to authenticated empty-binding confirmation', async () => {
    const body = { expectation: bootstrap.expectation, expectedPopulation: 'empty' as const };
    const confirmed = transport.confirm(body, 'single-user-auth');
    await Promise.resolve();
    const request = http.expectOne(`${transport.serverOrigin}/api/v1/sync/bindings/confirm`);
    expect(request.request.headers.get('X-CSRF-TOKEN')).toBe('auth-token');
    request.flush(bootstrap, { headers });
    await expect(confirmed).resolves.toEqual(bootstrap);
  });

  it('reobserves retained expectations only in the non-authorizing header', async () => {
    const observed = transport.bootstrap('synthetic-expectation');
    const request = http.expectOne(`${transport.serverOrigin}/api/v1/sync/bootstrap`);
    expect(request.request.headers.get('Sync-Expectation')).toBe('synthetic-expectation');
    expect(request.request.params.keys()).toEqual([]);
    expect(request.request.body).toBeNull();
    request.flush(bootstrap, { headers });
    await expect(observed).resolves.toEqual(bootstrap);
  });

  it.each(invalidCacheHeaders)(
    'denies cacheable discovery instead of falling back',
    async (invalidHeaders) => {
      const observed = transport.capabilities();
      const failure = expect(observed).rejects.toBeInstanceOf(SynchronizationBoundaryError);
      http
        .expectOne(`${transport.serverOrigin}/api/v1/server/capabilities`)
        .flush(capabilities, { headers: invalidHeaders });
      await failure;
    },
  );

  it('rejects extra protected discovery fields without retaining raw diagnostics', async () => {
    const observed = transport.capabilities();
    const failure = expect(observed).rejects.toMatchObject({
      message: 'The binding response is invalid.',
      details: undefined,
    });
    http
      .expectOne(`${transport.serverOrigin}/api/v1/server/capabilities`)
      .flush({ ...capabilities, accountId: 'private-account-canary' }, { headers });
    await failure;
  });

  it('preserves generic access denial as a typed closed error', async () => {
    const observed = transport.bootstrap();
    const failure = expect(observed).rejects.toBeInstanceOf(SynchronizationAccessError);
    http
      .expectOne(`${transport.serverOrigin}/api/v1/sync/bootstrap`)
      .flush(
        { code: 'ACCESS_DENIED', message: 'Access is denied.' },
        { status: 403, statusText: 'Forbidden', headers },
      );
    await failure;
  });

  it('rejects a foreign configured destination before issuing a request', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SYNC_API_BASE_URL, useValue: 'https://foreign.example/api/v1/sync' },
        {
          provide: SAME_ORIGIN_CSRF,
          useValue: { csrfHeaders: async () => ({ 'X-CSRF-TOKEN': 'auth-token' }) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    expect(() => TestBed.inject(HttpBindingTransport)).toThrow(
      'The binding destination is unsupported.',
    );
    http.expectNone(() => true);
  });
});
