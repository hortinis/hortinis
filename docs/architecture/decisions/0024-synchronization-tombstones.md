# ADR-0024: Synchronization tombstones and identifier retirement

- Status: Accepted

## Context

Offline clients can retain a record after another client deletes it. Removing the server row or omitting
the deletion from incremental history would allow that stale copy to reappear. ADR-0010 therefore
requires retained tombstones, while G1 must define when their synchronization payload may be compacted
without allowing resurrection.

Product concepts such as archive, void, correction, or account erasure are separate domain decisions.
This decision covers only the technical propagation of an accepted deletion.

## Decision

An accepted deletion creates a technical tombstone in the same transaction as its idempotency receipt
and monotonic server sequence. A tombstone contains:

- the stable record identifier;
- the final positive record revision;
- the server sequence assigned to the deletion.

The deletion operation requires the current expected revision and participates in the same causal,
idempotency, conflict, and generation rules as replacement. Pull returns the tombstone as an ordered
change. Applying it removes the record from the current local projection while preserving enough base
state and pending work for reconciliation.

A tombstone remains in incremental history for every generation whose valid cursor can predate the
deletion. Its detailed payload may be compacted only after a generation rollover makes those cursors
ineligible for incremental continuation. Full reconciliation treats a complete anchored snapshot as
authoritative for record presence: a record in the client's base that is absent from the complete
snapshot was deleted or otherwise does not exist at the anchor.

The server retains a minimal identifier reservation for the lifetime of the synchronization scope even
after the detailed tombstone is compacted. A create using a reserved identifier fails explicitly and can
never resurrect the deleted identity. The reservation contains no former record value or user-provided
content.

Reusing a human-visible name or creating a new record for the same real-world subject uses a new stable
record identifier. Restoring a deletion, if a future domain workflow permits it, is a new explicit
domain operation and does not erase the original tombstone history.

## Consequences

- Incremental clients observe deletions in sequence order.
- Arbitrarily old clients reconcile against complete record presence rather than requiring detailed
  tombstones forever.
- Stable UUID reservations grow with the number of record identities, but their privacy and storage cost
  is bounded to identifiers and retirement metadata rather than retained content.
- Hard deletion required by a future privacy workflow must define how scope destruction or identifier
  reservation removal remains safe; this ADR does not silently settle that product behavior.
- G2 adds delete operations, tombstone persistence, pull behavior, and identifier reservations.

## Rejected alternatives

- **Delete the row without a tombstone.** Offline clients can resurrect stale data.
- **Retain complete deleted values indefinitely.** Synchronization safety does not justify unbounded
  retention of user content.
- **Permit identifier reuse after a duration.** An old client cannot distinguish reuse from resurrection.
- **Treat archive as deletion.** Archive is a domain lifecycle state and must remain synchronizable as
  ordinary retained data.

## Validation criteria

- Deletion, receipt, revision, sequence, and tombstone commit atomically.
- A pull from before the deletion returns exactly one ordered tombstone and repeated application is
  idempotent.
- A pending replacement is preserved and becomes an explicit conflict when its target is deleted.
- A create using a retired identifier fails after detailed tombstone compaction.
- Reconciliation of a stale base cannot recreate a record missing from the complete anchored snapshot.
