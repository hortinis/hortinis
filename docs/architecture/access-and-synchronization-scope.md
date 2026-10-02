# Access and synchronization-scope specification

- Status: Accepted design for S1; runtime implementation remains planned.
- Decision: [ADR-0029](decisions/0029-access-and-synchronization-scope.md).
- Owners: [S2 through S15](../development/access-and-synchronization-scope-implementation-plan.md),
  P0.6 for export/restore, and the applicable foundation/deployment gates.

## Identity and access invariants

| Identity | Authority | Lifetime and use |
| --- | --- | --- |
| `localDataSetId` | Browser, without network access | Retained with its data; partitions projections, outbox, accepted results, bases, conflicts, cursors, retry state, leases, and binding. |
| `serverInstanceId` | Controlled server setup/restore | Stable through restart and forward migration; replaced before serving rolled-back, reset, or replacement synchronization state. Non-secret. |
| `synchronizationScopeId` | Server provisioning | Scope lifetime; partitions every server synchronization structure. Never reused after destruction. Non-secret. |
| `accountId` | Server account provisioning | Account lifetime; owns one explicit scope relationship. Never reused. Not proof of identity from a browser. |
| `deviceId` | Authorized enrollment | Enrollment lifetime; binds applicable sessions, listing, and revocation. Never owns garden data. |
| `sessionId` | Server session management | Rotated and revoked server-side credential; protected cookie only, never persisted in IndexedDB or script-readable storage. |

A data request receives an immutable `SynchronizationAccessContext` after configuration/session
validation and before any workflow or existence lookup. It identifies the server-authorized scope and
the access authority; authenticated contexts also identify the account and, once S11 exists, enrolled
device. Controllers delegate resolution, services require the context, and persistence rejects absent
or inconsistent scope context. Public DTOs cannot construct it. It is never logged or serialized as a
credential-bearing object. Background jobs use an explicitly authorized internal context.

Device enrollment requires explicit authorization for the owning account, a bounded one-time
replay-resistant handshake, and a fresh device identity/session. A supplied device ID does not prove
enrollment. Recovery cannot grant weaker access than the accepted account-authentication policy.
S8/S11 select the concrete mechanism and limits; until implemented, deny enrollment rather than
accepting a browser-declared device. Enrolled devices access the account's existing scope, not a copy
or a newly inferred scope.

An ordinary gardener may read, submit, and reconcile only their own scope. There are no shared-garden
roles or per-record sharing permissions in the first release. A record's mutable fields cannot change
ownership. Operator setup, provisioning, mode changes, recovery, and deletion are separate privileged
functions with explicit authorization. Operator access does not automatically expose garden content.

All server keys and constraints use the scope where applicable, including record and operation
identity, receipts, changes, sequence allocation, tombstones, retired IDs, locks, cursors, generations,
and snapshots. The same record/operation IDs may exist independently in two scopes. Untrusted IDs
never cause a lookup outside the authorized scope. Cursors and generations are opaque, non-secret
protocol state, bound to their instance and scope and validated against that context on every use.

## Configuration and provisioning

| Mode | Trusted resolution | Provisioning |
| --- | --- | --- |
| `single-user-no-auth` | One internal scope for every caller | Controlled setup provisions exactly one scope. No account is inferred. |
| `single-user-auth` | Valid sole account owns the internal scope | Controlled bootstrap or explicit claim of an existing scope. |
| `multi-user-auth` | Valid account owns its scope | Account and scope are provisioned together in one transaction. |

There is no implicit production default or fallback. Invalid/absent configuration, missing required
authentication, ownership mismatch, multiple scopes in a single-user mode, unavailable security state,
or an unsupported transition prevents data service startup/access. Public health responses remain
status-only. A fresh no-auth installation requires explicit selection and a documented compensating
access boundary before real-data use. Origin/CSRF controls apply to its mutations too: no-auth does not
make browser requests trustworthy.

Persist the last committed mode with installation state. Startup validates configured mode against
that state; editing a configuration value cannot bypass a transition procedure. S4 can deny transitions
before S12 implements them. Never ship a production scope-impersonation request field or test resolver.
Scope creation occurs only during authorized setup/provisioning, never in normal push/pull requests.

## Local binding and safe first connection

One local data set has at most one active binding. Persist normalized server origin, instance ID,
authorized scope identity, account identity when applicable, mode, and the supported synchronization
boundary. These values are personal metadata, not secrets or authorization evidence. Read-only public
discovery exposes instance ID and supported capabilities, not account/scope IDs, content, or setup
secrets. Obtain scope/account identity only through authorized binding/session information.

The browser explicitly requests first binding and compares the authenticated server identity with its
retained binding before releasing an outbox operation or applying a response. A browser-selected account
or scope is never authoritative. Serialize local binding changes with the data-set lease and fence
in-flight responses; capture binding identity/version with each attempt and reject response application
if it changed while the request was in flight. Cancellation does not prove that a submitted operation
was rejected; retain stable replay or indeterminate-outcome evidence.
Sign-out, revocation observation, and account/binding changes also advance a local exchange fence,
even if the retained binding metadata stays unchanged. A delayed response cannot restart exchange
under a paused or different account; recovery retains the original operation's evidence and intent.

Binding is a recoverable workflow across two stores, not a distributed database transaction. Commit
each side's state atomically within its own transaction, make repeated handshake steps idempotent,
and persist enough progress to resume after interruption without treating an unconfirmed binding as
permission to upload. Recheck server population and synchronization boundary during the handshake;
a racing writer invalidates an empty-server assumption and requires the safe recovery path.

| Local data set | Authorized server scope | Required path |
| --- | --- | --- |
| Empty | Empty | Confirm binding atomically in local storage, then normal synchronization. |
| Populated | Empty | Confirm binding and supported server boundary, then submit retained outbox operations with unchanged identity. Persist generation before first submission when G3 is implemented. |
| Empty | Populated | Complete anchored snapshot bootstrap and continuation before normal exchange. |
| Populated | Populated | Complete anchored reconciliation against retained base and pending intent. |

Until the last two complete paths exist, store a durable `reconciliation_required` pause, transfer no
content, and preserve local data. An empty change page or a partial traversal is not proof of an empty
scope. Cloning/export/import to a different destination needs its own accepted P0.6/S12 contract;
never reinterpret already submitted or indeterminate operations as new work for another scope.

## Transition outcomes

The following are design states, not new public wire contracts. S2 defines closed wire shapes. Binding
state and current permission are separate: being bound does not imply a valid session.

| Trigger | Authorization and server outcome | Durable browser outcome |
| --- | --- | --- |
| No server configured | No network request or account required. | `offline`; local reads/writes continue. |
| First binding interrupted | Repeat only the authorized, idempotent handshake; deny unconfirmed upload. | `binding_pending`; retain data/outbox. |
| Valid original binding and access | Resolve scope on every request. | `bound`; normal exchange under its fenced binding. |
| Session expiry or sign-out | Invalidate the session server-side; deny further use. | `auth_required`; stop new sync attempts, clear credential/transient account UI state, keep local garden access and edits. |
| Sign-out while offline | No claim of confirmed remote logout; server expiry remains effective. | Persist pending logout, pause sync, and complete remote invalidation when reachable before any sync resumes. |
| Reauthentication to original account | Rotate session; revalidate owner/scope/device and installation state. | Resume original binding only after all identities match. |
| Another account signs in | Its authorized context cannot access the previous scope. | `binding_mismatch` for the original data set; select/create a separate data set explicitly. |
| Device/account revoked or disabled | Deny subsequent data access and invalidate applicable sessions. | `revoked` when observed; retain offline data and pending intent. |
| Origin, instance, scope, or unexpected mode changes | Deny mismatched exchange; no automatic ownership transfer. | `binding_mismatch`; require explicit recovery/disconnection. |
| Generation/history no longer usable | Apply ADR-0023/ADR-0026; never speculate about acceptance. | `reconciliation_required`; retain pending/indeterminate work. |
| Account/scope destroyed | Deny access; synchronization cannot create it again. | `scope_deleted` when authorized confirmation is available; otherwise generic access denial. Retain local copy until explicit deletion. |
| Explicit disconnection | No server ownership change; finish/cancel authorized exchange safely. | `disconnected`; retain old binding provenance and pending intent for explicit recovery. |
| Explicit local deletion | No implicit server-account deletion. | Fence tabs/requests and delete the selected data set's records and work atomically; disclose export/device-storage limits. |

Server denial never depends on the browser receiving a revocation notification. Serialize access
validation/acceptance with revocation: a transaction accepted before revocation keeps its stable
outcome, but no new work may be accepted after revocation commits. Reads validate current permission
when acquiring protected data. Data already released before revocation cannot be recalled from the
network or browser. S9/S11 tests must demonstrate this boundary, including responses delayed in flight.

Offline sign-out cannot reliably delete an HttpOnly cookie without server contact. Do not copy it into
script storage or claim immediate remote invalidation. Suppress authenticated background traffic while
logout is pending, retry only logout, and complete it before allowing renewed synchronization.
Complete pending logout before initiating a new sign-in on that server, coordinate it across tabs,
and never replay a stale logout against a newly created session. S8 defines the protected logout
contract and S12 verifies interruption and session-change races without script-readable credentials.

## Mode changes and server restoration

Allow `single-user-no-auth` to `single-user-auth` only through controlled operator setup: prepare and
verify a backup; authenticate the intended new owner through the accepted S8 bootstrap mechanism;
quiesce synchronization; explicitly confirm the claim; atomically commit ownership and mode; then
resume with authentication required. Preserve scope, record, operation, revision, receipt, and journal
identity. An interrupted claim leaves either the previous committed mode/ownership or the complete
new state; maintenance/access denial covers uncertain state. Clients confirm the upgraded binding
after authenticating to its claimed owner. Unauthenticated data access must not survive the commit.

Allow `single-user-auth` to `multi-user-auth` only with authorized operator action and consistent
ownership. Preserve the original account/scope, rotate or invalidate sessions as required, and provision
new accounts/scopes independently. No other mode changes are supported in the first release. In
particular, both authenticated-to-no-auth changes and multi-user-to-single-user changes are rejected,
including direct edits, restarts, concurrent operator requests, and restoration of older configuration.

Normal restart and forward migration retain instance identity. Replacement/reset and restoration
of earlier synchronization or security state establish a new instance identity before data access;
stale bindings must detect it. A restore first reapplies deletion instructions and invalidates stale
sessions/enrollments as needed. Preserve deletion evidence outside affected backup rollback. Operator
restore procedures must prevent bypassing the recorded authentication downgrade restriction. A restored
no-auth snapshot of a server that has since enabled authentication stays inaccessible until controlled
recovery restores the accepted authenticated mode.

## Complete account deletion

The [privacy lifecycle](../privacy/access-data-lifecycle.md) defines category coverage and backup limits.
The first release needs complete account/scope deletion even though selective domain deletion remains
deferred by DF-06.

1. Require authorized recent authentication and explicit confirmation. Offer export before confirmation
   without making export mandatory. Controlled operator recovery cannot use public or weaker paths.
2. Atomically mark account and scope inaccessible/deleting, revoke all sessions and enrollments, and
   persist a content-free purge instruction. Coordinate this boundary with accepted writes and backups.
3. Run an idempotent, resumable purge across scoped records, receipts, journals, tombstones, reservations,
   generations, snapshots, credentials, account attributes, and devices. A failed purge keeps access
   denied and retries visibly for the operator; it never restores the account.
4. Record minimal completion/backup-suppression evidence in a restricted store outside rollback of the
   affected backups. Purge that evidence after all affected backups expire or are erased, unless a
   documented lawful hold requires retention. Generic denial must not reveal the former account.

Deletion does not change the installation to no-auth or claim a new owner automatically. For a deleted
sole account, keep the server in its authenticated mode and require controlled fresh setup if reused.
Fresh accounts get fresh scope identities. A stale device may keep using its local copy but cannot
upload it or recreate the deleted scope. Any deliberate import into a new account follows the future
explicit import/binding contract.

## Acceptance scenarios and implementation owners

These are required future executable scenarios, not claims of tests added by S1.

| Scenario | Expected evidence | Owner |
| --- | --- | --- |
| AC-AS-01 | Offline create/reload succeeds without server/account; all local work belongs to one durable data-set identity. | S5 |
| AC-AS-02 | Missing/unknown mode, absent context, wrong owner, and production test-resolver activation deny access without identifying another account. | S2, S4, S9, S10 |
| AC-AS-03 | Two devices in A exchange create/replace/delete; B sees none; identical record/operation IDs, locks, receipts, retries, and leases remain independent. | S3, S5, S7 |
| AC-AS-04 | Substituted cursor, generation, snapshot ID, and resource ID cannot reveal/modify another scope. | S2, S7, S13, S14 |
| AC-AS-05 | Each first-connection row, racing server population, interrupted handshake, and reload preserve local intent; unsupported paths pause. | S6, S14 |
| AC-AS-06 | Online/offline logout and expiry stop synchronization; offline edits persist; pending logout precedes later exchange; same-account reauthentication resumes safely. | S9, S12 |
| AC-AS-07 | Account switch/server replacement during a request fences its response and preserves ambiguous submitted work without cross-binding retries. | S6, S12, S14 |
| AC-AS-08 | Enrollment requires authorized single-use proof and rejects replay/forged device IDs; concurrent revocation and acceptance have a defined committed ordering; revoked sessions cannot authorize later work; accepted data remains owned by the account. | S9, S11 |
| AC-AS-09 | Interrupted/concurrent no-auth claim preserves one committed mode/owner and identity; post-commit unauthenticated access denies. | S9, S12 |
| AC-AS-10 | Direct config downgrade, multi-user-to-single-user change, and restored old no-auth config cannot bypass access denial. | S4, S12, S15 |
| AC-AS-11 | Interrupted purge/restart stays denied, eventually deletes every category, and stale clients cannot resurrect the account/scope. | S12, S15 |
| AC-AS-12 | Restore suppresses completed deletions, changes instance identity after rollback, invalidates old sessions, and pauses stale clients. | P0.6, S12, S15 |
| AC-AS-13 | Explicit local deletion fences tabs/responses, affects only the selected data set, and makes no secure-erasure claim. | S5, S12 |
| AC-AS-14 | Captured ordinary/security/proxy diagnostics contain only approved fields; retention/purge and security-log access controls work. | S4, S9-S12, S15 |

## Deferred decisions and release blockers

S2 selects wire contracts and binding versioning before adapters. S8 selects the authentication library,
factor strength, password/recovery policy, CSRF mechanism, exact session limits, and concurrent-session
limits. No authenticated path ships while these decisions are unresolved. Device enrollment follows
S11; additional-account/server imports and anchored recovery follow S12/S14 and P0.6.

S15 closes the ASVS implementation/evidence register, assesses offline-storage deviations, and verifies
production TLS, service credentials, secrets, logs, backups, erasure, rights handling, breach readiness,
and operator legal decisions. Synthetic G2f scope validation is permitted before those gates; real
personal-data trials and shared production use are not. No new library or public API is selected here.
