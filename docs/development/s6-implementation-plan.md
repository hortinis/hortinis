# S6 implementation plan

## Analysis and implementation boundary

S4 resolves trusted no-auth access and S5 encloses browser state within a durable local owner. HTTP
exchange still starts without discovery or a binding. S2 already defines public capabilities,
authorized bootstrap, empty confirmation, non-authorizing expectations, binding provenance, and local
exchange states. Implement those existing contracts before enabling binding-aware exchange; preserve
canonical operation/result shapes and IDs. No authenticated mode is enabled before S8-S10.

An empty destination must have no publication history, records, receipts, tombstones, or retired IDs.
An empty bootstrap alone is observational. Confirmation and the first exchange must share the scope's
publication lock with acceptance. A confirmed expectation admits the first exchange only while that
same destination remains empty; the successful first exchange establishes that expectation atomically.
Another writer invalidates competing initial admissions before any operation or cursor lookup.

Use persisted opaque UUID expectations as bounded non-secret protocol state, with a 15-minute server
lifetime and a bounded number of outstanding expectations per scope. Bind each to instance, scope,
resolved authority/mode/account, generationless boundary, and admission phase. Purge expired state when
issuing expectations and through a scheduled cleanup component. This uses existing UUID/JDBC
facilities, adds no cryptography/session mechanism, and never grants authority independently of the
current resolver. Server restart retains valid expectations within their original lifetime.

The browser stores initial confirmed admission separately from the credential-free binding model,
with its owner, exchange fence, and a shorter 10-minute local lifetime. A failed/ambiguous initial
exchange retains its original admission and operation IDs; expiry removes the expectation and pauses
for explicit recovery rather than refreshing into a populated destination. Purge on open/access and a
bounded active-page timer; closed browsers cannot execute cleanup. After the first known successful
exchange, normal refresh is allowed only for the exact retained destination. Initial progress remains
recoverable after reload, without a distributed transaction or inferred ownership.

## Implementation sequence

1. Extend the fresh PostgreSQL schema with scope-owned expectation state. Add dedicated persistence,
   service, and HTTP entry points for capabilities/bootstrap/confirmation, strict request parsing,
   fixed no-store access errors, duplicate/invalid/missing header denial, and bounded cleanup. Enforce
   expectations at HTTP exchange through transactional workflows, before data lookup. Keep trusted
   internal synchronization calls explicitly scoped for foundation tests/background use.
2. Extend the fresh Dexie schema with local exchange/progress state. Capture its positive decimal
   fence in every lease; validate it in result, conflict, rejection, retry, cursor, renewal, and release
   transactions. Binding/progress/state changes share the data-set lease and commit atomically. Reject
   upgrades without migration, under this session's fresh-database instruction.
3. Add a narrow binding HTTP adapter and binding service. Normalize/validate the current same-origin
   server origin, discover supported capabilities, obtain authorized bootstrap, explicitly confirm
   empty destinations, then atomically persist provenance/admission/readiness. Populated destinations
   remain durably `reconciliation_required` until S14. Missing discovery never permits fallback upload.
   A changed instance, origin, mode, account, scope, or boundary preserves the binding and pauses with
   an advanced fence; no implicit rebinding occurs. An unbound standalone data set remains local.
4. Gate real HTTP push/pull before preparing/reserving an attempt and attach `Sync-Expectation` outside
   operation bodies. Check captured destination/fence before response interpretation and again in the
   committing transaction. Complete initial progress atomically with a known successful result/pull;
   preserve ambiguous submission evidence. Synthetic test transports remain replaceable collaborators,
   without a production bypass flag or impersonation input.
5. Expose explicit connection/readiness in the shell through services, with fixed user-facing messages
   and no identifiers, credentials, or content in diagnostics. Update mock browser fixtures and real
   topology setup for retained bindings; add real first-binding evidence before existing G2 scenarios.
6. Cover discovery minimization, closed/no-store contracts, header/context substitution, scoped token
   replay/expiry/restart, racing confirmation/first publication, rollback, offline/unbound preservation,
   both empty-server matrix rows, populated-server pauses, interruption/reload, destination replacement,
   delayed responses, and unchanged operation IDs. Review and fix the implementation, update the ADR,
   access/security/privacy/development handoffs, run documented validation, and commit S6 before S7.

## Review refinements

Bootstrap accepts an optional retained `Sync-Expectation` for an authorized preflight reobservation.
It validates current authority and the original lifetime, returning the same token without allocating
rows. Every content request verifies this observation before sending its body or cursor; cached tokens
do not substitute for current identity checks. Established exchanges alone may renew a token for the
exact retained destination. Expiry timers compare the admission inside the pause transaction so a
late timer cannot pause an already completed binding. Stored provenance without atomic activation
evidence fails closed; a storage-only binding write cannot resume a populated destination. Origin
normalization rejects embedded whitespace/control characters rather than relying on URL cleanup.

## Acceptance limits

Capabilities advertise only the implemented generationless boundary and no anchored snapshots. They
expose no scope/account/content/setup state. Bootstrap/confirmation resolve current authority and never
provision a scope. Same-origin no-auth mutation protection remains S4; authenticated sessions/CSRF and
trusted TLS/edge deployment remain later gates. Expectation/progress is non-secret protocol metadata,
never a credential or a reason to log an identity/body. No server selection, cloning, populated-state
reconciliation, user-data migration, or production access/security/privacy readiness is claimed here.
