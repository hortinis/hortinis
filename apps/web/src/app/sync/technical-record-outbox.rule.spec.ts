import { describe, expect, it } from 'vitest';
import { outboxTail, outboxMutation, type OutboxChainNode } from './technical-record-outbox.rule';
import { operationRejectionCategory } from './technical-record-rejection.rule';

describe('outbox decisions', () => {
  it('orders by predecessor links independently of array or identifier order', () => {
    const root = { operationId: 'z', kind: 'create' as const };
    const middle = { operationId: 'a', kind: 'replace' as const, predecessorOperationId: 'z' };
    const tail = { operationId: 'm', kind: 'delete' as const, predecessorOperationId: 'a' };
    expect(outboxTail([middle, tail, root])).toEqual(tail);
    expect(outboxTail([])).toBeUndefined();
  });
  it.each(
    [
      [
        { operationId: 'a', kind: 'create' as const },
        { operationId: 'b', kind: 'create' as const },
      ],
      [
        { operationId: 'a', kind: 'replace' as const, predecessorOperationId: 'b' },
        { operationId: 'b', kind: 'replace' as const, predecessorOperationId: 'a' },
      ],
      [
        { operationId: 'a', kind: 'replace' as const, predecessorOperationId: 'root' },
        { operationId: 'b', kind: 'delete' as const, predecessorOperationId: 'root' },
      ],
    ].map((operations) => ({ operations })),
  )('rejects disconnected, cyclic, and forked chains', ({ operations }) => {
    expect(() => outboxTail<OutboxChainNode>(operations)).toThrow();
  });
  it.each([
    ['create', 'replace', 'coalesce'],
    ['create', 'delete', 'cancel'],
    ['replace', 'replace', 'coalesce'],
    ['replace', 'delete', 'coalesce'],
  ] as const)('decides %s + %s as %s only before submission', (kind, action, result) => {
    const tail = { operationId: 'tail', kind };
    expect(outboxMutation(tail, action, false)).toBe(result);
    expect(outboxMutation({ ...tail, submittedAt: 0 }, action, false)).toBe('append');
    expect(outboxMutation({ ...tail, legacySubmissionUnknown: true }, action, false)).toBe(
      'append',
    );
    expect(outboxMutation(tail, action, true)).toBe('append');
  });
  it('rejects successors after deletion, dependent creates, and duplicate identities', () => {
    expect(() =>
      outboxTail([
        { operationId: 'deleted', kind: 'delete' },
        { operationId: 'later', kind: 'replace', predecessorOperationId: 'deleted' },
      ]),
    ).toThrow('terminates');
    expect(() =>
      outboxTail([{ operationId: 'created', kind: 'create', predecessorOperationId: 'earlier' }]),
    ).toThrow('create cannot');
    expect(() =>
      outboxTail([
        { operationId: 'same', kind: 'create' },
        { operationId: 'same', kind: 'create' },
      ]),
    ).toThrow('duplicate');
  });
  it('does not extend a pending deletion or coalesce without a tail', () => {
    expect(() =>
      outboxMutation({ operationId: 'delete', kind: 'delete' }, 'replace', false),
    ).toThrow('already pending');
    expect(outboxMutation(undefined, 'replace', false)).toBe('append');
  });
  it('classifies only recognized request and protocol rejections', () => {
    expect(operationRejectionCategory('request')).toBe('invalid-request');
    expect(operationRejectionCategory('protocol', 'INVALID_REQUEST')).toBe('invalid-request');
    expect(operationRejectionCategory('protocol', 'OPERATION_ID_REUSED')).toBe(
      'operation-id-reused',
    );
    expect(operationRejectionCategory('protocol', 'RECORD_ALREADY_EXISTS')).toBe(
      'record-already-exists',
    );
    expect(operationRejectionCategory('protocol', 'REVISION_CONFLICT')).toBeUndefined();
    expect(operationRejectionCategory('other', 'INVALID_REQUEST')).toBeUndefined();
  });
});
