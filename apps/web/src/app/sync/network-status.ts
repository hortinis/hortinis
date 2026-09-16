import { inject, Injectable, InjectionToken } from '@angular/core';

export interface NetworkStatus {
  isOnline(): boolean;
}

@Injectable({ providedIn: 'root' })
export class BrowserNetworkStatus implements NetworkStatus {
  isOnline(): boolean {
    return typeof navigator === 'undefined' || navigator.onLine;
  }
}

export const NETWORK_STATUS = new InjectionToken<NetworkStatus>('Hortinis network status', {
  providedIn: 'root',
  factory: () => inject(BrowserNetworkStatus),
});
