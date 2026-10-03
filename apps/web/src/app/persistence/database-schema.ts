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

export const HORTINIS_DATABASE_SCHEMA_V3 = {
  ...HORTINIS_DATABASE_SCHEMA,
  synchronizationRetryState: 'workId, scope, phase, operationId, exhausted',
};

export const HORTINIS_DATABASE_SCHEMA_V4 = {
  ...HORTINIS_DATABASE_SCHEMA_V3,
  synchronizationLeases: 'scope, ownerId, expiresAt',
};

export const HORTINIS_DATABASE_SCHEMA_V5 = {
  ...HORTINIS_DATABASE_SCHEMA_V4,
  acceptedTechnicalRecords: 'recordId',
};
