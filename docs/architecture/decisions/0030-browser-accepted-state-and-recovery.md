# ADR-0030: Browser accepted state and recovery completion

- Status: Accepted

## Context

The technical browser projection previously combined a pending local value with the latest pulled
server revision. A delayed acknowledgement could clear the outbox while leaving an older value labelled
with a newer revision. The incremental cursor had already advanced past the discarded server value.
Local edits made during the pull phase could also lose their recovery request.

## Decision

Persist the latest accepted live server record separately in Dexie's `acceptedTechnicalRecords` table.
The existing `technicalRecords` table remains the visible projection; the outbox retains exact pending
intent and existing tombstone/conflict tables retain deletion evidence. This separation is justified by
response ordering and recovery behavior and introduces no additional database or provider.

Pulls and acknowledgements update accepted state monotonically. Equal accepted revisions must contain
equal values. Removing an acknowledged operation and rebuilding its visible projection happen in one
transaction. Remaining local intent keeps its visible value or deletion; otherwise the accepted server
value becomes visible. A dependent operation derives its expected revision from its own predecessor's
receipt, never from a newer unrelated server revision. Tombstones remove the accepted live projection
and continue to prevent identity resurrection.

Dexie version 5 preserves existing projections, outbox operations, receipts, conflicts, tombstones, and
normal cursors. Legacy databases with synchronization evidence receive an accepted-state repair marker.
Do not infer accepted values from legacy projections: they may already contain the inconsistency.
Before uploading, replay complete G2 history from the beginning, persist accepted values and a separate
repair cursor transactionally, and preserve visible live projections until the last repair page. That
page publishes the rebuilt live projections and the normal cursor atomically. Deletion evidence remains
applicable during repair. Offline periods, page failures, and reloads preserve repair progress and local
intent. New databases need no repair.

This repair relies on the current G2 runtime retaining complete history. It is not a replacement for
G3/G4 generation-aware reconciliation after compaction, nor an implicit claim of production readiness.

Every successful local mutation records a recovery request. Requests received during a running exchange
are coalesced. After the last pull page, recovery checks eligible pending operations and new requests,
then returns to pushing when necessary. Finalization also preserves requests arriving during lease
release. Retained conflicts and unresolved successors do not cause a busy loop. Offline observations,
retry eligibility, exhaustion, and permanent failures continue to stop automatic work as ADR-0027
requires; a new local edit does not reset a retry cycle.

## Validation criteria

- Pull revision 2 before acknowledging revision 1; the final value and revision both represent revision 2.
- Preserve dependent replacement/deletion intent and its predecessor-derived expected revision.
- Reject contradictory equal revisions without advancing the cursor or consuming a committed reservation.
- Repair populated legacy state across offline operation, failed pages, and database reopen before upload.
- Drain creates, replacements, and deletions committed during pulling or lease release.
- Coalesce edits across shared browser contexts without looping over retained conflicts.
