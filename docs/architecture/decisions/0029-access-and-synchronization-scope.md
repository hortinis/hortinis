# ADR-0029: Access and synchronization-scope ownership

- Status: Accepted
- Accepted on: 2026-10-02.
- Implementation owner: S1 through S15 of the
  [access and synchronization-scope plan](../../development/access-and-synchronization-scope-implementation-plan.md).

## Context

DF-11 through DF-13 require indefinite standalone use, optional application accounts for an individual
server, and isolated accounts on a shared server. G2e validates operation replay, deletion, recovery,
and browser coordination in one technical scope. It does not implement account isolation or safe
binding of existing local data to a server.

ADR-0013 establishes built-in accounts and protected cookie sessions. It does not require an account
for standalone use or settle the individual server's optional-account mode. Authentication, ownership,
local persistence, and synchronization must have separate identities and lifecycles.

## Decision

### Identity and authorization

- Generate a durable `localDataSetId` offline. It partitions all browser records and synchronization
  work and never authorizes server access.
- Give each server installation an opaque, non-secret `serverInstanceId`. Preserve it across normal
  restarts and forward migrations. Replacement, reset, or a restore that rolls synchronization state
  back changes this identity before serving clients.
- Provision an opaque `synchronizationScopeId` on the server. For the first release, one account owns
  one scope through an explicit relationship; an account ID and a scope ID are different identities.
- Devices identify enrolled clients, not business owners. Sessions resolve access and are revocable
  credentials, not synchronization partition keys. Generate session secrets independently of UUID
  record identifiers and carry them only in protected cookies.
- Resolve one immutable `SynchronizationAccessContext` from trusted configuration or a validated
  server-side session for every data request. Pass it explicitly through services and persistence.
  Scope every query, mutation, constraint, receipt, cursor, lock, generation, and snapshot. No identifier
  in an untrusted request grants access.

### Explicit access modes

Support only `single-user-no-auth`, `single-user-auth`, and `multi-user-auth`. There is no runtime
fallback or implicit production mode. Missing, unknown, inconsistent, or unsupported configuration
fails closed. Standalone browser use remains available without server configuration.

`single-user-no-auth` resolves the sole provisioned scope for every caller. Anyone able to reach its
data endpoints can read and modify that scope. Real personal-data use requires a documented and tested
compensating access boundary; an unrestricted LAN or public listener is insufficient. This mode never
establishes individual user isolation.

Authenticated modes map a validated account to its owned scope. Operator administration is separate
from ordinary gardener access and does not automatically grant garden-data access. A test resolver
must be absent from production artifacts; a production header or parameter must never impersonate a
scope.

### Binding and local access

Bind one local data set to one active server origin, instance identity, and authorized scope/account
identity. Compare identity received through an authorized server context; locally stored identity is
only a mismatch detector. Require an explicit first-binding action and preserve pending intent.

Sign-out, session expiry, and device revocation pause server synchronization. Local data remains
readable and editable offline. A different account uses a separate local data set and cannot receive
the previous account's outbox. Local retention does not provide secrecy against another person using
the same browser profile, malicious same-origin JavaScript, or an attacker controlling the device.

Never silently rebind on account change, server change, replacement, or restoration. Preserve local
work and require explicit recovery. Populated server state uses anchored bootstrap/reconciliation;
until the complete path exists, deny transfer and retain `reconciliation_required` work.

### Configuration transitions

- Switching from no application login to single-user login requires controlled operator setup and an
  explicit claim of the existing sole scope. Preserve accepted data, scope identity, and operation
  identities. Quiesce data requests during the transition and permit them afterward only with valid
  authentication. Do not assign ownership to whoever happens to log in first.
- Switching from single-user authentication to multi-user authentication preserves the original owner
  and scope; additional accounts receive separate scopes.
- Reject either authenticated mode changing to no authentication in the first release, including a
  direct configuration edit. Reject multi-user mode changing to single-user mode. Other unimplemented
  transitions fail closed until an accepted transition contract exists.

### Account and scope deletion

Define complete deletion now and implement it before real-user deployment. Confirm authorization,
atomically block account/scope access and revoke sessions, then perform an idempotent, resumable purge.
Delete scoped data, receipts, journals, tombstones, identifier reservations, temporary state, account
credentials, and device metadata. Never reuse account or scope identities or provision a missing scope
from a stale synchronization request.

Local deletion is a separate explicit action. Account deletion cannot remotely erase disconnected
browsers or user exports. Backups have a bounded lifecycle and a restore procedure that reapplies
completed deletion instructions before service resumes. Retain only the minimal protected deletion
evidence required until affected backups have expired or been purged, subject to a documented legal
hold. See the [privacy lifecycle](../../privacy/access-data-lifecycle.md).

### Security and privacy acceptance

Use ASVS 5.0.0 Level 2 as the verification target for authenticated and networked personal-data paths.
The [applicability register](../../security/asvs-5.0.0-applicability.md) records every requirement,
conditional feature, exclusion, and selected stronger control. This is a design target, not a claim
that G2e or a future deployment has passed verification.

Retaining offline data after sign-out intentionally departs from `v5.0.0-14.3.1` and
`v5.0.0-14.3.3`. Record this product constraint, mitigations, and residual shared-device/XSS risks;
do not represent those requirements as satisfied or make an unqualified Level 2 claim. S8 must resolve
the Level 2 authentication-strength requirement `v5.0.0-6.3.3`; password-only authentication is not
silently accepted as sufficient.

Separate minimized operational and protected security logs. No credentials, session identifiers,
request/response bodies, garden content, precise locations, or raw account/scope/device identifiers
belong in logs. A purpose-limited pseudonymous security subject reference may be introduced only with
the documented inventory, access controls, retention, and S15 verification.

## Alternatives and rationale

- **Use an account as local identity.** This prevents indefinite offline account-free use.
- **Use a device as owner.** Revocation would incorrectly change ownership and fragment one gardener's
  data across devices.
- **Trust a client-selected scope.** An identifier is not evidence of authorization and enables IDOR.
- **Upload to the account currently signed in.** This can disclose another account's retained local work.
- **Erase local data on sign-out.** The accepted product choice retains offline work; explicit local
  deletion remains available as a separate workflow.
- **Automatically downgrade authentication or claim existing data.** Configuration mistakes or a racing
  first login could expose data or select the wrong owner.

## Consequences and validation

S1 is a documentation/design increment. S2 through S7 implement scope isolation with synthetic data;
S8 through S12 implement authentication, devices, and transitions; S13 and S14 implement scope-aware
generations and reconciliation. Deletion, backup restoration, and organizational/privacy evidence are
release prerequisites tracked by S12 and S15 with P0.6 and deployment work.

Use the [access specification](../access-and-synchronization-scope.md),
[threat model](../../security/access-threat-model.md), and privacy lifecycle as the implementation
handoff. Validate deny-by-default transitions, cross-scope substitution, concurrent revocation,
interrupted claims/purges, and restoration without resurrection. Library selection, numeric session
limits, recovery mechanisms, production log/backup periods, and lawful-basis decisions remain explicit
blocking work for their implementing tasks.

## Relationship to existing decisions

S2 records the concrete wire and application-boundary refinement in
[access and binding contracts](../../api/access-and-binding-contracts.md): canonical distinct
identifiers, internal-only context factories, public discovery versus authorized bootstrap,
empty-destination confirmation, exchange expectations, local version-one metadata, and non-cacheable
generic access errors. No authentication library or session mechanism is selected. S2 parsing evidence
does not activate the corresponding S4/S6 or authenticated adapters.

This decision extends ADR-0013 for DF-12's optional application authentication and refines ADR-0015's
identifier logging rules. ADR-0010 and ADR-0023 through ADR-0028 remain authoritative for operation
identity, retention, tombstones, generations, reconciliation, retry, and browser coordination. Scope
destruction ends the identifier-reservation lifetime in ADR-0024; it does not permit identity reuse.
