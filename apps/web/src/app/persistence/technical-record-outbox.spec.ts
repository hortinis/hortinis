import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  SynchronizationTestHarness,
  testOperation,
  accepted,
  emptyPage,
  deferred,
} from '../sync/testing/synchronization-test-harness';
import type { TechnicalRecordOperation, OperationResult } from '../sync/conformance';
import {
  SynchronizationUnavailableError,
  SynchronizationProtocolError,
  SynchronizationBoundaryError,
  SynchronizationUnexpectedResponseError,
} from '../sync/synchronization-transport';
import { SynchronizationOwnershipLostError } from './synchronization-ownership';
import { pushRetryWorkId } from './local-synchronization-retry-state';

const secondId = '00000000-0000-4000-8000-000000000003';
const thirdId = '00000000-0000-4000-8000-000000000004';

describe('outbox chains and submission boundaries', () => {
  let harness: SynchronizationTestHarness;
  afterEach(async () => {
    await harness.close();
  });
  function scope(
    submitOperation = vi.fn(async (operation: TechnicalRecordOperation) => accepted(operation)),
  ) {
    harness = new SynchronizationTestHarness();
    return harness.scope({ submitOperation, pullChanges: async () => emptyPage });
  }

  it('coalesces repeated unsent creates and cancels create/delete without a receipt or tombstone', async () => {
    const owner = scope();
    await owner.persistence.commitCreate(testOperation);
    for (let index = 0; index < 50; index++) {
      const replacement = await owner.persistence.commitReplace(
        crypto.randomUUID(),
        testOperation.recordId,
        `value ${index}`,
      );
      expect(replacement.operation).toEqual({ ...testOperation, value: `value ${index}` });
    }
    expect(await harness.database.outboxOperations.count()).toBe(1);
    expect(await owner.persistence.commitDelete(secondId, testOperation.recordId)).toEqual({
      status: 'cancelled',
      recordId: testOperation.recordId,
    });
    expect(await harness.database.outboxOperations.count()).toBe(0);
    expect(await harness.database.technicalRecords.count()).toBe(0);
    expect(await harness.database.pendingDeletionRecords.count()).toBe(0);
    expect(await harness.database.technicalTombstones.count()).toBe(0);
    expect(await harness.database.acceptedOperationResults.count()).toBe(0);
  });

  it('coalesces unsent replacements and replacement/delete while keeping the original revision and identifier', async () => {
    const owner = scope();
    await owner.persistence.commitCreate(testOperation);
    await owner.service.pushOnePendingOperation();
    const first = await owner.persistence.commitReplace(secondId, testOperation.recordId, 'first');
    const latest = await owner.persistence.commitReplace(thirdId, testOperation.recordId, 'latest');
    expect(latest.operation).toEqual({ ...first.operation, value: 'latest' });
    const deletion = await owner.persistence.commitDelete(
      crypto.randomUUID(),
      testOperation.recordId,
    );
    expect(deletion).toEqual({
      operationId: secondId,
      recordId: testOperation.recordId,
      kind: 'delete',
      expectedRevision: '1',
    });
    expect(await harness.database.pendingDeletionRecords.get(testOperation.recordId)).toMatchObject(
      { value: 'latest' },
    );
  });

  it('commits submission metadata and reservation before dispatch and replays unchanged after edits and reopening', async () => {
    const submissions: TechnicalRecordOperation[] = [];
    const receipts = new Map<string, OperationResult>();
    let lost = true;
    const submit = vi.fn(async (operation: TechnicalRecordOperation) => {
      const stored = await harness.database.outboxOperations.get(operation.operationId);
      expect(stored?.submittedAt).toBe(0);
      expect(
        await harness.database.synchronizationRetryState.get(
          pushRetryWorkId(operation.operationId),
        ),
      ).toMatchObject({ inFlight: true });
      expect(operation).not.toHaveProperty('submittedAt');
      expect(operation).not.toHaveProperty('predecessorOperationId');
      submissions.push(operation);
      let receipt = receipts.get(operation.operationId);
      if (!receipt) {
        if (operation.kind === 'delete') throw new Error('Unexpected deletion.');
        const revision = String(receipts.size + 1);
        receipt = {
          outcome: 'accepted',
          operationId: operation.operationId,
          sequence: revision,
          record: { recordId: operation.recordId, revision, value: operation.value },
        };
        receipts.set(operation.operationId, receipt);
      }
      if (lost) {
        lost = false;
        throw new SynchronizationUnavailableError();
      }
      return receipt;
    });
    const owner = scope(submit);
    await owner.persistence.commitCreate(testOperation);
    expect((await owner.service.pushOnePendingOperation()).status).toBe('failed');
    await owner.persistence.commitReplace(secondId, testOperation.recordId, 'second');
    await owner.persistence.commitReplace(thirdId, testOperation.recordId, 'third');
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual({
      ...testOperation,
      submittedAt: 0,
    });
    expect(await harness.database.outboxOperations.get(secondId)).toMatchObject({
      value: 'third',
      predecessorOperationId: testOperation.operationId,
      expectedRevision: null,
    });
    owner.destroy();
    harness.database.close();
    await harness.database.open();
    const resumed = harness.scope({ submitOperation: submit, pullChanges: async () => emptyPage });
    expect(await resumed.service.retryNow()).toMatchObject({ status: 'completed', pushed: 2 });
    expect(submissions).toEqual([
      testOperation,
      testOperation,
      {
        operationId: secondId,
        recordId: testOperation.recordId,
        kind: 'replace',
        value: 'third',
        expectedRevision: '1',
      },
    ]);
    expect(receipts.size).toBe(2);
    expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
      value: 'third',
      lastAcceptedRevision: '2',
    });
  });

  it.each(['edit-first', 'prepare-first'] as const)(
    'serializes competing-tab coalescing and preparation (%s)',
    async (order) => {
      const owner = scope();
      const other = harness.scope({ submitOperation: vi.fn(), pullChanges: async () => emptyPage });
      await owner.persistence.commitCreate(testOperation);
      const lease = await owner.coordinator.tryAcquire();
      if (!lease.acquired) throw new Error('Expected lease.');
      const ownership = { lease: lease.lease, now: () => 0, isLost: () => false };
      const edit = () => other.persistence.commitReplace(secondId, testOperation.recordId, 'later');
      const prepare = () => owner.persistence.preparePendingOperation(ownership, 10000);
      let prepared: TechnicalRecordOperation | undefined;
      if (order === 'edit-first') {
        const [, operation] = await Promise.all([edit(), prepare()]);
        prepared = operation;
      } else {
        const [operation] = await Promise.all([prepare(), edit()]);
        prepared = operation;
      }
      expect(prepared).toBeDefined();
      expect(await harness.database.outboxOperations.get(testOperation.operationId)).toMatchObject({
        ...prepared,
        submittedAt: 0,
      });
      await owner.persistence.commitAcceptedResult(prepared!, accepted(prepared!), ownership);
      expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
        value: 'later',
      });
      const successor = await owner.persistence.firstPendingOperation();
      if (prepared?.kind !== 'delete' && prepared?.value === 'later')
        expect(successor).toBeUndefined();
      else
        expect(successor).toMatchObject({
          operationId: secondId,
          expectedRevision: '1',
          value: 'later',
        });
    },
  );

  it('rolls back reservation when marking the submitted operation fails, and dispatches nothing', async () => {
    const submit = vi.fn(async (operation: TechnicalRecordOperation) => accepted(operation));
    const owner = scope(submit);
    await owner.persistence.commitCreate(testOperation);
    vi.spyOn(harness.database.outboxOperations, 'put').mockRejectedValueOnce(
      new Error('Storage failed.'),
    );
    await expect(owner.service.pushOnePendingOperation()).rejects.toThrow('Storage failed.');
    expect(submit).not.toHaveBeenCalled();
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
      testOperation,
    );
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
  });

  it('rejects stale ownership before marking or reserving work', async () => {
    const owner = scope();
    await owner.persistence.commitCreate(testOperation);
    const attempt = await owner.coordinator.tryAcquire();
    if (!attempt.acquired) throw new Error('Expected lease.');
    await expect(
      owner.persistence.preparePendingOperation(
        { lease: attempt.lease, now: () => attempt.lease.expiresAt, isLost: () => false },
        10000,
      ),
    ).rejects.toBeInstanceOf(SynchronizationOwnershipLostError);
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
      testOperation,
    );
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
  });

  it('drains a long linear chain whose UUID ordering differs from causal order', async () => {
    const sent: TechnicalRecordOperation[] = [];
    const owner = scope(
      vi.fn(async (operation) => {
        sent.push(operation);
        if (operation.kind === 'delete') throw new Error('Unexpected deletion.');
        if (operation.kind === 'replace')
          expect(operation.expectedRevision).toBe(String(sent.length - 1));
        return {
          outcome: 'accepted',
          operationId: operation.operationId,
          sequence: String(sent.length),
          record: {
            recordId: operation.recordId,
            revision: String(sent.length),
            value: operation.value,
          },
        };
      }),
    );
    const count = 25;
    let predecessor = testOperation.operationId;
    await owner.persistence.commitCreate(testOperation);
    for (let index = 1; index < count; index++) {
      const operationId = crypto.randomUUID();
      await harness.database.outboxOperations.add({
        operationId,
        recordId: testOperation.recordId,
        kind: 'replace',
        value: `value ${index}`,
        expectedRevision: null,
        predecessorOperationId: predecessor,
      });
      predecessor = operationId;
    }
    await harness.database.technicalRecords.update(testOperation.recordId, {
      value: `value ${count - 1}`,
    });
    expect(await owner.service.recoverAfterReload()).toMatchObject({
      status: 'completed',
      pushed: count,
    });
    expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
      value: `value ${count - 1}`,
      lastAcceptedRevision: String(count),
    });
    expect(await harness.database.outboxOperations.count()).toBe(0);
  });

  it('preserves the marked tail when a further edit arrives during its acknowledgement', async () => {
    const response = deferred<OperationResult>();
    const owner = scope(vi.fn(() => response.promise));
    await owner.persistence.commitCreate(testOperation);
    const push = owner.service.pushOnePendingOperation();
    await vi.waitFor(async () =>
      expect(
        (await harness.database.outboxOperations.get(testOperation.operationId))?.submittedAt,
      ).toBe(0),
    );
    await owner.persistence.commitReplace(secondId, testOperation.recordId, 'latest');
    response.resolve(accepted(testOperation));
    expect((await push).status).toBe('accepted');
    expect(await owner.persistence.firstPendingOperation()).toMatchObject({
      value: 'latest',
      expectedRevision: '1',
    });
  });

  it.each(['request', 'protocol'] as const)(
    'quarantines %s rejection, retains dependent intent, and drains an independent record',
    async (reason) => {
      const independent = {
        ...testOperation,
        operationId: crypto.randomUUID(),
        recordId: crypto.randomUUID(),
        value: 'other',
      };
      const submit = vi.fn(async (operation: TechnicalRecordOperation) => {
        if (operation.recordId === independent.recordId) return accepted(operation);
        if (reason === 'request')
          throw new SynchronizationBoundaryError('Invalid request.', 'request');
        throw new SynchronizationProtocolError(400, {
          code: 'INVALID_REQUEST',
          message: 'Invalid request.',
        });
      });
      const owner = scope(submit);
      await owner.persistence.commitCreate(testOperation);
      await harness.database.outboxOperations.update(testOperation.operationId, { submittedAt: 0 });
      await owner.persistence.commitReplace(secondId, testOperation.recordId, 'retained intent');
      await owner.persistence.commitCreate(independent);
      expect(await owner.service.recoverAfterReload()).toMatchObject({
        status: 'completed',
        pushed: 1,
      });
      expect(submit).toHaveBeenCalledTimes(2);
      expect(await harness.database.outboxOperations.get(secondId)).toMatchObject({
        expectedRevision: null,
      });
      expect(
        await harness.database.rejectedOperations.get(testOperation.operationId),
      ).toMatchObject({
        operation: { ...testOperation, submittedAt: 0 },
        category: 'invalid-request',
        localRecord: { value: 'retained intent' },
      });
      await owner.persistence.commitPulledPage({
        changes: [
          {
            operationId: crypto.randomUUID(),
            record: { recordId: testOperation.recordId, revision: '2', value: 'remote' },
            sequence: '2',
          },
        ],
        nextCursor: 'later',
        hasMore: false,
      });
      expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
        value: 'retained intent',
      });
      await owner.service.retryNow();
      expect(submit).toHaveBeenCalledTimes(2);
      await vi.waitFor(() =>
        expect(owner.service.rejections()).toEqual({ status: 'rejected', count: 1 }),
      );
    },
  );

  it.each(['response', 'unexpected'] as const)(
    'retains uncertain %s failures without quarantine',
    async (kind) => {
      const owner = scope(
        vi.fn(async () => {
          throw kind === 'response'
            ? new SynchronizationBoundaryError('Invalid response.', 'response')
            : new SynchronizationUnexpectedResponseError(200);
        }),
      );
      await owner.persistence.commitCreate(testOperation);
      expect((await owner.service.pushOnePendingOperation()).status).toBe('failed');
      expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual({
        ...testOperation,
        submittedAt: 0,
      });
      expect(await harness.database.rejectedOperations.count()).toBe(0);
    },
  );

  it('rolls back a failed quarantine and handles duplicate rejection idempotently', async () => {
    const owner = scope();
    await owner.persistence.commitCreate(testOperation);
    vi.spyOn(harness.database.rejectedOperations, 'add').mockRejectedValueOnce(
      new Error('Storage failed.'),
    );
    await expect(
      owner.persistence.commitRejectedOperation(testOperation, 'invalid-request'),
    ).rejects.toThrow('Storage failed.');
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
      testOperation,
    );
    expect(await harness.database.rejectedOperations.count()).toBe(0);
    await owner.persistence.commitRejectedOperation(testOperation, 'invalid-request');
    await owner.persistence.commitRejectedOperation(testOperation, 'invalid-request');
    await expect(
      owner.persistence.commitRejectedOperation(
        { ...testOperation, value: 'changed' },
        'invalid-request',
      ),
    ).rejects.toThrow('does not match');
    expect(await harness.database.rejectedOperations.count()).toBe(1);
  });

  it('retries a failed quarantine commit without losing the marked operation', async () => {
    const submit = vi.fn(async () => {
      throw new SynchronizationProtocolError(400, {
        code: 'INVALID_REQUEST',
        message: 'Invalid request.',
      });
    });
    const owner = scope(submit);
    await owner.persistence.commitCreate(testOperation);
    vi.spyOn(harness.database.rejectedOperations, 'add').mockRejectedValueOnce(
      new Error('Storage failed.'),
    );
    expect(await owner.service.recoverAfterReload()).toMatchObject({
      status: 'scheduled',
      phase: 'push',
      attemptCount: 1,
    });
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual({
      ...testOperation,
      submittedAt: 0,
    });
    expect(await owner.service.retryNow()).toMatchObject({ status: 'completed' });
    expect(submit.mock.calls).toHaveLength(2);
    expect(await harness.database.rejectedOperations.count()).toBe(1);
    expect(await harness.database.synchronizationRetryState.count()).toBe(0);
  });

  it.each(['operation-id-reused', 'record-already-exists'] as const)(
    'quarantines a correlated %s response without retrying it',
    async (category) => {
      const submit = vi.fn(async () => {
        throw new SynchronizationProtocolError(
          409,
          category === 'operation-id-reused'
            ? {
                code: 'OPERATION_ID_REUSED',
                operationId: testOperation.operationId,
                message: 'Identity reused.',
              }
            : {
                code: 'RECORD_ALREADY_EXISTS',
                operationId: testOperation.operationId,
                message: 'Record exists.',
                currentRecord: { recordId: testOperation.recordId, revision: '1', value: 'remote' },
              },
        );
      });
      const owner = scope(submit);
      await owner.persistence.commitCreate(testOperation);
      expect(await owner.service.pushOnePendingOperation()).toMatchObject({
        status: 'rejected',
        category,
      });
      await vi.waitFor(() =>
        expect(owner.service.rejections()).toEqual({ status: 'rejected', count: 1 }),
      );
      await owner.service.retryNow();
      expect(submit).toHaveBeenCalledOnce();
    },
  );

  it.each(['wrong-operation', 'wrong-record'] as const)(
    'retains a mismatched rejection (%s) without quarantine',
    async (mismatch) => {
      const owner = scope(
        vi.fn(async () => {
          throw new SynchronizationProtocolError(409, {
            code: 'RECORD_ALREADY_EXISTS',
            operationId: mismatch === 'wrong-operation' ? secondId : testOperation.operationId,
            message: 'Record exists.',
            currentRecord: {
              recordId: mismatch === 'wrong-record' ? secondId : testOperation.recordId,
              revision: '1',
              value: 'remote',
            },
          });
        }),
      );
      await owner.persistence.commitCreate(testOperation);
      expect(await owner.service.pushOnePendingOperation()).toMatchObject({
        status: 'failed',
        reason: 'boundary',
      });
      expect(await harness.database.rejectedOperations.count()).toBe(0);
      expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual({
        ...testOperation,
        submittedAt: 0,
      });
    },
  );

  it('fences a late rejection after another tab takes ownership', async () => {
    harness = new SynchronizationTestHarness();
    const clock = { value: 0 };
    const response = deferred<OperationResult>();
    const owner = harness.scope(
      { submitOperation: () => response.promise, pullChanges: async () => emptyPage },
      { clock },
    );
    await owner.persistence.commitCreate(testOperation);
    const push = owner.service.pushOnePendingOperation();
    await vi.waitFor(async () =>
      expect(
        (await harness.database.outboxOperations.get(testOperation.operationId))?.submittedAt,
      ).toBe(0),
    );
    clock.value = 15001;
    const successor = harness.scope(
      { submitOperation: vi.fn(), pullChanges: async () => emptyPage },
      { clock },
    );
    expect((await successor.coordinator.tryAcquire()).acquired).toBe(true);
    response.reject(
      new SynchronizationProtocolError(400, {
        code: 'INVALID_REQUEST',
        message: 'Invalid request.',
      }),
    );
    expect(await push).toEqual({ status: 'ownership-lost' });
    expect(await harness.database.rejectedOperations.count()).toBe(0);
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual({
      ...testOperation,
      submittedAt: 0,
    });
  });

  it('rolls back coalescing and cancellation when the outbox mutation fails', async () => {
    const owner = scope();
    await owner.persistence.commitCreate(testOperation);
    vi.spyOn(harness.database.outboxOperations, 'put').mockRejectedValueOnce(
      new Error('Storage failed.'),
    );
    await expect(
      owner.persistence.commitReplace(secondId, testOperation.recordId, 'changed'),
    ).rejects.toThrow('Storage failed.');
    expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
      value: testOperation.value,
    });
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
      testOperation,
    );
    vi.spyOn(harness.database.outboxOperations, 'delete').mockRejectedValueOnce(
      new Error('Storage failed.'),
    );
    await expect(owner.persistence.commitDelete(thirdId, testOperation.recordId)).rejects.toThrow(
      'Storage failed.',
    );
    expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
      value: testOperation.value,
    });
    expect(await harness.database.outboxOperations.get(testOperation.operationId)).toEqual(
      testOperation,
    );
  });

  it('reports a missing predecessor instead of submitting or discarding an orphan', async () => {
    const owner = scope();
    await harness.database.outboxOperations.add({
      operationId: secondId,
      recordId: testOperation.recordId,
      kind: 'replace',
      value: 'orphan',
      expectedRevision: null,
      predecessorOperationId: thirdId,
    });
    await expect(owner.persistence.firstPendingOperation()).rejects.toThrow('missing predecessor');
    expect(await harness.database.outboxOperations.count()).toBe(1);
  });

  it('retains a quarantined value across pulls with an otherwise empty outbox', async () => {
    const owner = scope();
    await owner.persistence.commitCreate(testOperation);
    await owner.persistence.commitRejectedOperation(testOperation, 'invalid-request');
    await owner.persistence.commitPulledPage({
      changes: [
        {
          operationId: secondId,
          record: {
            recordId: testOperation.recordId,
            revision: '2',
            value: 'remote accepted value',
          },
          sequence: '2',
        },
      ],
      nextCursor: 'remote',
      hasMore: false,
    });
    expect(await harness.database.outboxOperations.count()).toBe(0);
    expect(
      await harness.database.acceptedTechnicalRecords.get(testOperation.recordId),
    ).toMatchObject({ value: 'remote accepted value' });
    expect(await harness.database.technicalRecords.get(testOperation.recordId)).toMatchObject({
      value: testOperation.value,
      lastAcceptedRevision: '2',
    });
    await owner.persistence.commitReplace(secondId, testOperation.recordId, 'correction intent');
    expect(await harness.database.outboxOperations.get(secondId)).toMatchObject({
      expectedRevision: null,
      predecessorOperationId: testOperation.operationId,
    });
    expect(await owner.persistence.firstPendingOperation()).toBeUndefined();
  });

  it('observes rejection counts across service instances and disposes observation on destruction', async () => {
    const owner = scope();
    const other = harness.scope({ submitOperation: vi.fn(), pullChanges: async () => emptyPage });
    await owner.persistence.commitCreate(testOperation);
    await owner.persistence.commitRejectedOperation(testOperation, 'invalid-request');
    await vi.waitFor(() =>
      expect(other.service.rejections()).toEqual({ status: 'rejected', count: 1 }),
    );
    await vi.waitFor(() =>
      expect(owner.service.rejections()).toEqual({ status: 'rejected', count: 1 }),
    );
    const publish = vi.spyOn(owner.service.rejections, 'set');
    owner.destroy();
    publish.mockClear();
    const independent = {
      ...testOperation,
      operationId: secondId,
      recordId: thirdId,
      value: 'independent',
    };
    await other.persistence.commitCreate(independent);
    await other.persistence.commitRejectedOperation(independent, 'invalid-request');
    await vi.waitFor(() =>
      expect(other.service.rejections()).toEqual({ status: 'rejected', count: 2 }),
    );
    expect(publish).not.toHaveBeenCalled();
    expect(JSON.stringify(other.service.rejections())).not.toContain('independent');
  });
});
