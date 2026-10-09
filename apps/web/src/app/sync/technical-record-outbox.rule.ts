export interface OutboxChainNode {
  operationId: string;
  kind: 'create' | 'replace' | 'delete';
  predecessorOperationId?: string;
  submittedAt?: number;
  legacySubmissionUnknown?: true;
}

export function outboxTail<T extends OutboxChainNode>(operations: T[]): T | undefined {
  if (operations.length === 0) return undefined;
  const byId = new Map(operations.map((operation) => [operation.operationId, operation]));
  const predecessors = new Set<string>();
  if (byId.size !== operations.length)
    throw new Error('The outbox chain has duplicate identities.');
  for (const operation of operations) {
    assertLink(operation, byId.get(operation.predecessorOperationId ?? ''));
    if (operation.predecessorOperationId === undefined) continue;
    if (predecessors.has(operation.predecessorOperationId)) {
      throw new Error('A predecessor cannot have more than one successor.');
    }
    predecessors.add(operation.predecessorOperationId);
  }
  const roots = operations.filter((operation) => !byId.has(operation.predecessorOperationId ?? ''));
  const tails = operations.filter((operation) => !predecessors.has(operation.operationId));
  if (roots.length !== 1 || tails.length !== 1)
    throw new Error('The outbox chain is disconnected or cyclic.');
  let current: T | undefined = tails[0];
  const visited = new Set<string>();
  while (current) {
    if (visited.has(current.operationId)) throw new Error('The outbox chain is cyclic.');
    visited.add(current.operationId);
    current = byId.get(current.predecessorOperationId ?? '');
  }
  if (visited.size !== operations.length)
    throw new Error('The outbox chain is disconnected or cyclic.');
  return tails[0];
}

export function outboxMutation(
  tail: OutboxChainNode | undefined,
  action: 'replace' | 'delete',
  blocked: boolean,
): 'append' | 'coalesce' | 'cancel' {
  if (!tail || blocked || tail.submittedAt !== undefined || tail.legacySubmissionUnknown)
    return 'append';
  if (tail.kind === 'delete') throw new Error('A deletion is already pending.');
  return tail.kind === 'create' && action === 'delete' ? 'cancel' : 'coalesce';
}

function assertLink(operation: OutboxChainNode, predecessor?: OutboxChainNode): void {
  if (operation.kind === 'create' && operation.predecessorOperationId !== undefined)
    throw new Error('A create cannot have a predecessor.');
  if (predecessor?.kind === 'delete') throw new Error('A deletion terminates the outbox chain.');
}
