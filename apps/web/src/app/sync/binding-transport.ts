import type {
  ServerCapabilities,
  SynchronizationBootstrap,
  ConfirmEmptyBindingRequest,
  AccessError,
} from './access-contracts.rule';

export interface BindingTransport {
  readonly serverOrigin: string;
  capabilities(): Promise<ServerCapabilities>;
  bootstrap(expectation?: string): Promise<SynchronizationBootstrap>;
  confirm(request: ConfirmEmptyBindingRequest): Promise<SynchronizationBootstrap>;
}

export class SynchronizationAccessError extends Error {
  constructor(readonly body: AccessError) {
    super(body.message);
  }
}
