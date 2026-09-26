import { inject, Injectable, InjectionToken } from '@angular/core';

export interface NetworkStatus {
  isOnline(): boolean;
  onOnline?(listener: () => void): () => void;
}

@Injectable({ providedIn: 'root' })
export class BrowserNetworkStatus implements NetworkStatus {
  isOnline(): boolean {
    return typeof navigator === 'undefined' || navigator.onLine;
  }

  onOnline(listener: () => void): () => void {
    if (typeof window === 'undefined') return () => undefined;
    window.addEventListener('online', listener, { once: true });
    return () => window.removeEventListener('online', listener);
  }
}

export const NETWORK_STATUS = new InjectionToken<NetworkStatus>('Hortinis network status', {
  providedIn: 'root',
  factory: () => inject(BrowserNetworkStatus),
});
