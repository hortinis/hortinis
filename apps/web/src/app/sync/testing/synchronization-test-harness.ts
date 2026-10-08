import 'fake-indexeddb/auto';

import { createEnvironmentInjector, EnvironmentInjector } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { HortinisDatabase } from '../../persistence/hortinis-database';
import { TechnicalRecordPersistence } from '../../persistence/technical-record-persistence';
import { SynchronizationRetryPersistence } from '../../persistence/synchronization-retry-persistence';
import type { OperationResult, TechnicalRecordOperation } from '../conformance';
import { NETWORK_STATUS, type NetworkStatus } from '../network-status';
import {
  SynchronizationCoordinator,
  SYNCHRONIZATION_COORDINATION_SCHEDULER,
  SYNCHRONIZATION_OWNER_ID,
} from '../synchronization-coordinator';
import {
  SYNCHRONIZATION_CLOCK,
  SYNCHRONIZATION_JITTER,
  SYNCHRONIZATION_SCHEDULER,
  type SynchronizationScheduler,
} from '../synchronization-runtime';
import type { SynchronizationTransport } from '../synchronization-transport';
import { SYNCHRONIZATION_TRANSPORT } from '../synchronization-transport.token';
import {
  TechnicalRecordSynchronizationService,
  type SynchronizationStatus,
} from '../technical-record-synchronization-service';

export const testOperation = {
  operationId: '00000000-0000-4000-8000-000000000001',
  recordId: '00000000-0000-4000-8000-000000000002',
  kind: 'create' as const,
  value: 'local value',
};
export const emptyPage = { changes: [], nextCursor: 'cursor', hasMore: false };
export const inertScheduler = { schedule: () => () => undefined };

export function accepted(operation: TechnicalRecordOperation): OperationResult {
  if (operation.kind === 'delete') throw new Error('Expected a live operation.');
  return {
    outcome: 'accepted',
    operationId: operation.operationId,
    sequence: '1',
    record: { recordId: operation.recordId, revision: '1', value: operation.value },
  };
}

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

export class ControlledSynchronizationScheduler implements SynchronizationScheduler {
  readonly tasks: { run: () => void; delay: number; cancelled: boolean }[] = [];
  schedule(run: () => void, delay: number): () => void {
    const task = { run, delay, cancelled: false };
    this.tasks.push(task);
    return () => {
      task.cancelled = true;
    };
  }
  runNext(clock: { value: number }): void {
    const task = this.tasks.find((entry) => !entry.cancelled);
    if (!task) throw new Error('No scheduled task.');
    task.cancelled = true;
    clock.value += task.delay;
    task.run();
  }
  delays(): number[] {
    return this.tasks.filter((task) => !task.cancelled).map((task) => task.delay);
  }
}

export class SynchronizationTestHarness {
  readonly database = new HortinisDatabase(`hortinis-h5-${crypto.randomUUID()}`);
  private readonly injectors: EnvironmentInjector[] = [];

  scope(
    transport: SynchronizationTransport,
    options: {
      clock?: { value: number };
      scheduler?: SynchronizationScheduler;
      network?: NetworkStatus;
      jitter?: (maximum: number) => number;
    } = {},
  ) {
    const clock = options.clock ?? { value: 0 };
    const injector = createEnvironmentInjector(
      [
        { provide: HortinisDatabase, useValue: this.database },
        TechnicalRecordPersistence,
        SynchronizationRetryPersistence,
        TechnicalRecordSynchronizationService,
        SynchronizationCoordinator,
        { provide: SYNCHRONIZATION_TRANSPORT, useValue: transport },
        { provide: NETWORK_STATUS, useValue: options.network ?? { isOnline: () => true } },
        { provide: SYNCHRONIZATION_CLOCK, useValue: { now: () => clock.value } },
        { provide: SYNCHRONIZATION_JITTER, useValue: { sample: options.jitter ?? (() => 0) } },
        { provide: SYNCHRONIZATION_SCHEDULER, useValue: options.scheduler ?? inertScheduler },
        { provide: SYNCHRONIZATION_COORDINATION_SCHEDULER, useValue: inertScheduler },
        { provide: SYNCHRONIZATION_OWNER_ID, useValue: crypto.randomUUID() },
      ],
      TestBed.inject(EnvironmentInjector),
    );
    this.injectors.push(injector);
    const service = injector.get(TechnicalRecordSynchronizationService);
    const trace: SynchronizationStatus[] = [service.status()];
    const set = service.status.set.bind(service.status);
    vi.spyOn(service.status, 'set').mockImplementation((status) => {
      trace.push(status);
      set(status);
    });
    return {
      service,
      trace,
      persistence: injector.get(TechnicalRecordPersistence),
      retries: injector.get(SynchronizationRetryPersistence),
      coordinator: injector.get(SynchronizationCoordinator),
      destroy: () => injector.destroy(),
    };
  }

  async close(): Promise<void> {
    this.injectors.splice(0).forEach((injector) => {
      if (!injector.destroyed) injector.destroy();
    });
    TestBed.resetTestingModule();
    this.database.close();
    await this.database.delete();
  }
}
