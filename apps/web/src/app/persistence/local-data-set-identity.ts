import { parseAccessContract, type LocalDataSetId } from '../sync/access-contracts.rule';

export const LOCAL_DATA_SET_KEY = 'local-data-set';

export interface LocalDataSetIdentity {
  readonly key: typeof LOCAL_DATA_SET_KEY;
  readonly version: '1';
  readonly localDataSetId: LocalDataSetId;
}

export class LocalDataSetUnavailableError extends Error {
  constructor() {
    super('Local data-set identity is unavailable.');
  }
}

export class FreshLocalDatabaseRequiredError extends Error {
  constructor() {
    super('A fresh local database is required; existing data was not changed.');
  }
}

export function readLocalDataSetIdentity(rows: readonly unknown[]): LocalDataSetId {
  const row = rows[0];
  if (
    rows.length !== 1 ||
    typeof row !== 'object' ||
    row === null ||
    Array.isArray(row) ||
    Object.keys(row).length !== 3 ||
    !('key' in row) ||
    row.key !== LOCAL_DATA_SET_KEY ||
    !('version' in row) ||
    row.version !== '1' ||
    !('localDataSetId' in row)
  )
    throw new LocalDataSetUnavailableError();
  try {
    return parseAccessContract('LocalDataSetId', row.localDataSetId);
  } catch {
    throw new LocalDataSetUnavailableError();
  }
}
