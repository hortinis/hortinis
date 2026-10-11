import {
  parseAccessContract,
  type LocalDataSetId,
  type LocalExchangeState,
  type LocalServerBinding,
} from '../sync/access-contracts.rule';
import { LocalDataSetUnavailableError } from './local-data-set-identity';

export interface OwnedLocalExchangeState {
  readonly localDataSetId: LocalDataSetId;
  readonly value: LocalExchangeState;
}

export interface LocalInitialAdmission {
  readonly localDataSetId: LocalDataSetId;
  readonly exchangeFence: string;
  readonly expectation: string | null;
  readonly expiresAt: number | null;
  readonly phase: 'confirmed' | 'uncertain';
}

export function readLocalExchangeState(
  rows: readonly unknown[],
  owner: LocalDataSetId,
): LocalExchangeState {
  const row = rows[0];
  if (
    rows.length !== 1 ||
    !object(row, ['localDataSetId', 'value']) ||
    row['localDataSetId'] !== owner
  )
    throw new LocalDataSetUnavailableError();
  try {
    return parseAccessContract('LocalExchangeState', row['value']);
  } catch {
    throw new LocalDataSetUnavailableError();
  }
}

export function readLocalInitialAdmission(
  rows: readonly unknown[],
  owner: LocalDataSetId,
  fence: string,
): LocalInitialAdmission | undefined {
  if (rows.length === 0) return undefined;
  const row = rows[0];
  if (
    rows.length !== 1 ||
    !object(row, ['localDataSetId', 'exchangeFence', 'expectation', 'expiresAt', 'phase']) ||
    row['localDataSetId'] !== owner ||
    row['exchangeFence'] !== fence
  )
    throw new LocalDataSetUnavailableError();
  if (row['phase'] === 'uncertain' && row['expectation'] === null && row['expiresAt'] === null) {
    return row as unknown as LocalInitialAdmission;
  }
  if (
    row['phase'] !== 'confirmed' ||
    typeof row['expiresAt'] !== 'number' ||
    !Number.isSafeInteger(row['expiresAt']) ||
    row['expiresAt'] <= 0
  )
    throw new LocalDataSetUnavailableError();
  try {
    parseAccessContract('ExchangeExpectation', row['expectation']);
  } catch {
    throw new LocalDataSetUnavailableError();
  }
  return row as unknown as LocalInitialAdmission;
}

export function readLocalBinding(
  rows: readonly unknown[],
  owner: LocalDataSetId,
): LocalServerBinding | undefined {
  if (rows.length === 0) return undefined;
  if (rows.length !== 1) throw new LocalDataSetUnavailableError();
  const binding = parseAccessContract('LocalServerBinding', rows[0]);
  if (binding.localDataSetId !== owner) throw new LocalDataSetUnavailableError();
  return binding;
}

function object(value: unknown, fields: readonly string[]): value is Record<string, unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.keys(value).length === fields.length &&
    fields.every((field) => Object.hasOwn(value, field))
  );
}

export function sameBinding(first: LocalServerBinding, second: LocalServerBinding): boolean {
  return (
    first.localDataSetId === second.localDataSetId &&
    first.serverOrigin === second.serverOrigin &&
    first.serverInstanceId === second.serverInstanceId &&
    first.identity.authority === second.identity.authority &&
    first.identity.accessMode === second.identity.accessMode &&
    first.identity.synchronizationScopeId === second.identity.synchronizationScopeId &&
    (first.identity.authority !== 'authenticated' ||
      (second.identity.authority === 'authenticated' &&
        first.identity.accountId === second.identity.accountId)) &&
    first.boundary.kind === second.boundary.kind &&
    (first.boundary.kind !== 'generation' ||
      (second.boundary.kind === 'generation' &&
        first.boundary.generation === second.boundary.generation))
  );
}
