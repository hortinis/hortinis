# ADR-0010: Synchronization protocol model

- Status: Accepted

## Context

Offline clients must retry safely, exchange locally retained changes after an unbounded period without connectivity, preserve deletions, and surface concurrent edits without silently losing accepted local work. Server-side history may be compacted, but compaction cannot make pending local work expire or become unrecoverable.

## Decision

- Use an application-level operation journal rather than introducing a generic CRDT engine.
- Generate stable UUIDv7 record and operation identifiers on clients.
- Commit a local state change and its outbox operation atomically.
- Retain each pending operation locally until the client receives and persists its stable server result.
- Order operations from the same replica that affect the same record as a causal chain. Do not submit a dependent operation until its predecessor has a stable result, unless the protocol submits the chain as one atomic ordered batch.
- Include the expected server revision when an operation changes an existing synchronized record. Derive a dependent operation's expected revision from its predecessor's accepted result, persist it before the operation's first submission, and keep it unchanged across retries.
- Give every submitted operation a stable idempotency identifier. A retry must reuse the same identifier and canonical request; reusing an identifier with a different request is an error.
- Assign accepted server changes a monotonic sequence within their synchronization scope and pull them with an opaque cursor.
- Represent deletions with retained tombstones.
- Return concurrent changes as explicit conflicts; do not resolve them silently with last-write-wins.
- Retry transient failures with bounded exponential backoff and jitter.
- Associate incremental synchronization state and submitted operations with a synchronization generation. Reject operations from a generation whose required server history has been compacted and require full reconciliation instead of treating them as new submissions.
- Anchor every full reconciliation to a server sequence. Read an internally consistent snapshot at that sequence, then resume incremental synchronization strictly after it so writes accepted while the snapshot is transferred cannot be missed.
- During full reconciliation, compare the anchored server snapshot with the client's last-synchronized base and pending operation journal. Preserve pending local intent, merge non-overlapping changes, and represent concurrent changes as explicit conflicts.
- If the server can no longer prove whether a pending operation was accepted, represent the operation as having an indeterminate outcome and retain its local intent for explicit reconciliation. Do not automatically resubmit it under a new generation or silently treat it as accepted.

## Consequences

- Replaying an operation while its synchronization generation remains valid produces the same observable result.
- The service must atomically persist idempotency, accepted state, the resulting revision and sequence, and the change journal. It must reject the same idempotency identifier with a different canonical request.
- The client must retain enough last-synchronized base state to perform full reconciliation after incremental history is no longer available.
- Server compaction may remove the incremental synchronization path, but it must not cause pending local work to be silently discarded, duplicated, or overwritten.
- Full reconciliation requires a point-in-time snapshot and a continuation cursor from the same synchronization boundary; a best-effort traversal of mutable records is insufficient.
- Idempotency receipts may be compacted, but an operation with an indeterminate outcome remains recoverable as explicit local reconciliation work rather than being replayed speculatively.
- Retention, synchronization-generation, tombstone, and full-reconciliation policies must be defined and tested before compaction or hard deletion is introduced.
- User-facing conflict resolution and domain-specific merge rules will be designed with the first relevant business workflow.
- Protocol tests must cover sequential offline edits to one record, a lost acknowledgement followed by retry, duplicate identifiers with different requests, writes concurrent with a paginated full snapshot, reconnection after server history has been compacted, and a lost acknowledgement followed by idempotency compaction and generation rollover.
- Protocol behavior will be documented and tested before feature code relies on it.
