import type {
  ChangePage,
  OperationResult,
  RecordIdentifierRetiredError,
  RecordNotFoundError,
  RevisionConflictError,
  TechnicalRecordOperation,
} from './conformance';
import type {
  SynchronizationRetryPhase,
  SynchronizationRetryFailureCategory,
} from '../persistence/local-synchronization-retry-state';

export type PushOutcome =
  | { status: 'busy' }
  | { status: 'ownership-lost' }
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'accepted'; operation: TechnicalRecordOperation; result: OperationResult }
  | {
      status: 'conflict';
      operation: TechnicalRecordOperation;
      conflict: RevisionConflictError | RecordNotFoundError | RecordIdentifierRetiredError;
    }
  | {
      status: 'failed';
      operation: TechnicalRecordOperation;
      error: unknown;
      reason: SynchronizationFailureReason;
      retryCategory?: SynchronizationRetryFailureCategory;
    };

export type PullOutcome =
  | { status: 'busy' }
  | { status: 'ownership-lost' }
  | { status: 'empty' }
  | { status: 'offline' }
  | { status: 'applied'; page: ChangePage }
  | {
      status: 'failed';
      error: unknown;
      reason: SynchronizationFailureReason;
      retryCategory?: SynchronizationRetryFailureCategory;
    };

export type RecoveryOutcome =
  | { status: 'completed'; pushed: number; pulled: number }
  | { status: 'offline'; pushed: number; pulled: number }
  | { status: 'failed'; pushed: number; pulled: number; error: unknown }
  | {
      status: 'scheduled';
      pushed: number;
      pulled: number;
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      nextEligibleAt: number;
    }
  | {
      status: 'exhausted';
      pushed: number;
      pulled: number;
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      reason: SynchronizationRetryFailureCategory;
    }
  | { status: 'already-running' }
  | { status: 'ownership-lost' };

export type SynchronizationFailureReason =
  'unavailable' | 'protocol' | 'boundary' | 'unexpected-response' | 'local-persistence' | 'unknown';

export type SynchronizationStatus =
  | { status: 'ownership-lost' }
  | { status: 'idle' }
  | { status: 'synchronizing' }
  | { status: 'manual-recovery' }
  | { status: 'offline' }
  | {
      status: 'scheduled';
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      nextEligibleAt: number;
    }
  | {
      status: 'exhausted';
      phase: SynchronizationRetryPhase;
      attemptCount: number;
      reason: SynchronizationRetryFailureCategory;
    }
  | { status: 'failed'; reason: SynchronizationFailureReason }
  | { status: 'completed'; pushed: number; pulled: number };
