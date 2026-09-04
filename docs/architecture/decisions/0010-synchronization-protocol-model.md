# ADR-0010: Synchronization protocol model

- Status: Accepted

## Context

Offline clients must retry safely, exchange changes after long disconnections, preserve deletions, and surface concurrent edits without silently losing accepted local work.

## Decision

- Use an application-level operation journal rather than introducing a generic CRDT engine.
- Generate stable UUIDv7 record and operation identifiers on clients.
- Commit a local state change and its outbox operation atomically.
- Give every submitted operation a stable idempotency identifier.
- Include the expected server revision when an operation changes an existing synchronized record.
- Assign accepted server changes a monotonic sequence within their synchronization scope and pull them with an opaque cursor.
- Represent deletions with retained tombstones.
- Return concurrent changes as explicit conflicts; do not resolve them silently with last-write-wins.
- Retry transient failures with bounded exponential backoff and jitter.

## Consequences

- Replaying the same operation produces the same observable result.
- The service must atomically persist idempotency, accepted state, and the change journal.
- Tombstone retention and replica recovery policies must be defined before hard deletion is introduced.
- User-facing conflict resolution and domain-specific merge rules will be designed with the first relevant business workflow.
- Protocol behavior will be documented and tested before feature code relies on it.

