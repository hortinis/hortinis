import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { TechnicalRecordPersistence } from '../persistence/technical-record-persistence';
import { StoragePersistence, type StoragePersistenceStatus } from './storage-persistence';
import { StorageSafetyService } from './storage-safety-service';

describe('StorageSafetyService', () => {
  const databases: HortinisDatabase[] = [];
  const permission = signal<StoragePersistenceStatus>('best-effort');
  const operation = {
    operationId: 'pending',
    recordId: 'record',
    kind: 'create' as const,
    value: 'local',
  };

  afterEach(async () => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
    for (const database of databases.splice(0)) {
      database.close();
      await database.delete();
    }
    permission.set('best-effort');
  });

  function setup() {
    const database = new HortinisDatabase(`hortinis-h7-${crypto.randomUUID()}`);
    databases.push(database);
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: StoragePersistence, useValue: { status: permission.asReadonly() } },
      ],
    });
    return { database, service: TestBed.inject(StorageSafetyService) };
  }

  it('observes saved work and changes from another database connection', async () => {
    const { database, service } = setup();
    expect(service.outbox()).toEqual({ status: 'loading', count: null });
    await vi.waitFor(() => expect(service.outbox()).toEqual({ status: 'available', count: 0 }));
    const other = new HortinisDatabase(database.name);
    try {
      await other.outboxOperations.add(operation);
      await vi.waitFor(() => expect(service.pendingStorageWarning()).toBe(true));
      permission.set('persistent');
      expect(service.pendingStorageWarning()).toBe(false);
      permission.set('unsupported');
      expect(service.pendingStorageWarning()).toBe(true);
      await other.outboxOperations.clear();
      await vi.waitFor(() => expect(service.outbox().count).toBe(0));
      expect(service.pendingStorageWarning()).toBe(false);
    } finally {
      other.close();
    }
  });

  it('reports persisted work after reopening', async () => {
    const database = new HortinisDatabase(`hortinis-h7-${crypto.randomUUID()}`);
    databases.push(database);
    await database.outboxOperations.add(operation);
    database.close();
    const reopened = new HortinisDatabase(database.name);
    databases[databases.length - 1] = reopened;
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: reopened },
        { provide: StoragePersistence, useValue: { status: permission.asReadonly() } },
      ],
    });
    const service = TestBed.inject(StorageSafetyService);
    await vi.waitFor(() => expect(service.pendingStorageWarning()).toBe(true));
  });

  it('reports observation failures as unavailable', async () => {
    const database = new HortinisDatabase(`hortinis-h7-${crypto.randomUUID()}`);
    databases.push(database);
    vi.spyOn(database.outboxOperations, 'count').mockRejectedValue(new Error('Storage failure'));
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        { provide: StoragePersistence, useValue: { status: permission.asReadonly() } },
      ],
    });
    const service = TestBed.inject(StorageSafetyService);
    await vi.waitFor(() =>
      expect(service.outbox()).toEqual({ status: 'unavailable', count: null }),
    );
  });

  it.each(['initial', 'subsequent'] as const)(
    'publishes an aborted %s read and resumes observation after another write',
    async (phase) => {
      const database = new HortinisDatabase(`hortinis-h7-abort-${crypto.randomUUID()}`);
      databases.push(database);
      await database.outboxOperations.add(operation);
      const count = database.outboxOperations.count.bind(database.outboxOperations);
      const countSpy = vi.spyOn(database.outboxOperations, 'count');
      const abortRead = () =>
        database.transaction('r', database.outboxOperations, (transaction) => {
          const result = count();
          transaction.abort();
          return result;
        });
      if (phase === 'initial') countSpy.mockImplementationOnce(abortRead);
      TestBed.configureTestingModule({
        providers: [
          { provide: HortinisDatabase, useValue: database },
          { provide: StoragePersistence, useValue: { status: permission.asReadonly() } },
        ],
      });
      const service = TestBed.inject(StorageSafetyService);
      if (phase === 'subsequent') {
        await vi.waitFor(() => expect(service.outbox()).toEqual({ status: 'available', count: 1 }));
        countSpy.mockImplementationOnce(abortRead);
        await database.outboxOperations.add({ ...operation, operationId: 'trigger-abort' });
      }
      await vi.waitFor(() =>
        expect(service.outbox()).toEqual({ status: 'unavailable', count: null }),
      );
      await database.outboxOperations.add({ ...operation, operationId: 'trigger-recovery' });
      await vi.waitFor(() =>
        expect(service.outbox()).toEqual({
          status: 'available',
          count: phase === 'initial' ? 2 : 3,
        }),
      );
      expect(service.pendingStorageWarning()).toBe(true);
      await database.outboxOperations.clear();
      await vi.waitFor(() => expect(service.outbox()).toEqual({ status: 'available', count: 0 }));
      expect(service.pendingStorageWarning()).toBe(false);
    },
  );

  it('disposes observation with the application service', () => {
    const stop = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: TechnicalRecordPersistence, useValue: { observeOutboxCount: () => stop } },
        { provide: StoragePersistence, useValue: { status: permission.asReadonly() } },
      ],
    });
    TestBed.inject(StorageSafetyService);
    TestBed.resetTestingModule();
    expect(stop).toHaveBeenCalledOnce();
  });
});
