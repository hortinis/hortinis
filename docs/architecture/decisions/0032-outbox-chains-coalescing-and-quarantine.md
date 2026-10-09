# ADR-0032: Outbox chains, coalescing, and quarantine

- Status: Accepted
- Date: 2026-10-08

## Context

The G2 browser supports one pending create and one dependent replacement or deletion. A third offline
edit fails. Permanently rejected requests remain at the head and block independent records. The large
persistence component combines local commits, acknowledgements, conflicts, projections, and pull pages.

Coalescing changes operation bodies. The server's structural idempotency comparison makes a changed
body under a previously submitted identifier an `OPERATION_ID_REUSED` conflict, even after an
acknowledgement was lost. Existing browser rows have no trustworthy submission marker.

## Decision

Maintain an arbitrarily long linear causal chain per record. Each dependent replacement or deletion
retains its immediate `predecessorOperationId`. Reject forks and cycles; missing predecessor evidence
blocks submission with a fixed diagnostic. Identifier ordering only chooses between independent eligible
chains. Derive a successor's expected revision from its own predecessor's accepted live receipt during
submission preparation, never from a newer pulled record. A deletion terminates the chain.

Only the unsent tail may coalesce. Preserve that tail's identifier and original dependency or expected
revision. A create followed by replacement keeps the create with the latest value; repeated replacements
keep one replacement; replacement followed by deletion keeps one deletion; unsent create followed by
deletion cancels the create and visible projection. Cancellation returns `{ status: 'cancelled', recordId }`
and produces no receipt, pending-deletion base, or server tombstone. UUID generation continues to give
later creates fresh identities. Submitted, conflicted, rejected, and unknown legacy operations cannot be
rewritten. Later edits append a dependent successor when the tail cannot coalesce.

Before dispatch, one lease-fenced IndexedDB transaction re-reads eligible work, resolves any dependency,
reserves the existing bounded retry attempt, and persists `submittedAt` using the injected clock. That
marker records a conservative dispatch boundary: interruption before the actual network call still
freezes the envelope. All later attempts retain its exact wire identity, kind, value, and revision.
Failure to reserve or persist prevents dispatch and rolls back both writes. Recheck ownership before the
network call. Serialize this transaction against local commits from every browser context. Local
commits themselves require no synchronization lease. Explicit wire construction excludes all local
metadata for creates, replacements, and deletions.

Dexie version 6 adds the predecessor index and `rejectedOperations` store. Preserve version-5 values,
receipts, cursors, accepted-state repair progress, conflicts, tombstones, leases, and retry history.
Legacy ready rows receive `legacySubmissionUnknown: true`: they may already have reached the server,
so migration never coalesces or changes their wire bodies. Deferred rows with a null revision could not
have satisfied the old request contract and remain unsent. Do not invent historical submission times.
Version 5 remains the accepted-state repair migration selected by ADR-0030.

Quarantine an operation on a push request-boundary rejection or a recognized `INVALID_REQUEST`,
`OPERATION_ID_REUSED`, or `RECORD_ALREADY_EXISTS` protocol response. Existing revision and deletion
conflicts keep their established stores and semantics. Validate correlated rejection identifiers. An
atomic, lease-fenced transition stores the exact local operation, fixed category, and available local
projection or deletion base; removes the operation from automatic submission; and clears its retry
reservation. Duplicate matching rejection is harmless; mismatches fail without partial writes.

Continue recovery for independent records and then pull. Preserve causal descendants as blocked,
without rebasing them or replaying quarantined work. Preserve rejected local intent when projecting
later accepted live records. Keep tombstone retirement rules. Timeouts, malformed or unexpected
responses, and response-boundary failures do not prove permanent rejection and remain outside
quarantine. Infrastructure and repeatable commit failures keep ADR-0027's existing bounded recovery.
Quarantine storage failure never consumes the operation.

Expose a separate observable `rejections` summary alongside synchronization activity: `clear` or
`rejected` plus a count, or `unavailable` when observation fails. Observe persisted state across reopen
and browser contexts, and dispose observation with the owning Angular service. Aggregate completion
means eligible work finished; it does not clear retained rejection evidence. Diagnostics expose fixed
categories and counts, never values, response bodies, or exception messages.

`Retry now` resets an eligible retry cycle; it does not requeue rejected operations, alter submitted
bodies, or invent a missing accepted predecessor. Local edits can retain further dependent intent.
Inspection uses the retained rejection records. Correction requires an explicit recovery decision and
new operation identity after establishing the accepted base; the technical shell has no correction UI.
Generation-aware indeterminate outcomes and full reconciliation remain G3/G4 work, with different scope
stopping rules under ADR-0026. H6 does not reinterpret them as request rejection.

Keep a small concrete persistence facade. Dedicated collaborators own local commits, outbox preparation,
accepted results, conflicts, pulled pages and tombstones, projections, and rejection storage. A shared
transaction helper checks ownership while workflows explicitly list their participating tables. Return
values from transaction callbacks rather than assigning outer variables. Framework-independent rules
own chain validation, coalescing decisions, and rejection categories. Retain the existing dependencies
and production file limits of 350 nonblank/noncomment lines and complexity 15; remove the monolithic
persistence exception.

## Consequences

- Repeated offline editing no longer depends on a two-operation limit.
- Unsent edits may collapse; operations that may have reached the server remain immutable.
- A permanently rejected record no longer blocks independent synchronization or hides retained intent.
- Legacy replay safety takes precedence over maximizing migration-time coalescing.
- No HTTP contract, backend schema, provider, or new dependency is needed.

## Validation criteria

- Exercise all four coalescing rules, transaction rollback, and explicit cancellation results.
- Serialize competing-tab edits and submission preparation without mismatched acknowledgements.
- Persist reservation and submission metadata before dispatch; rollback both when preparation fails.
- Preserve lost-acknowledgement replay across edits, reload, and ownership transfer.
- Drain long chains independently of identifier order and derive revisions from predecessor receipts.
- Preserve populated version-5 stores and immutable unknown legacy envelopes.
- Quarantine recognized rejection, retain descendants and local intent, and continue independent work.
- Keep uncertain responses outside quarantine and preserve duplicate/mismatched outcome behavior.
- Compare deterministic model sequences against retained intent and immutable server replay.
- Verify three offline edits and lost acknowledgement with later edits through the real topology,
  checking final browser/server state and exactly one journal entry per operation identity.
