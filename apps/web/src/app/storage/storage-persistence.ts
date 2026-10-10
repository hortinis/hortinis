import { DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';

export type StoragePersistenceStatus = 'persistent' | 'best-effort' | 'unsupported';
export type BrowserStorage = Partial<Pick<StorageManager, 'persist' | 'persisted'>>;

export const BROWSER_STORAGE = new InjectionToken<BrowserStorage | undefined>('Browser storage', {
  providedIn: 'root',
  factory: () => (typeof navigator === 'undefined' ? undefined : navigator.storage),
});

@Injectable({ providedIn: 'root' })
export class StoragePersistence {
  private readonly storage = inject(BROWSER_STORAGE);
  private readonly state = signal<StoragePersistenceStatus>(
    typeof this.storage?.persist === 'function' ? 'best-effort' : 'unsupported',
  );
  private readonly initialized: Promise<void>;
  private requested = false;
  private destroyed = false;
  readonly status = this.state.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
    });
    this.initialized = this.checkStatus();
  }

  afterLocalCommit(): void {
    if (this.requested || this.destroyed) return;
    this.requested = true;
    void this.requestPersistence();
  }

  private async checkStatus(): Promise<void> {
    try {
      if (typeof this.storage?.persisted === 'function' && (await this.storage.persisted()))
        this.publish('persistent');
    } catch {
      // Unconfirmed persistence retains the conservative initial status.
    }
  }

  private async requestPersistence(): Promise<void> {
    await this.initialized;
    if (this.destroyed || this.status() === 'persistent') return;
    if (typeof this.storage?.persist !== 'function') return;
    try {
      this.publish((await this.storage.persist()) ? 'persistent' : 'best-effort');
    } catch {
      this.publish('best-effort');
    }
  }

  private publish(status: StoragePersistenceStatus): void {
    if (!this.destroyed) this.state.set(status);
  }
}
