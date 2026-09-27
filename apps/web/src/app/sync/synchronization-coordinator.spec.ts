import 'fake-indexeddb/auto';

import { TestBed } from '@angular/core/testing';
import Dexie from 'dexie';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import {
  SYNCHRONIZATION_CLOCK,
  type SynchronizationClock,
  type SynchronizationScheduler,
} from './synchronization-runtime';
import {
  SYNCHRONIZATION_OWNER_ID,
  SYNCHRONIZATION_COORDINATION_SCHEDULER,
  SynchronizationCoordinator,
} from './synchronization-coordinator';

describe('cross-tab synchronization coordination', () => {
  const databases: Dexie[] = [];

  afterEach(async () => {
    TestBed.resetTestingModule();
    await Promise.all(
      databases.splice(0).map(async (database) => {
        database.close();
        await database.delete();
      }),
    );
  });

  it('transfers an expired lease without allowing the former owner to release the successor', async () => {
    const database = track(new HortinisDatabase(databaseName()));
    const clock = new MutableClock(1_000);
    const first = coordinator(database, clock, 'first-tab');
    const firstAttempt = await first.tryAcquire();
    expect(firstAttempt.acquired).toBe(true);

    TestBed.resetTestingModule();
    const second = coordinator(database, clock, 'second-tab');
    await expect(second.tryAcquire()).resolves.toEqual({ acquired: false, retryAt: 16_000 });

    clock.time = 16_001;
    const secondAttempt = await second.tryAcquire();
    expect(secondAttempt).toMatchObject({
      acquired: true,
      lease: { ownerId: 'second-tab', fencingToken: 2 },
    });
    if (!firstAttempt.acquired || !secondAttempt.acquired) throw new Error('Expected leases.');

    await first.release(firstAttempt.lease);
    await expect(database.synchronizationLeases.get('technical-records')).resolves.toMatchObject({
      ownerId: 'second-tab',
      fencingToken: 2,
    });
  });

  it('rejects a late page when another owner already advanced the cursor', async () => {
    const database = track(new HortinisDatabase(databaseName()));
    const persistence = persistenceFor(database);
    await persistence.commitPulledPage({
      changes: [],
      nextCursor: 'cursor-one',
      hasMore: false,
    });
    await persistence.commitPulledPage({
      changes: [],
      nextCursor: 'cursor-two',
      hasMore: false,
    });

    await expect(
      persistence.commitPulledPage(
        { changes: [], nextCursor: 'stale-cursor', hasMore: false },
        { expectedCursor: 'cursor-one' },
      ),
    ).rejects.toThrow('ownership changed');
    await expect(persistence.synchronizationCursor()).resolves.toBe('cursor-two');
  });

  function coordinator(
    database: HortinisDatabase,
    clock: SynchronizationClock,
    ownerId: string,
  ): SynchronizationCoordinator {
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: SYNCHRONIZATION_CLOCK, useValue: clock },
        { provide: SYNCHRONIZATION_COORDINATION_SCHEDULER, useValue: inertScheduler },
        { provide: SYNCHRONIZATION_OWNER_ID, useValue: ownerId },
      ],
    });
    return TestBed.inject(SynchronizationCoordinator);
  }

  function persistenceFor(database: HortinisDatabase): TechnicalRecordPersistence {
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: database }],
    });
    return TestBed.inject(TechnicalRecordPersistence);
  }

  function track<T extends Dexie>(database: T): T {
    databases.push(database);
    return database;
  }
});

class MutableClock implements SynchronizationClock {
  constructor(public time: number) {}

  now(): number {
    return this.time;
  }
}

const inertScheduler: SynchronizationScheduler = {
  schedule: () => () => undefined,
};

function databaseName(): string {
  return `hortinis-coordination-${crypto.randomUUID()}`;
}
