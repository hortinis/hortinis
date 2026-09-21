# ADR-0025: Anchored reconciliation snapshots and continuation

- Status: Accepted

## Context

When incremental history is unavailable, ADR-0010 requires a full reconciliation against an internally
consistent server snapshot. Reading mutable records page by page is unsafe: inserts, updates, and
deletions accepted while pages are transferred can be skipped or observed twice. A snapshot also needs a
precise handoff back to incremental synchronization.

## Decision

The server exposes an explicit reconciliation session. Starting a session captures, within one server
consistency boundary:

- the active synchronization generation;
- an anchor server sequence;
- the complete record-presence view at that sequence;
- an opaque snapshot cursor for its first page; and
- an expiry time governed by ADR-0023.

The implementation may materialize the view or use another PostgreSQL mechanism, but every page in the
session must represent the same anchor. It must not hold an unbounded client-controlled database
transaction open between HTTP requests.

Snapshot pages contain a closed union of live records and retained technical tombstones. Pages repeat the
session identifier, generation, and anchor sequence so accidental cross-session composition is rejected.
Continuation cursors are opaque, session-bound, non-empty values. A non-final page has `hasMore: true`
and exactly one `nextCursor`. The final page has `hasMore: false`, no snapshot cursor, and exactly one
opaque `incrementalCursor` positioned strictly after the anchor sequence.

Clients persist snapshot progress only after atomically applying a complete page. They may resume an
unexpired session with its persisted cursor. A malformed cursor returns `INVALID_REQUEST`; a valid cursor
for an expired or different session returns `SNAPSHOT_EXPIRED` and requires a new session. Restarting a
session is safe because no local pending operation or last-synchronized base is removed while a snapshot
is in progress.

After the final page and local reconciliation commit atomically, the client uses the supplied incremental
cursor. Every change accepted after the anchor is then returned exactly once through normal incremental
pull semantics. A client must not construct an incremental cursor from the numeric anchor.

## Consequences

- Concurrent writes cannot fall between a paginated snapshot and subsequent incremental pull.
- Session storage is bounded by expiry and can be cleaned independently from pending client work.
- The numeric anchor is diagnostic and testable, while continuation remains opaque and server-owned.
- Complete record presence allows compacted tombstones to be represented by absence without ambiguity
  once the entire snapshot has been received.
- G4 implements session persistence, snapshot paging, continuation, and atomic browser application.

## Rejected alternatives

- **Paginate the current mutable projection.** Concurrent writes can be missed.
- **Use the last page's current sequence as continuation.** Writes accepted during transfer can fall
  before that sequence without appearing in the snapshot.
- **Let clients build a cursor from the anchor.** Cursor encoding and retention boundaries are server
  implementation details.
- **Keep one database transaction open across client requests.** Slow or abandoned clients would hold
  resources and database snapshots unpredictably.

## Validation criteria

- All pages in one session have one generation and anchor.
- Reusing a page cursor returns the same page or an equivalent idempotent result.
- Inserting, replacing, and deleting records during transfer changes neither earlier nor later snapshot
  pages and each change appears after the final incremental cursor.
- A final page cannot contain `nextCursor`, and a non-final page cannot contain `incrementalCursor`.
- An expired session leaves local base state and pending operations intact and can be restarted.
