import { inject, Injectable, InjectionToken } from '@angular/core';

export interface SynchronizationClock {
  now(): number;
}

export interface SynchronizationJitter {
  sample(maxDelayMilliseconds: number): number;
}

export interface SynchronizationScheduler {
  schedule(task: () => void, delayMilliseconds: number): () => void;
}

@Injectable({ providedIn: 'root' })
export class BrowserSynchronizationClock implements SynchronizationClock {
  now(): number {
    return Date.now();
  }
}

@Injectable({ providedIn: 'root' })
export class FullSynchronizationJitter implements SynchronizationJitter {
  sample(maxDelayMilliseconds: number): number {
    return Math.floor(Math.random() * (maxDelayMilliseconds + 1));
  }
}

@Injectable({ providedIn: 'root' })
export class BrowserSynchronizationScheduler implements SynchronizationScheduler {
  schedule(task: () => void, delayMilliseconds: number): () => void {
    const timeout = setTimeout(task, delayMilliseconds);
    return () => clearTimeout(timeout);
  }
}

export const SYNCHRONIZATION_CLOCK = new InjectionToken<SynchronizationClock>(
  'Synchronization clock',
  { providedIn: 'root', factory: () => inject(BrowserSynchronizationClock) },
);

export const SYNCHRONIZATION_JITTER = new InjectionToken<SynchronizationJitter>(
  'Synchronization jitter',
  { providedIn: 'root', factory: () => inject(FullSynchronizationJitter) },
);

export const SYNCHRONIZATION_SCHEDULER = new InjectionToken<SynchronizationScheduler>(
  'Synchronization scheduler',
  { providedIn: 'root', factory: () => inject(BrowserSynchronizationScheduler) },
);
