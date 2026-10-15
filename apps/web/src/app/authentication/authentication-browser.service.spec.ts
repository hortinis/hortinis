import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AUTHENTICATION_HTTP } from './authentication-http.port';
import { AuthenticationHttpClient } from './authentication-http-client';
import { AuthenticationBrowserService } from './authentication-browser.service';
import { BrowserWebAuthnAdapter } from './browser-webauthn.adapter';
import { AuthenticationCookieGate } from '../sync/authentication-cookie-gate';
import { SynchronizationPausedError } from '../sync/synchronization-transport';

async function allowHttpWorkflowToContinue(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('browser authentication client', () => {
  let service: AuthenticationBrowserService;
  let http: HttpTestingController;
  const webAuthn = { assert: vi.fn().mockResolvedValue({ assertion: 'test-proof' }) };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AUTHENTICATION_HTTP, useExisting: AuthenticationHttpClient },
        { provide: BrowserWebAuthnAdapter, useValue: webAuthn },
      ],
    });
    service = TestBed.inject(AuthenticationBrowserService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('keeps application startup offline until account access is explicitly opened', async () => {
    http.expectNone(`${location.origin}/api/v1/auth/session`);
    const opened = service.open();
    const request = http.expectOne(`${location.origin}/api/v1/auth/session`);
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ state: 'anonymous' }, { headers: { 'Cache-Control': 'no-store' } });
    await opened;
    expect(service.view()).toBe('anonymous');
  });

  it('does not accept an account response that could be cached', async () => {
    const opened = service.open();
    http.expectOne(`${location.origin}/api/v1/auth/session`).flush({ state: 'anonymous' });
    await opened;
    expect(service.view()).toBe('unavailable');
    expect(service.notice()).toContain('unavailable');
  });

  it('rejects a non-string CSRF token rather than coercing it into a header', async () => {
    const refreshed = TestBed.inject(AuthenticationHttpClient).refreshCsrf();
    const rejected = expect(refreshed).rejects.toThrow('invalid security token');
    http
      .expectOne(`${location.origin}/api/v1/auth/csrf`)
      .flush(
        { token: 123, headerName: 'X-CSRF-TOKEN' },
        { headers: { 'Cache-Control': 'no-store' } },
      );
    await rejected;
  });

  it('keeps password and passkey login in one coordinated CSRF-bound workflow', async () => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: (_name: string, _options: unknown, operation: () => Promise<void>) => operation(),
      },
    });
    try {
      const login = service.login('owner', 'password held only for this call');
      const csrf = http.expectOne(`${location.origin}/api/v1/auth/csrf`);
      expect(csrf.request.method).toBe('GET');
      csrf.flush(
        { token: 'fc172f68-a11e-4c9e-91d8-1f1b44693789', headerName: 'X-CSRF-TOKEN' },
        { headers: { 'Cache-Control': 'no-store' } },
      );
      await allowHttpWorkflowToContinue();

      const password = http.expectOne(`${location.origin}/api/v1/auth/login/password`);
      expect(password.request.withCredentials).toBe(true);
      expect(password.request.headers.get('X-CSRF-TOKEN')).toBe(
        'fc172f68-a11e-4c9e-91d8-1f1b44693789',
      );
      expect(password.request.body).toEqual({
        login: 'owner',
        password: 'password held only for this call',
      });
      password.flush({ challenge: 'challenge' }, { headers: { 'Cache-Control': 'no-store' } });
      await allowHttpWorkflowToContinue();

      const rotatedCsrf = http.expectOne(`${location.origin}/api/v1/auth/csrf`);
      rotatedCsrf.flush(
        { token: 'f6c8a5f4-45e0-4278-9d7a-5fde43231c0e', headerName: 'X-CSRF-TOKEN' },
        { headers: { 'Cache-Control': 'no-store' } },
      );
      await allowHttpWorkflowToContinue();

      const passkey = http.expectOne(`${location.origin}/api/v1/auth/login/passkey`);
      expect(passkey.request.headers.get('X-CSRF-TOKEN')).toBe(
        'f6c8a5f4-45e0-4278-9d7a-5fde43231c0e',
      );
      expect(passkey.request.body).toEqual({ assertion: 'test-proof' });
      passkey.flush({}, { headers: { 'Cache-Control': 'no-store' } });
      await allowHttpWorkflowToContinue();

      const finalCsrf = http.expectOne(`${location.origin}/api/v1/auth/csrf`);
      finalCsrf.flush(
        { token: 'C'.repeat(43), headerName: 'X-CSRF-TOKEN' },
        { headers: { 'Cache-Control': 'no-store' } },
      );
      await allowHttpWorkflowToContinue();

      const status = http.expectOne(`${location.origin}/api/v1/auth/session`);
      status.flush(
        { state: 'authenticated', logoutTarget: 'non-authorizing-target' },
        { headers: { 'Cache-Control': 'no-store' } },
      );
      await login;
      expect(webAuthn.assert).toHaveBeenCalledWith({ challenge: 'challenge' });
      expect(service.view()).toBe('authenticated');
    } finally {
      Reflect.deleteProperty(navigator, 'locks');
    }
  });

  it('fails closed when it cannot persist the cross-tab logout marker', async () => {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: (_name: string, _options: unknown, operation: () => Promise<void>) => operation(),
      },
    });
    const opened = service.open();
    http
      .expectOne(`${location.origin}/api/v1/auth/session`)
      .flush(
        { state: 'authenticated', logoutTarget: 'logout-target' },
        { headers: { 'Cache-Control': 'no-store' } },
      );
    await opened;
    const originalSetItem = Storage.prototype.setItem;
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key,
      value,
    ) {
      if (key === 'hortinis.pending-authentication-logout.v1') {
        throw new DOMException('Storage is full.', 'QuotaExceededError');
      }
      return originalSetItem.call(this, key, value);
    });
    try {
      await expect(service.logout()).rejects.toThrow('Pending sign-out could not be stored.');
      expect(setItem).toHaveBeenCalledOnce();
      expect(service.view()).toBe('pending-logout');
      expect(service.notice()).toContain('paused in open tabs');
      expect(() => TestBed.inject(AuthenticationCookieGate).assertExchangeAllowed()).toThrow(
        SynchronizationPausedError,
      );
      http.expectNone(`${location.origin}/api/v1/auth/logout`);
    } finally {
      setItem.mockRestore();
      Reflect.deleteProperty(navigator, 'locks');
    }
  });
});
