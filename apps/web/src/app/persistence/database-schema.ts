export const HORTINIS_DATABASE_SCHEMA_V1 = {
  technicalRecords: 'recordId',
  outboxOperations: 'operationId, recordId',
  acceptedOperationResults: 'operationId',
  revisionConflicts: 'operationId',
  synchronizationState: 'scope',
};

export const HORTINIS_DATABASE_SCHEMA = {
  ...HORTINIS_DATABASE_SCHEMA_V1,
  technicalTombstones: 'recordId',
  pendingDeletionRecords: 'recordId',
  deletionConflicts: 'operationId, recordId',
};
