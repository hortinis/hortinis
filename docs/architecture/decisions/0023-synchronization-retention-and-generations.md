# ADR-0023: Synchronization retention, compaction, and generations

- Status: Accepted

## Context

ADR-0010 permits server-side history and idempotency-receipt compaction, but compaction must never make
pending local work appear new, accepted, or disposable. The V0 synchronization slice deliberately keeps
all history and receipts and has no production retention boundary. Production operation submission and
incremental pull therefore need a shared way to identify which retained history makes their result
provable.

Retention must remain practical for an autonomous self-hosted installation. It cannot require an
unbounded change journal or complete operation receipts, and it cannot assume that every browser returns
within a configured period.

## Decision

### Generation boundary

Use one server-controlled, opaque synchronization generation per synchronization scope. A generation is
the proof boundary shared by operation receipts, incremental cursors, change history, and tombstones. It
has no client-visible ordering semantics.

The production operation envelope carries the generation in which the operation was first submitted.
The client persists that generation with the operation before its first submission and never changes it
on retry. Incremental cursors are bound to a generation. Clients must not inspect, construct, or transfer
generation identifiers or cursors between synchronization scopes.

The server atomically rolls the scope to a new generation before removing any history needed to validate
an operation or cursor in the active generation. Normal retention compaction and an exceptional
operator-forced compaction both use this rollover. Rollover is observable operationally but does not
block local client work.

Only one generation accepts newly submitted operations for a scope. For a request from a retired
generation, the server performs an idempotency lookup before returning a terminal result:

1. if a retained receipt proves the operation was accepted, return its stable accepted result;
2. if retained terminal evidence proves it was rejected, return that rejection;
3. if acceptance can no longer be proved either way, return an indeterminate outcome as defined by
   ADR-0026.

The server never interprets an operation from a retired generation as a new operation in the active
generation.

### Retention classes

The supported defaults and minimum normal-operation settings are:

| Retained material | Default | Supported minimum | Rule |
| --- | --- | --- | --- |
| Active-generation incremental changes and tombstones | 90 days | 7 days | Removing required history first rolls the generation. |
| Receipts from retired generations | 30 days | 7 days | A missing receipt produces an indeterminate outcome, never speculative replay. |
| Completed or interrupted reconciliation sessions | 24 hours | 1 hour | Expiry restarts reconciliation from a new anchor. |
| Client pending operations, conflicts, bases, and indeterminate work | Until resolved or explicitly discarded by the user | Not configurable by age | Local intent does not expire automatically. |
| Stable record-identifier reservations | Lifetime of the synchronization scope | Not configurable | A deleted identifier cannot later be treated as a new identity. |

Operators may increase these periods. Reducing a configured period below the supported minimum is
invalid. An explicit emergency rollover may invalidate incremental history earlier than the configured
period, but must preserve the same reconciliation and indeterminate-outcome guarantees and emit a
privacy-safe operational event.

Retention time is measured from server acceptance or session creation, not from a client clock. A
deployment publishes its effective retention settings through operator documentation and diagnostics;
retention durations are not protocol promises that allow clients to delete pending work.

### Required wire behavior

When a cursor or generation is no longer usable, the server returns `RECONCILIATION_REQUIRED` with the
current opaque generation and one of these machine-readable reasons:

- `generation_expired` for a retired generation;
- `history_compacted` when required incremental history is unavailable;
- `cursor_expired` when a cursor cannot be continued in its generation.

This response is distinct from an empty change page and from `INVALID_REQUEST`. An empty page means that
the supplied, valid cursor is current. Invalid syntax remains `INVALID_REQUEST`.

## Consequences

- History size is bounded without placing an expiry on local intent.
- Generation rollover is scope-wide because sequence, history, receipts, and snapshot anchors are
  scope-wide. A device-specific generation would not describe the server's compaction boundary.
- Idempotency lookup must remain available during the retired-receipt grace period.
- Operators can tune storage use within documented bounds, while exceptional early rollover remains a
  visible recovery event rather than silent data loss.
- G2 implements tombstone retention and retry behavior. G3 implements generation-bound operation and
  cursor handling. G4 implements reconciliation sessions. G5 verifies configured and forced rollover.

## Rejected alternatives

- **Expire pending client operations with server retention.** This violates ADR-0010.
- **Treat an old operation as new in the current generation.** A lost acknowledgement could duplicate an
  accepted effect.
- **Use per-device generations.** Compaction applies to scope-wide history and cannot be described safely
  by a device-local epoch.
- **Retain every receipt and change forever.** This makes autonomous operation depend on unbounded
  storage.

## Validation criteria

- A valid current cursor can return an empty page without requesting reconciliation.
- A compacted cursor returns `RECONCILIATION_REQUIRED`, never an empty page.
- An accepted old-generation operation with a retained receipt returns its stable result.
- The same request after receipt removal becomes indeterminate and is not resubmitted automatically.
- Forced and configured rollover preserve pending local operations and emit no user-provided content in
  diagnostics.
