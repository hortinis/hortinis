# ADR-0028: Browser synchronization coordination

- Status: Accepted

## Context

Multiple browser tabs share one IndexedDB synchronization scope. In-memory single-flight flags protect
one Angular service instance only, so two tabs can submit, pull, schedule retries, and commit responses
concurrently. A tab may also close or become suspended while it owns recovery. Coordination must not
block ordinary offline edits or rely on a suspended tab executing cleanup code.

## Decision

Coordinate recovery with a short, renewable lease stored in the same Dexie database as synchronization
state. The lease identifies the synchronization scope, an ephemeral tab owner, a monotonically
increasing fencing token, and an expiry time. One owner renews the lease while recovery is active. A
different tab may take ownership after expiry; releasing an older fencing token cannot remove a newer
lease. Release expires the row instead of deleting it so the fencing counter remains monotonic across
normal recovery runs.

Renewal rejects expired ownership and reports failure or storage uncertainty to the running exchange.
Each new request verifies ownership and retry eligibility. After awaiting a response, every receipt,
conflict, accepted projection, page/cursor, and retry-state mutation checks owner, token, and expiry
inside the same IndexedDB transaction as the mutation. Local lease-expiry checks also run immediately
before dispatch. Ownership loss stops the run with an explicit `ownership-lost` outcome; it is not a
retryable local commit failure. A stale response is discarded, and stable operation identity permits
the current owner to recover an accepted server result through replay. Already dispatched requests may
outlive their lease; browser cancellation cannot prove whether the server accepted them.

Local record and outbox transactions do not require the lease. Accepted-operation commits remain
idempotent. Pull commits compare the cursor used for the request with the cursor still stored inside the
commit transaction, so a response from an expired owner cannot move the boundary behind work committed
by its successor. A tab that observes another owner schedules a bounded attempt at the lease expiry.

The lease contains no record values, request or response bodies, credentials, tokens, locations, or
other user-provided content. The owner identifier is generated for one application instance and has no
meaning outside local coordination.

## Consequences

- Closing a tab releases its lease when normal cleanup runs; expiry provides recovery when cleanup does
  not run.
- Suspending a tab stops renewal and permits another tab to continue after the bounded lease duration.
- Duplicate accepted responses remain harmless, while a stale pull response cannot regress the cursor.
- Local editing remains available regardless of lease ownership.
- Coordination depends only on the already selected IndexedDB/Dexie boundary and introduces no browser
  lock API or background-execution assumption.

## Rejected alternatives

- **Service-instance flags only.** They do not coordinate tabs.
- **Web Locks API ownership.** A suspended owner can retain a lock without executing a renewal or
  bounded handoff policy.
- **Permanent leader election.** Recovery needs bounded ownership, not a long-lived privileged tab.
- **Coordinate local edits.** Editing must remain independent of network recovery ownership.

## Validation criteria

- Two tabs cannot own one synchronization scope concurrently before lease expiry.
- Expiry transfers ownership with a higher fencing token.
- A former owner cannot release its successor's lease, commit a stale response, or mutate retry state.
- Release and reacquisition retain an increasing fencing token.
- Renewal failure or expiry stops further work; exhausted requests require manual recovery.
- Closing or suspending the owner does not permanently block recovery.
- Local commits succeed while another tab owns recovery.
- Persisted coordination metadata contains no user-provided content.
