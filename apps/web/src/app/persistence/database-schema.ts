export const HORTINIS_DATABASE_SCHEMA = {
  technicalRecords: 'recordId',
  outboxOperations: 'operationId, recordId',
  acceptedOperationResults: 'operationId',
  revisionConflicts: 'operationId',
  synchronizationState: 'scope',
  garden: 'id, name',
  space: 'id, gardenId, type, name',
  configuration: 'sync, serverUrl',
};
