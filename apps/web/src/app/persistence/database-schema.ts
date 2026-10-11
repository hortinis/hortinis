export const HORTINIS_DATABASE_SCHEMA = {
  technicalRecords: 'recordId',
  outboxOperations: 'operationId, [entityType+entityId]',
  acceptedOperationResults: 'operationId',
  revisionConflicts: 'operationId',
  synchronizationState: 'scope',
  garden: 'id, name',
  space: 'id, gardenId, type, name',
  configuration: 'id, sync, serverUrl',
};
