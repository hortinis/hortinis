# ADR-0027: Bounded synchronization retry and manual recovery

- Status: Accepted
- Date: 2026-09-22

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
from zero through `1 second * 2^(n - 1)`. The four upper bounds are therefore 1, 2, 4, and 8 seconds,
with at most 15 seconds of scheduled waiting in one cycle. Clock, jitter, and scheduling boundaries are
injectable so tests can be deterministic.

Each HTTP attempt has a 10-second request timeout. A timeout is service unavailability and consumes an
attempt because a response may have been lost; the stable operation identifier or unchanged pull cursor
makes repetition safe. The timeout and scheduled delays provide bounded recovery for short outages, not
an indefinite background availability mechanism.

`navigator.onLine` is a hint only; request failure remains the authoritative offline signal.

An offline observation does not consume an attempt. Service unavailability and a local result or page
commit failure that is safe to repeat consume the budget. Stable operation identifiers and opaque pull
cursors make those retries idempotent. Protocol rejection, invalid boundary data, and malformed or
unexpected responses are not automatically retried because repetition cannot repair them.

The browser persists privacy-safe retry state before scheduling another attempt. State identifies the
synchronization scope, push or pull phase, the operation identifier for push work where applicable, the
attempt count, next eligible time, failure category, and whether the item is exhausted. It contains no
record value or response body. Reload restores the remaining schedule instead of resetting its budget.
A successful work item clears its retry state in the same transaction that commits its result or page.

Reserve each attempt durably before dispatch, under the current lease, rather than incrementing only
when a failure returns. An in-flight marker and request deadline preserve the reservation across reload
or ownership transfer. Interrupted requests remain counted because their server acceptance may be
unknown. After an interrupted fifth attempt, automatic recovery is exhausted until manual retry.
Failure settlement replaces the in-flight marker with its bounded jitter schedule or exhaustion; offline
observations reserve no attempt. Non-retryable responses stop the cycle without scheduling repetition.
Lease-fenced transaction checks apply to reservation, settlement, reset, and deletion. A former owner
cannot change the successor's retry state.

An online event resumes the existing non-exhausted cycle without resetting its attempt count. Window
focus does not start or reset recovery. Neither online nor focus events clear exhaustion; only the
explicit `Retry now` action starts a fresh cycle. Browser tabs coordinate recovery through the bounded
lease selected by ADR-0028, and ownership transfer preserves the same retry record.

Exhaustion keeps the exact pending work, current projection, conflicts, tombstones, and cursor intact.
It does not block unrelated local transactions. The application exposes scheduled, offline, running,
exhausted, manual-recovery, and completed states. An explicit `Retry now` action clears exhaustion for
the affected work item and starts a new bounded recovery cycle.

Background retry means a non-blocking scheduler while the application is running. G2 does not adopt the
browser Background Sync API, periodic service-worker synchronization, or an assumption that a closed or
suspended browser continues executing.

## Browser implementation boundary

A framework-independent retry rule owns the five-attempt limit and bounded jitter calculation. The
existing persistence module re-exports the attempt limit for compatibility; retry record shape and
storage schema are unchanged. A retry controller restores interrupted or scheduled work and persists
failure settlement before the trigger component schedules its next run.

Failure classification uses explicit origins. Recognized transport errors retain their categories;
result/page commit failures retain their repeatable local-persistence category. Persistence reads and
retry mutations identify storage-origin failures without classifying every JavaScript `Error` as a
storage failure. Unrelated errors, including invalid injected jitter, publish `unknown` and do not
schedule automatic repetition. Ownership loss and retry-eligibility errors retain their distinct control
flow and cannot become local-persistence failures. Status contains fixed categories and counts, never
error bodies or user content.

## Consequences

- Temporary failures recover without tight loops or indefinite automatic traffic.
- Reload cannot bypass an exhausted budget.
- Users and tests can distinguish waiting, offline operation, and permanent failure.
- Manual recovery is explicit and never discards local intent.
- One cycle covers at most 15 seconds of scheduled delay plus the bounded request durations.
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
- Reload restores a scheduled, in-flight, or exhausted item without resetting its attempt count.
- Ownership takeover cannot submit a sixth attempt or clear exhaustion without manual recovery.
- Exhaustion is observable and leaves pending work intact.
- Manual recovery starts a fresh bounded cycle without changing operation identity or cursor.
- Persisted and logged retry metadata contains no user-provided content.
