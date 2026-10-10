import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { BROWSER_STORAGE, StoragePersistence, type BrowserStorage } from './storage-persistence';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}

describe('StoragePersistence', () => {
  afterEach(() => TestBed.resetTestingModule());

  function service(storage: BrowserStorage | undefined) {
    TestBed.configureTestingModule({
      providers: [{ provide: BROWSER_STORAGE, useValue: storage }],
    });
    return TestBed.inject(StoragePersistence);
  }

  it('checks existing permission on startup without requesting it', async () => {
    const persist = vi.fn(async () => false);
    const persistence = service({ persisted: async () => true, persist });
    await vi.waitFor(() => expect(persistence.status()).toBe('persistent'));
    persistence.afterLocalCommit();
    await Promise.resolve();
    expect(persist).not.toHaveBeenCalled();
  });

  it.each([true, false])('publishes a request result of %s once per session', async (granted) => {
    const persist = vi.fn(async () => granted);
    const persistence = service({ persisted: async () => false, persist });
    expect(persist).not.toHaveBeenCalled();
    persistence.afterLocalCommit();
    persistence.afterLocalCommit();
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
    expect(persistence.status()).toBe(granted ? 'persistent' : 'best-effort');
    persistence.afterLocalCommit();
    await Promise.resolve();
    expect(persist).toHaveBeenCalledOnce();
  });

  it.each([undefined, {}, { persisted: async () => false }])(
    'handles missing request capabilities',
    (storage) => {
      const persistence = service(storage);
      expect(persistence.status()).toBe('unsupported');
      expect(() => persistence.afterLocalCommit()).not.toThrow();
    },
  );

  it('can confirm existing persistence even without a request capability', async () => {
    const persistence = service({ persisted: async () => true });
    await vi.waitFor(() => expect(persistence.status()).toBe('persistent'));
  });

  it('requests when the status-check capability is missing', async () => {
    const persistence = service({ persist: async () => true });
    persistence.afterLocalCommit();
    await vi.waitFor(() => expect(persistence.status()).toBe('persistent'));
  });

  it.each(['throw', 'reject'])('contains %s failures in both browser methods', async (mode) => {
    const failure = () => {
      if (mode === 'throw') throw new Error('Browser permission failure');
      return Promise.reject(new Error('Browser permission failure'));
    };
    const persist = vi.fn(failure);
    const persistence = service({ persisted: failure, persist });
    persistence.afterLocalCommit();
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
    expect(persistence.status()).toBe('best-effort');
  });

  it('preserves the storage receiver', async () => {
    const storage = {
      granted: false,
      async persisted() {
        return this.granted;
      },
      async persist() {
        this.granted = true;
        return this.granted;
      },
    };
    const persistence = service(storage);
    persistence.afterLocalCommit();
    await vi.waitFor(() => expect(persistence.status()).toBe('persistent'));
    expect(storage.granted).toBe(true);
  });

  it('orders a commit during startup behind the check and publishes the request last', async () => {
    const check = deferred<boolean>();
    const request = deferred<boolean>();
    const persist = vi.fn(() => request.promise);
    const persistence = service({ persisted: () => check.promise, persist });
    persistence.afterLocalCommit();
    expect(persist).not.toHaveBeenCalled();
    check.resolve(false);
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
    expect(persistence.status()).toBe('best-effort');
    request.resolve(true);
    await vi.waitFor(() => expect(persistence.status()).toBe('persistent'));
  });

  it('does not request after destruction while checking permission', async () => {
    const check = deferred<boolean>();
    const persist = vi.fn(async () => true);
    const persistence = service({ persisted: () => check.promise, persist });
    persistence.afterLocalCommit();
    TestBed.resetTestingModule();
    check.resolve(false);
    await Promise.resolve();
    await Promise.resolve();
    persistence.afterLocalCommit();
    expect(persist).not.toHaveBeenCalled();
  });

  it('ignores a permission result after destruction', async () => {
    const request = deferred<boolean>();
    const persist = vi.fn(() => request.promise);
    const persistence = service({ persist });
    persistence.afterLocalCommit();
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
    TestBed.resetTestingModule();
    request.resolve(true);
    await Promise.resolve();
    expect(persistence.status()).toBe('best-effort');
  });
});
