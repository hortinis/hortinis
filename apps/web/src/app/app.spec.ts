import { SynchronizationBindingService } from './sync/synchronization-binding-service';
import { TechnicalRecordSynchronizationService } from './sync/technical-record-synchronization-service';
import { signal } from '@angular/core';
import { StorageSafetyService } from './storage/storage-safety-service';
import { type StoragePersistenceStatus } from './storage/storage-persistence';
import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { AuthenticationBrowserService } from './authentication/authentication-browser.service';

describe('App', () => {
  const status = signal<StoragePersistenceStatus>('best-effort');
  const warning = signal(false);
  const outbox = signal({ status: 'available', count: 0 });
  beforeEach(async () => {
    status.set('best-effort');
    warning.set(false);
    outbox.set({ status: 'available', count: 0 });
    await TestBed.configureTestingModule({
      providers: [
        {
          provide: SynchronizationBindingService,
          useValue: {
            state: signal('offline'),
            connecting: signal(false),
            failedConnection: signal(false),
            connect: async () => 'offline',
          },
        },
        {
          provide: TechnicalRecordSynchronizationService,
          useValue: { retryNow: async () => undefined },
        },
        {
          provide: StorageSafetyService,
          useValue: {
            persistenceStatus: status,
            pendingStorageWarning: warning,
            outbox,
          },
        },
        {
          provide: AuthenticationBrowserService,
          useValue: {
            view: signal('closed'),
            notice: signal(''),
            recoveryCodes: signal([]),
            open: async () => undefined,
            login: async () => undefined,
            setup: async () => undefined,
            logout: async () => undefined,
            stepUp: async () => undefined,
            changePassword: async () => undefined,
            addPasskey: async () => undefined,
            regenerateRecoveryCodes: async () => undefined,
            revokeCredential: async () => undefined,
          },
        },
      ],
      imports: [App],
    }).compileComponents();
  });

  it.each(['persistent', 'best-effort', 'unsupported'] as const)(
    'renders storage status %s',
    async (value) => {
      status.set(value);
      const fixture = TestBed.createComponent(App);
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      const text = element.querySelector('[role="status"]')?.textContent;
      expect(text).toContain(
        value === 'persistent'
          ? 'enabled'
          : value === 'unsupported'
            ? 'unsupported'
            : 'not confirmed',
      );
      expect(element.querySelector('[role="alert"]')).toBeNull();
    },
  );

  it('renders and clears the pending-work warning', async () => {
    warning.set(true);
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'Unsynchronized changes',
    );
    warning.set(false);
    await fixture.whenStable();
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });

  it('renders an unavailable observation distinctly', async () => {
    outbox.set({ status: 'unavailable', count: 0 });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent,
    ).toContain('could not be checked');
  });

  it('should create the application', () => {
    const fixture = TestBed.createComponent(App);

    expect(fixture.componentInstance).toBeTruthy();
  });

  describe.each([
    { method: 'changePassword', run: (app: App) => app.changePassword() },
    { method: 'addPasskey', run: (app: App) => app.addPasskey() },
    { method: 'regenerateRecoveryCodes', run: (app: App) => app.regenerateRecoveryCodes() },
    { method: 'revokeCredential', run: (app: App) => app.revokeCredential('listed-credential') },
  ] as const)('$method', ({ method, run }) => {
    it.each(['success', 'proof failure', 'change failure'])(
      'requires both factors and clears submitted secrets after %s',
      async (outcome) => {
        const app = TestBed.createComponent(App).componentInstance;
        const auth = TestBed.inject(AuthenticationBrowserService);
        const order: string[] = [];
        vi.spyOn(auth, 'stepUp').mockImplementation(async (password) => {
          expect(password).toBe('transient current password');
          order.push('proof');
          if (outcome === 'proof failure') throw new Error('Synthetic proof failure');
        });
        vi.spyOn(auth, method).mockImplementation(async () => {
          order.push('change');
          if (outcome === 'change failure') throw new Error('Synthetic change failure');
        });
        app.currentPassword = 'transient current password';
        app.replacementPassword = 'transient replacement password';
        await run(app);
        expect(order).toEqual(outcome === 'proof failure' ? ['proof'] : ['proof', 'change']);
        expect(app.currentPassword).toBe('');
        expect(app.replacementPassword).toBe(
          method === 'changePassword' ? '' : 'transient replacement password',
        );
        if (outcome !== 'success') expect(auth.notice()).toContain('try again');
      },
    );
  });

  it('should render the application name', async () => {
    const fixture = TestBed.createComponent(App);

    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h1')?.textContent?.trim()).toBe('Hortinis');
  });
});
