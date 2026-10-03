declare const identityBrand: unique symbol;
type Identity<Name extends string> = string & { readonly [identityBrand]: Name };

export type LocalDataSetId = Identity<'LocalDataSetId'>;
export type ServerInstanceId = Identity<'ServerInstanceId'>;
export type SynchronizationScopeId = Identity<'SynchronizationScopeId'>;
export type AccountId = Identity<'AccountId'>;
export type ServerAccessMode = 'single-user-no-auth' | 'single-user-auth' | 'multi-user-auth';
export type SynchronizationBoundary =
  { readonly kind: 'g2' } | { readonly kind: 'generation'; readonly generation: string };
export type AuthorizedScopeIdentity =
  | {
      readonly authority: 'configured';
      readonly accessMode: 'single-user-no-auth';
      readonly synchronizationScopeId: SynchronizationScopeId;
    }
  | {
      readonly authority: 'authenticated';
      readonly accessMode: 'single-user-auth' | 'multi-user-auth';
      readonly synchronizationScopeId: SynchronizationScopeId;
      readonly accountId: AccountId;
    };

export interface ServerCapabilities {
  readonly contractVersion: '1';
  readonly serverInstanceId: ServerInstanceId;
  readonly accessMode: ServerAccessMode;
  readonly supportedBoundaries: readonly ('g2' | 'generation')[];
  readonly anchoredSnapshots: boolean;
}

export interface SynchronizationBootstrap {
  readonly contractVersion: '1';
  readonly serverInstanceId: ServerInstanceId;
  readonly identity: AuthorizedScopeIdentity;
  readonly population: 'empty' | 'populated';
  readonly boundary: SynchronizationBoundary;
  readonly expectation: string;
}

export interface ConfirmEmptyBindingRequest {
  readonly expectation: string;
  readonly expectedPopulation: 'empty';
}

export interface LocalServerBinding {
  readonly version: '1';
  readonly localDataSetId: LocalDataSetId;
  readonly serverOrigin: string;
  readonly serverInstanceId: ServerInstanceId;
  readonly identity: AuthorizedScopeIdentity;
  readonly boundary: SynchronizationBoundary;
}

export type LocalExchangeStatus =
  | 'offline'
  | 'binding_pending'
  | 'bound'
  | 'auth_required'
  | 'binding_mismatch'
  | 'revoked'
  | 'reconciliation_required'
  | 'scope_deleted'
  | 'disconnected';

export type LocalExchangeState =
  | {
      readonly state: LocalExchangeStatus;
      readonly exchangeFence: string;
      readonly logoutPending: false;
    }
  | {
      readonly state: 'auth_required';
      readonly exchangeFence: string;
      readonly logoutPending: true;
    };

export type AccessError =
  | { readonly code: 'AUTHENTICATION_REQUIRED'; readonly message: 'Authentication is required.' }
  | { readonly code: 'ACCESS_DENIED'; readonly message: 'Access is denied.' }
  | {
      readonly code: 'BINDING_PRECONDITION_FAILED';
      readonly message: 'The binding precondition no longer holds.';
    }
  | { readonly code: 'ACCESS_UNAVAILABLE'; readonly message: 'The access service is unavailable.' };

export interface AccessContractValues {
  LocalDataSetId: LocalDataSetId;
  ServerInstanceId: ServerInstanceId;
  SynchronizationScopeId: SynchronizationScopeId;
  AccountId: AccountId;
  ServerAccessMode: ServerAccessMode;
  ExchangeExpectation: string;
  ServerOrigin: string;
  SynchronizationBoundary: SynchronizationBoundary;
  AuthorizedScopeIdentity: AuthorizedScopeIdentity;
  ServerCapabilities: ServerCapabilities;
  SynchronizationBootstrap: SynchronizationBootstrap;
  ConfirmEmptyBindingRequest: ConfirmEmptyBindingRequest;
  LocalServerBinding: LocalServerBinding;
  LocalExchangeState: LocalExchangeState;
  AuthenticationRequiredError: Extract<AccessError, { code: 'AUTHENTICATION_REQUIRED' }>;
  AccessDeniedError: Extract<AccessError, { code: 'ACCESS_DENIED' }>;
  BindingPreconditionFailedError: Extract<AccessError, { code: 'BINDING_PRECONDITION_FAILED' }>;
  AccessUnavailableError: Extract<AccessError, { code: 'ACCESS_UNAVAILABLE' }>;
}

const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const modes = ['single-user-no-auth', 'single-user-auth', 'multi-user-auth'];
const states = [
  'offline',
  'binding_pending',
  'bound',
  'auth_required',
  'binding_mismatch',
  'revoked',
  'reconciliation_required',
  'scope_deleted',
  'disconnected',
];

const validators: Record<keyof AccessContractValues, (value: unknown) => boolean> = {
  LocalDataSetId: uuid,
  ServerInstanceId: uuid,
  SynchronizationScopeId: uuid,
  AccountId: uuid,
  ServerAccessMode: (value) => modes.includes(typeof value === 'string' ? value : ''),
  ExchangeExpectation: expectation,
  ServerOrigin: origin,
  SynchronizationBoundary: boundary,
  AuthorizedScopeIdentity: identity,
  ServerCapabilities: capabilities,
  SynchronizationBootstrap: bootstrap,
  ConfirmEmptyBindingRequest: (value) =>
    object(value, ['expectation', 'expectedPopulation']) &&
    expectation(value['expectation']) &&
    value['expectedPopulation'] === 'empty',
  LocalServerBinding: binding,
  LocalExchangeState: exchange,
  AuthenticationRequiredError: (value) =>
    error(value, 'AUTHENTICATION_REQUIRED', 'Authentication is required.'),
  AccessDeniedError: (value) => error(value, 'ACCESS_DENIED', 'Access is denied.'),
  BindingPreconditionFailedError: (value) =>
    error(value, 'BINDING_PRECONDITION_FAILED', 'The binding precondition no longer holds.'),
  AccessUnavailableError: (value) =>
    error(value, 'ACCESS_UNAVAILABLE', 'The access service is unavailable.'),
};

export function isAccessContractSchema(value: string): value is keyof AccessContractValues {
  return Object.hasOwn(validators, value);
}

export function parseAccessContract<Name extends keyof AccessContractValues>(
  name: Name,
  value: unknown,
): AccessContractValues[Name] {
  if (!Object.hasOwn(validators, name) || !validators[name](value)) {
    throw new Error('The access contract is invalid.');
  }
  return value as AccessContractValues[Name];
}

function object(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key))
  );
}

function uuid(value: unknown): boolean {
  return typeof value === 'string' && canonicalUuid.test(value);
}

function expectation(value: unknown): boolean {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,256}$/.test(value);
}

function origin(value: unknown): boolean {
  return (
    typeof value === 'string' && value.length <= 2048 && /^https?:\/\/[a-z0-9.[\]:%-]+$/.test(value)
  );
}

function boundary(value: unknown): boolean {
  return (
    (object(value, ['kind']) && value['kind'] === 'g2') ||
    (object(value, ['kind', 'generation']) &&
      value['kind'] === 'generation' &&
      typeof value['generation'] === 'string' &&
      value['generation'].length > 0)
  );
}

function identity(value: unknown): boolean {
  return (
    (object(value, ['authority', 'accessMode', 'synchronizationScopeId']) &&
      value['authority'] === 'configured' &&
      value['accessMode'] === 'single-user-no-auth' &&
      uuid(value['synchronizationScopeId'])) ||
    (object(value, ['authority', 'accessMode', 'synchronizationScopeId', 'accountId']) &&
      value['authority'] === 'authenticated' &&
      (value['accessMode'] === 'single-user-auth' || value['accessMode'] === 'multi-user-auth') &&
      uuid(value['synchronizationScopeId']) &&
      uuid(value['accountId']))
  );
}

function capabilities(value: unknown): boolean {
  return (
    object(value, [
      'contractVersion',
      'serverInstanceId',
      'accessMode',
      'supportedBoundaries',
      'anchoredSnapshots',
    ]) &&
    value['contractVersion'] === '1' &&
    uuid(value['serverInstanceId']) &&
    validators.ServerAccessMode(value['accessMode']) &&
    Array.isArray(value['supportedBoundaries']) &&
    value['supportedBoundaries'].length >= 1 &&
    value['supportedBoundaries'].length <= 2 &&
    value['supportedBoundaries'].every((item: unknown) => item === 'g2' || item === 'generation') &&
    new Set(value['supportedBoundaries']).size === value['supportedBoundaries'].length &&
    typeof value['anchoredSnapshots'] === 'boolean'
  );
}

function bootstrap(value: unknown): boolean {
  return (
    object(value, [
      'contractVersion',
      'serverInstanceId',
      'identity',
      'population',
      'boundary',
      'expectation',
    ]) &&
    value['contractVersion'] === '1' &&
    uuid(value['serverInstanceId']) &&
    identity(value['identity']) &&
    (value['population'] === 'empty' || value['population'] === 'populated') &&
    boundary(value['boundary']) &&
    expectation(value['expectation'])
  );
}

function binding(value: unknown): boolean {
  return (
    object(value, [
      'version',
      'localDataSetId',
      'serverOrigin',
      'serverInstanceId',
      'identity',
      'boundary',
    ]) &&
    value['version'] === '1' &&
    uuid(value['localDataSetId']) &&
    origin(value['serverOrigin']) &&
    uuid(value['serverInstanceId']) &&
    identity(value['identity']) &&
    boundary(value['boundary'])
  );
}

function exchange(value: unknown): boolean {
  return (
    object(value, ['state', 'exchangeFence', 'logoutPending']) &&
    typeof value['state'] === 'string' &&
    states.includes(value['state']) &&
    typeof value['exchangeFence'] === 'string' &&
    /^[1-9][0-9]*$/.test(value['exchangeFence']) &&
    (value['logoutPending'] === false ||
      (value['logoutPending'] === true && value['state'] === 'auth_required'))
  );
}

function error(value: unknown, code: string, message: string): boolean {
  return (
    object(value, ['code', 'message']) && value['code'] === code && value['message'] === message
  );
}
