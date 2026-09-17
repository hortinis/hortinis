export const HORTINIS_DATABASE_SCHEMA = {
  technicalRecords: 'recordId',
  outboxOperations: 'operationId, recordId',
  acceptedOperationResults: 'operationId',
  synchronizationState: 'scope',
};
