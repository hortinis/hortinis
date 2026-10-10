import { signal } from '@angular/core';
import { StorageSafetyService } from './storage/storage-safety-service';
import { type StoragePersistenceStatus } from './storage/storage-persistence';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

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
          provide: StorageSafetyService,
          useValue: {
            persistenceStatus: status,
            pendingStorageWarning: warning,
            outbox,
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

  it('should render the application name', async () => {
    const fixture = TestBed.createComponent(App);

    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h1')?.textContent?.trim()).toBe('Hortinis');
  });
});
