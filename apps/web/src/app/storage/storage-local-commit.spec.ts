import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { HortinisDatabase } from '../persistence/hortinis-database';
import { TechnicalRecordLocalService } from '../sync/technical-record-local-service';
import { TechnicalRecordSynchronizationService } from '../sync/technical-record-synchronization-service';
import { BROWSER_STORAGE, StoragePersistence } from './storage-persistence';

describe('storage persistence after local commits', () => {
  let database: HortinisDatabase;
  const recovery = vi.fn(async () => ({ status: 'completed' as const }));
  const persist = vi.fn(async () => false);

  beforeEach(() => {
    database = new HortinisDatabase(`hortinis-h7-commits-${crypto.randomUUID()}`);
    recovery.mockClear();
    persist.mockReset().mockResolvedValue(false);
    TestBed.configureTestingModule({
      providers: [
        { provide: HortinisDatabase, useValue: database },
        {
          provide: TechnicalRecordSynchronizationService,
          useValue: { startBackgroundRecovery: recovery },
        },
        { provide: BROWSER_STORAGE, useValue: { persisted: async () => false, persist } },
      ],
    });
  });

  afterEach(async () => {
    TestBed.resetTestingModule();
    database.close();
    await database.delete();
  });

  it.each(['create', 'replace', 'delete'] as const)(
    'requests after a successful %s transaction',
    async (kind) => {
      const service = TestBed.inject(TechnicalRecordLocalService);
      if (kind !== 'create')
        await database.technicalRecords.add({
          recordId: 'record',
          value: 'old',
          lastAcceptedRevision: '1',
        });
      let observedCount: number | undefined;
      persist.mockImplementation(async () => {
        observedCount = await database.outboxOperations.count();
        return false;
      });
      if (kind === 'create') await service.create('new');
      else if (kind === 'replace') await service.replace('record', 'new');
      else await service.delete('record');
      await vi.waitFor(() => expect(observedCount).toBe(1));
      expect(persist).toHaveBeenCalledOnce();
      expect(recovery).toHaveBeenCalledOnce();
    },
  );

  it('does not delay local results or recovery while permission is pending', async () => {
    let grant!: (value: boolean) => void;
    persist.mockReturnValue(
      new Promise<boolean>((resolve) => {
        grant = resolve;
      }),
    );
    const service = TestBed.inject(TechnicalRecordLocalService);
    const result = await service.create('local');
    expect(result.record.value).toBe('local');
    expect(recovery).toHaveBeenCalledOnce();
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
    await expect(service.delete(result.record.recordId)).resolves.toEqual({
      status: 'cancelled',
      recordId: result.record.recordId,
    });
    expect(recovery).toHaveBeenCalledTimes(2);
    expect(persist).toHaveBeenCalledOnce();
    grant(true);
    await vi.waitFor(() => expect(TestBed.inject(StoragePersistence).status()).toBe('persistent'));
  });

  it('shares one automatic request across concurrent successful writes', async () => {
    const service = TestBed.inject(TechnicalRecordLocalService);
    await Promise.all([service.create('one'), service.create('two'), service.create('three')]);
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
    expect(await database.outboxOperations.count()).toBe(3);
    expect(recovery).toHaveBeenCalledTimes(3);
  });

  it('leaves a failed transaction eligible for the first successful request', async () => {
    const service = TestBed.inject(TechnicalRecordLocalService);
    const add = vi
      .spyOn(database.outboxOperations, 'add')
      .mockRejectedValueOnce(new Error('Write failed'));
    await expect(service.create('local')).rejects.toThrow('Write failed');
    expect(persist).not.toHaveBeenCalled();
    expect(recovery).not.toHaveBeenCalled();
    expect(await database.technicalRecords.count()).toBe(0);
    add.mockRestore();
    await service.create('valid');
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
  });

  it('does not request for invalid input or missing replace/delete records', async () => {
    const service = TestBed.inject(TechnicalRecordLocalService);
    await expect(service.create('\u0000')).rejects.toThrow();
    await expect(service.replace('missing', 'new')).rejects.toThrow();
    await expect(service.delete('missing')).rejects.toThrow();
    expect(persist).not.toHaveBeenCalled();
    expect(recovery).not.toHaveBeenCalled();
  });

  it('keeps successful local writes and recovery when the browser rejects permission', async () => {
    persist.mockRejectedValue(new Error('Permission failure'));
    const service = TestBed.inject(TechnicalRecordLocalService);
    const result = await service.create('local');
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce());
    expect(await database.technicalRecords.get(result.record.recordId)).toEqual(result.record);
    expect(await database.outboxOperations.count()).toBe(1);
    expect(recovery).toHaveBeenCalledOnce();
    expect(TestBed.inject(StoragePersistence).status()).toBe('best-effort');
  });
});
