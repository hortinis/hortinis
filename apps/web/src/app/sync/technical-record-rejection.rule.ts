export function operationRejectionCategory(
  kind: 'request' | 'protocol' | 'other',
  code?: string,
): 'invalid-request' | 'operation-id-reused' | 'record-already-exists' | undefined {
  if (kind === 'request') return 'invalid-request';
  if (kind !== 'protocol') return undefined;
  switch (code) {
    case 'INVALID_REQUEST':
      return 'invalid-request';
    case 'OPERATION_ID_REUSED':
      return 'operation-id-reused';
    case 'RECORD_ALREADY_EXISTS':
      return 'record-already-exists';
    default:
      return undefined;
  }
}
