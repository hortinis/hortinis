import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BINDING_TRANSPORT } from '../sync/binding-transport.token';
import { AUTHENTICATION_HTTP } from './authentication-http.port';
import { AuthenticationHttpClient } from './authentication-http-client';
import { AuthenticationBrowserService } from './authentication-browser.service';
import { AuthenticationCookieGate } from '../sync/authentication-cookie-gate';
import { LocalBindingExchangePersistence } from '../persistence/local-binding-exchange-persistence';

const headers = { 'Cache-Control': 'no-store', 'Content-Type': 'application/json' };
const account = '00000000-0000-4000-8000-000000000001';
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('R5 native Angular transport and browser state', () => {
  let http: HttpTestingController;
  let client: AuthenticationHttpClient;
  let service: AuthenticationBrowserService;
  const capabilities = vi.fn(async () => ({ accessMode: 'single-user-auth' }));
  const persistence = {
    snapshot: vi.fn(
      async (): Promise<{ binding: { identity: { accessMode: string } } | undefined }> => ({
        binding: { identity: { accessMode: 'single-user-auth' } },
      }),
    ),
    pauseAuthentication: vi.fn(async () => undefined),
    resolveAuthenticationPause: vi.fn(async () => undefined),
  };
  beforeEach(() => {
    localStorage.clear();
    persistence.snapshot.mockResolvedValue({
      binding: { identity: { accessMode: 'single-user-auth' } },
    });
    capabilities.mockResolvedValue({ accessMode: 'single-user-auth' });
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: (_name: string, _options: unknown, work: () => Promise<void>) => work(),
      },
    });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AUTHENTICATION_HTTP, useExisting: AuthenticationHttpClient },
        { provide: LocalBindingExchangePersistence, useValue: persistence },
        { provide: BINDING_TRANSPORT, useValue: { capabilities } },
      ],
    });
    client = TestBed.inject(AuthenticationHttpClient);
    service = TestBed.inject(AuthenticationBrowserService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    Reflect.deleteProperty(navigator, 'locks');
  });
  function flush(path: string, body: object, status = 200) {
    const request = http.expectOne(`${location.origin}/api/v1/auth${path}`);
    request.flush(body, { headers, status, statusText: 'Synthetic' });
    return request;
  }
  async function csrf() {
    await tick();
    flush('/csrf', { token: 'native_masked+value/with=padding-', headerName: 'X-CSRF-TOKEN' });
    await tick();
  }

  it('makes no request during local-only construction', () => {
    http.expectNone(() => true);
  });
  it('bootstraps masked CSRF only in memory', async () => {
    const work = client.refreshCsrf();
    await csrf();
    await work;
    expect(await client.csrfHeaders()).toEqual({
      'X-CSRF-TOKEN': 'native_masked+value/with=padding-',
    });
    expect(localStorage.length).toBe(0);
  });
  it('does not repopulate CSRF after a boundary changed', async () => {
    const work = client.refreshCsrf();
    const failed = expect(work).rejects.toThrow();
    TestBed.inject(AuthenticationCookieGate).markPending();
    await csrf();
    await failed;
  });
  it('rejects a cacheable or non-JSON status', async () => {
    const work = service.open();
    const failed = expect(work).rejects.toThrow();
    http.expectOne(`${location.origin}/api/v1/auth/session`).flush({ state: 'anonymous' });
    await failed;
    expect(service.view()).not.toBe('authenticated');
  });
  it('rejects lone surrogates before the form encoder can replace password text', async () => {
    await expect(
      client.login('owner@example.test', 'synthetic' + String.fromCharCode(0xd800)),
    ).rejects.toThrow();
    http.expectNone(() => true);
  });

  it('uses exact native form encoding and refreshes after login', async () => {
    const work = service.login('Gardener+tag@Example.test', ' exact +&=🌱 password ');
    await csrf();
    const request = http.expectOne(`${location.origin}/api/v1/auth/login`);
    expect(request.request.body).toBe(
      'email=Gardener%2Btag%40Example.test&password=+exact+%2B%26%3D%F0%9F%8C%B1+password+',
    );
    expect(request.request.headers.get('Content-Type')).toContain(
      'application/x-www-form-urlencoded',
    );
    expect(request.request.headers.has('X-CSRF-TOKEN')).toBe(true);
    request.flush({ state: 'authenticated', accountId: account }, { headers });
    await tick();
    flush('/session', { state: 'authenticated', accountId: account });
    await csrf();
    await work;
    expect(service.view()).toBe('authenticated');
    expect(JSON.stringify(localStorage)).not.toContain('password');
  });
  it('accepts the generic 202 acknowledgement', async () => {
    const work = service.requestReset('unknown@example.test');
    await csrf();
    flush('/password-reset/request', { accepted: true }, 202);
    await work;
    expect(service.notice()).toContain('does not confirm');
  });
  it('never retries a CSRF-rejected unsafe request', async () => {
    const work = service.requestReset('unknown@example.test');
    const failed = expect(work).rejects.toThrow();
    await csrf();
    flush('/password-reset/request', { code: 'ACCESS_DENIED', message: 'Access is denied.' }, 403);
    await csrf();
    await failed;
    http.expectNone(`${location.origin}/api/v1/auth/password-reset/request`);
    expect(service.view()).toBe('resolution-required');
  });
  it('ignores a delayed denial after a newer login boundary instead of pausing it', async () => {
    const work = service.requestReset('unknown@example.test');
    const failed = expect(work).rejects.toThrow();
    await csrf();
    const gate = TestBed.inject(AuthenticationCookieGate);
    gate.markComplete(gate.markPending());
    const current = gate.revision();
    flush('/password-reset/request', { code: 'ACCESS_DENIED', message: 'Access is denied.' }, 403);
    await failed;
    expect(gate.revision()).toBe(current);
    expect(gate.paused()).toBe(false);
    http.expectNone(`${location.origin}/api/v1/auth/csrf`);
  });

  it('keeps offline logout paused across explicit status resolution without replay', async () => {
    const initial = service.open();
    flush('/session', { state: 'authenticated', accountId: account });
    await csrf();
    await initial;
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    const localLogout = service.logout();
    online.mockReturnValue(true);
    await localLogout;
    expect(service.view()).toBe('resolution-required');
    http.expectNone(`${location.origin}/api/v1/auth/logout`);
    online.mockRestore();
    const opened = service.open();
    flush('/session', { state: 'authenticated', accountId: account });
    await csrf();
    await opened;
    expect(TestBed.inject(AuthenticationCookieGate).paused()).toBe(true);
    http.expectNone(`${location.origin}/api/v1/auth/logout`);
  });
  it('cancels logout if connectivity was lost while the CSRF bootstrap was pending', async () => {
    const opened = service.open();
    flush('/session', { state: 'authenticated', accountId: account });
    await csrf();
    await opened;
    const work = service.logout();
    await tick();
    dispatchEvent(new Event('offline'));
    await csrf();
    // Uncertain completion may refresh proof, but must never dispatch/replay native logout.
    await csrf();
    await work;
    http.expectNone(`${location.origin}/api/v1/auth/logout`);
    expect(TestBed.inject(AuthenticationCookieGate).paused()).toBe(true);
    expect(service.notice()).toContain('server session may still exist');
  });
  it.each([true, false])(
    'does not fence no-auth synchronization (retained binding: %s)',
    async (bound) => {
      persistence.snapshot.mockResolvedValue({
        binding: bound ? { identity: { accessMode: 'single-user-no-auth' } } : undefined,
      });
      capabilities.mockResolvedValue({ accessMode: 'single-user-no-auth' });
      const opened = service.open();
      const failed = expect(opened).rejects.toThrow();
      flush(
        '/session',
        { code: 'ACCESS_UNAVAILABLE', message: 'The access service is unavailable.' },
        503,
      );
      await failed;
      await expect(service.logout()).rejects.toThrow();
      const gate = TestBed.inject(AuthenticationCookieGate);
      expect(gate.revision()).toBe('');
      expect(() => gate.assertExchangeAllowed()).not.toThrow();
      http.expectNone(`${location.origin}/api/v1/auth/logout`);
    },
  );
  it('rejects a superseded mutation before CSRF headers finish resolving', async () => {
    const gate = TestBed.inject(AuthenticationCookieGate);
    const csrf = vi.spyOn(client, 'csrfHeaders').mockImplementation(async () => {
      gate.markPending();
      return { 'X-CSRF-TOKEN': 'synthetic_masked' };
    });
    await expect(client.login('owner@example.test', 'synthetic password')).rejects.toThrow();
    await expect(client.post('/password', { newPassword: 'synthetic password' })).rejects.toThrow();
    await expect(client.postBodyless('/logout')).rejects.toThrow();
    http.expectNone(() => true);
    csrf.mockRestore();
  });
  it('does not publish an old intent after an asynchronous binding read', async () => {
    const gate = TestBed.inject(AuthenticationCookieGate);
    persistence.snapshot.mockImplementationOnce(async () => {
      gate.markComplete(gate.markPending());
      return { binding: { identity: { accessMode: 'single-user-auth' } } };
    });
    await expect(service.logout()).rejects.toThrow();
    expect(gate.paused()).toBe(false);
    http.expectNone(() => true);
  });
  it('uses native bodyless logout and remains anonymous after refresh', async () => {
    const opened = service.open();
    flush('/session', { state: 'authenticated', accountId: account });
    await csrf();
    await opened;
    const work = service.logout();
    await csrf();
    const request = flush('/logout', { completed: true });
    expect(request.request.body).toBeNull();
    await csrf();
    await work;
    expect(service.view()).toBe('anonymous');
  });
  it.each(['invitation', 'reset', 'change'])('does not login after %s', async (kind) => {
    const work =
      kind === 'invitation'
        ? service.acceptInvitation('A'.repeat(43), 'new synthetic password')
        : kind === 'reset'
          ? service.completeReset('A'.repeat(43), 'new synthetic password')
          : service.changePassword('current', 'new synthetic password');
    await csrf();
    flush(
      kind === 'invitation'
        ? '/invitations/accept'
        : kind === 'reset'
          ? '/password-reset/complete'
          : '/password',
      { completed: true },
    );
    await csrf();
    await work;
    expect(service.view()).toBe('anonymous');
    http.expectNone(`${location.origin}/api/v1/auth/login`);
  });
  it('fails closed and notifies other tabs when the durable pause cannot be written', async () => {
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error();
    });
    const notify = vi.spyOn(BroadcastChannel.prototype, 'postMessage');
    await expect(service.logout()).rejects.toThrow();
    expect(TestBed.inject(AuthenticationCookieGate).paused()).toBe(true);
    expect(notify).toHaveBeenCalledWith('unavailable');
    http.expectNone(() => true);
    write.mockRestore();
    notify.mockRestore();
  });

  it('rejects a delayed login after another boundary without releasing the pause', async () => {
    const work = service.login('owner@example.test', 'synthetic password');
    const failed = expect(work).rejects.toThrow();
    await csrf();
    TestBed.inject(AuthenticationCookieGate).markPending();
    flush('/login', { state: 'authenticated', accountId: account });
    await failed;
    expect(service.view()).not.toBe('authenticated');
    expect(TestBed.inject(AuthenticationCookieGate).paused()).toBe(true);
  });
});
