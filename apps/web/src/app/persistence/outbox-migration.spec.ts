import 'fake-indexeddb/auto';
import { afterEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import type Dexie from 'dexie';
import { HortinisDatabase } from './hortinis-database';
import { VersionFiveMigrationFixtureDatabase } from './testing/migration-fixture-database';
import { TechnicalRecordPersistence } from './technical-record-persistence';
import { toSubmittedTechnicalRecordOperation } from './local-technical-record-operation';

const recordId = '00000000-0000-4000-8000-000000000001';
const createId = '00000000-0000-4000-8000-000000000002';
const replaceId = '00000000-0000-4000-8000-000000000003';

describe('version-six outbox migration', () => {
  const databases: Pick<Dexie, 'close' | 'delete'>[] = [];
  afterEach(async () => {
    TestBed.resetTestingModule();
    for (const database of databases.reverse()) {
      database.close();
      await database.delete();
    }
    databases.length = 0;
  });

  it('preserves populated version-five stores and distinguishes ready legacy work from deferred intent', async () => {
    const name = `hortinis-h6-migration-${crypto.randomUUID()}`;
    const legacy = new VersionFiveMigrationFixtureDatabase(name);
    databases.push(legacy);
    await legacy.open();
    const create = {
      operationId: createId,
      recordId,
      kind: 'create' as const,
      value: 'original submitted value',
    };
    const deferred = {
      operationId: replaceId,
      recordId,
      kind: 'replace' as const,
      value: 'local latest',
      expectedRevision: null,
      predecessorOperationId: createId,
    };
    const deletion = {
      operationId: crypto.randomUUID(),
      recordId: crypto.randomUUID(),
      kind: 'delete' as const,
      expectedRevision: '4',
    };
    await legacy.table('outboxOperations').bulkAdd([create, deferred, deletion]);
    const retained: Record<string, object[]> = {
      technicalRecords: [{ recordId, value: 'local latest', lastAcceptedRevision: null }],
      acceptedTechnicalRecords: [
        { recordId: 'accepted-record', revision: '2', value: 'accepted value' },
      ],
      acceptedOperationResults: [
        {
          operationId: 'accepted-operation',
          outcome: 'accepted',
          sequence: '2',
          record: { recordId: 'accepted-record', revision: '2', value: 'accepted value' },
        },
      ],
      technicalTombstones: [{ recordId: 'retired-record', revision: '3', deletedAtSequence: '3' }],
      pendingDeletionRecords: [
        { recordId: deletion.recordId, value: 'deletion base', lastAcceptedRevision: '4' },
      ],
      revisionConflicts: [
        {
          operationId: 'conflicted-operation',
          code: 'REVISION_CONFLICT',
          message: 'Conflict.',
          expectedRevision: '1',
          currentRecord: { recordId: 'conflicted-record', revision: '2', value: 'remote value' },
        },
      ],
      deletionConflicts: [
        { operationId: 'deleted-operation', recordId: 'deleted-record', reason: 'remote-deletion' },
      ],
      synchronizationState: [
        {
          scope: 'technical-records',
          cursor: 'normal-cursor',
          repairRequired: true,
          repairCursor: 'repair-cursor',
        },
      ],
      synchronizationRetryState: [
        {
          workId: `technical-records:push:${createId}`,
          scope: 'technical-records',
          phase: 'push',
          operationId: createId,
          attemptCount: 5,
          exhausted: true,
          nextEligibleAt: null,
          failureCategory: 'unavailable',
        },
      ],
      synchronizationLeases: [
        { scope: 'technical-records', ownerId: 'legacy-owner', fencingToken: 7, expiresAt: 0 },
      ],
    };
    for (const [table, rows] of Object.entries(retained)) await legacy.table(table).bulkAdd(rows);
    legacy.close();
    const upgraded = new HortinisDatabase(name);
    databases.push(upgraded);
    await upgraded.open();
    expect(upgraded.verno).toBe(6);
    for (const [table, rows] of Object.entries(retained))
      expect(await upgraded.table(table).toArray()).toEqual(rows);
    expect(await upgraded.outboxOperations.get(createId)).toEqual({
      ...create,
      legacySubmissionUnknown: true,
    });
    expect(await upgraded.outboxOperations.get(replaceId)).toEqual(deferred);
    expect(await upgraded.outboxOperations.get(deletion.operationId)).toEqual({
      ...deletion,
      legacySubmissionUnknown: true,
    });
    expect(await upgraded.rejectedOperations.count()).toBe(0);
    expect(
      toSubmittedTechnicalRecordOperation((await upgraded.outboxOperations.get(createId))!),
    ).toEqual(create);
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: upgraded }],
    });
    const persistence = TestBed.inject(TechnicalRecordPersistence);
    await persistence.commitReplace(crypto.randomUUID(), recordId, 'after upgrade');
    expect(await upgraded.outboxOperations.get(createId)).toEqual({
      ...create,
      legacySubmissionUnknown: true,
    });
    expect(await upgraded.outboxOperations.get(replaceId)).toEqual({
      ...deferred,
      value: 'after upgrade',
    });
    upgraded.close();
    await upgraded.open();
    expect(await upgraded.outboxOperations.get(createId)).toEqual({
      ...create,
      legacySubmissionUnknown: true,
    });
  });

  it('appends rather than rewriting a legacy ready operation with unknown submission history', async () => {
    const name = `hortinis-h6-legacy-ready-${crypto.randomUUID()}`;
    const legacy = new VersionFiveMigrationFixtureDatabase(name);
    databases.push(legacy);
    await legacy
      .table('technicalRecords')
      .add({ recordId, value: 'legacy', lastAcceptedRevision: null });
    const original = { operationId: createId, recordId, kind: 'create' as const, value: 'legacy' };
    await legacy.table('outboxOperations').add(original);
    legacy.close();
    const upgraded = new HortinisDatabase(name);
    databases.push(upgraded);
    await upgraded.open();
    TestBed.configureTestingModule({
      providers: [{ provide: HortinisDatabase, useValue: upgraded }],
    });
    const persistence = TestBed.inject(TechnicalRecordPersistence);
    const changed = await persistence.commitReplace(replaceId, recordId, 'new');
    expect(changed.operation).toEqual({
      operationId: replaceId,
      recordId,
      kind: 'replace',
      value: 'new',
      expectedRevision: null,
      predecessorOperationId: createId,
    });
    expect(await persistence.firstPendingOperation()).toEqual(original);
    expect(await upgraded.outboxOperations.get(createId)).toEqual({
      ...original,
      legacySubmissionUnknown: true,
    });
  });
});
