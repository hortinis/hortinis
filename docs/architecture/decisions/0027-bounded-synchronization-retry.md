# ADR-0027: Bounded synchronization retry and manual recovery

- Status: Accepted

## Context

ADR-0010 requires transient synchronization failures to use bounded exponential backoff with jitter.
The V0 synchronization slice deliberately performs only immediate and explicit recovery. Production
recovery needs concrete scheduling, persistence, exhaustion, and manual-recovery behavior without
blocking offline work or turning a browser into an unbounded background worker.

Retry diagnostics must remain privacy-safe. Scheduling state must not copy record values, request or
response bodies, credentials, tokens, precise locations, or other user-provided content.

## Decision

One synchronization work item receives one immediate attempt followed by at most four automatic
retries. The delay before automatic retry number `n`, starting with `n = 1`, is sampled with full jitter
from zero through `min(30 seconds, 1 second * 2^(n - 1))`. Clock, jitter, and scheduling boundaries are
injectable so tests can be deterministic.

An offline observation does not consume an attempt. Service unavailability and a local result or page
commit failure that is safe to repeat consume the budget. Stable operation identifiers and opaque pull
cursors make those retries idempotent. Protocol rejection, invalid boundary data, and malformed or
unexpected responses are not automatically retried because repetition cannot repair them.

The browser persists privacy-safe retry state before scheduling another attempt. State identifies the
synchronization scope, push or pull phase, the operation identifier for push work where applicable, the
attempt count, next eligible time, failure category, and whether the item is exhausted. It contains no
record value or response body. Reload restores the remaining schedule instead of resetting its budget.
A successful work item clears its retry state.

Exhaustion keeps the exact pending work, current projection, conflicts, tombstones, and cursor intact.
It does not block unrelated local transactions. The application exposes scheduled, offline, running,
exhausted, manual-recovery, and completed states. An explicit `Retry now` action clears exhaustion for
the affected work item and starts a new bounded recovery cycle.

Background retry means a non-blocking scheduler while the application is running. G2 does not adopt the
browser Background Sync API, periodic service-worker synchronization, or an assumption that a closed or
suspended browser continues executing.

## Consequences

- Temporary failures recover without tight loops or indefinite automatic traffic.
- Reload cannot bypass an exhausted budget.
- Users and tests can distinguish waiting, offline operation, and permanent failure.
- Manual recovery is explicit and never discards local intent.
- Implementing browser or service-worker background execution later requires a separate decision.

## Rejected alternatives

- **Retry forever.** Permanent failures would remain hidden behind continuous traffic.
- **Keep retry counters only in memory.** Reload would reset the bound and permit unbounded retry.
- **Count offline observations as failed attempts.** Ordinary offline use would exhaust work without a
  request being attempted.
- **Retry every error classification.** Contract and boundary failures require correction rather than
  repetition.
- **Require service-worker Background Sync.** Availability and execution behavior vary by browser and
  are unnecessary for the accepted in-application recovery guarantee.

## Validation criteria

- The immediate attempt and at most four automatic retries reuse the exact operation or cursor.
- Full-jitter delays stay within the selected exponential cap.
- Offline periods consume no attempt and do not block local commits.
- Reload restores a scheduled or exhausted item without resetting its attempt count.
- Exhaustion is observable and leaves pending work intact.
- Manual recovery starts a fresh bounded cycle without changing operation identity or cursor.
- Persisted and logged retry metadata contains no user-provided content.
