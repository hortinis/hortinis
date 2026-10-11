# S5 implementation plan

## Analysis and accepted implementation boundary

All browser synchronization state currently shares a single unowned Dexie database. Raw table reads,
counts, live queries, and writes are used throughout the already validated projection/outbox workflow.
A physical Dexie database per local data set keeps every such access within one partition without
adding fallible scope predicates or ownership metadata to public operation/result contracts.

Each database receives one durable, offline-generated UUIDv7 in its identity store during the atomic
initial creation. Its name remains a local locator, not an identity or credential. Opening the same
locator in another tab loads the same identity; another database receives a distinct identity. The
root application keeps its `hortinis` locator. Additional data-set selection requires a separate
database/service scope; mutable switching of a live persistence instance is not introduced. S12 owns
the user selection/clone workflow. Extend ADR-0029 with this implementation choice and rationale.

This session assumes fresh databases. Register the new version-seven schema and reject upgrades of
older unowned application databases without assigning their contents to an identity. Preserve their
prior version and pending intent on rejection; reset remains an explicit developer action.

## Implementation sequence

1. Add singleton identity and typed local binding stores to the versioned Dexie schema. Generate identity
   only in the initial populate transaction. Validate identity on open/reopen, retain immutable expected
   identity per connection, and fail without regeneration when identity/schema is missing or inconsistent.
2. Include identity in workflow/retry/coordination transactions. Add `localDataSetId` to leases, check
   it with the persisted identity on acquisition, renewal, release, and response application, and preserve
   existing per-database multi-tab fencing. Validate binding shapes/ownership without storing credentials
   or activating a server binding; S6 implements discovery and first connection.
3. Replace obsolete application-upgrade expectations with deterministic fresh-database rejection tests.
   Test offline creation, reload and concurrent first opens, initial/commit rollback, missing/changed
   identity, colliding record/operation/retry/lease identities across data sets, response fencing,
   complete local-state separation, credential-shaped binding rejection, and independent retry/transport
   behavior. Add browser offline identity/reload evidence while retaining existing smoke/topology checks.
4. Update architecture, privacy/threat, development/reset documentation, and track status. Explain that
   all data sets in one browser origin remain accessible to same-origin scripts/shared-profile users;
   this storage boundary provides no authentication or XSS defense, and IDs are not secrets.
5. Run documented validation, review and correct the implementation, record actual evidence, and commit
   S5 before beginning S6.

## Completion limits

No new framework, database engine, account credential, telemetry, or production product functionality is
introduced. Physical data-set ownership covers records/tombstones, pending/accepted operations, accepted
bases, all conflicts/rejections, synchronization/repair cursors, retries, leases, and local binding.
Server authorization remains independent; binding, account change, cloning, and complete local deletion
remain S6/S12 workflows. No production migration or inferred ownership is authorized.

## Review and validation outcome

Completed on 2026-10-11. Review corrected initial creation-event promise tracking so Dexie tracks
identity writes and rolls them back on a real IndexedDB creation abort. Existing malformed bindings
are preserved and denied rather than overwritten. Array-shaped identity metadata is rejected even
when its named fields match. Foreign data-set leases fail with the ownership-loss control error;
workflow identity is checked in the transaction that commits a result, cursor, retry, or local edit.

Documented `pnpm validate` passed all contract/documentation, frontend quality/build, backend quality/build,
299 web unit, 109 Java unit, 57 PostgreSQL integration, 11 standard Chromium, four real-topology,
and repository checks on the final reviewed revision. The offline Chromium test
waits for a controlled service-worker navigation before disconnecting, after trace review showed that
worker activation alone did not prove completed cache initialization. The obsolete application migration
tests were replaced with deterministic rejection and fresh-schema repair tests; generic migration/rollback
foundation fixtures remain. No migration or inferred ownership is implemented.

Manual review covered every local table's physical boundary, immutable identity, transaction/lease
ordering, raw counts/live queries, binding validation, unchanged public operation/result shapes, safe
diagnostics, and English consistency. The real disposable topology cleaned up successfully. S5 is
validated; S6 can implement explicit server discovery, confirmation, binding activation, and destination
fencing. Authentication, user selection/cloning, complete local deletion, and security/privacy readiness
remain later gates.
