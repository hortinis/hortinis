# Access and synchronization-scope implementation plan

## Purpose and authority

This document is the implementation handoff for R13, the access and ownership portions of P0.5 and
M4.1, and foundation increment G2f. It refines those existing increments rather than creating a separate
product track.

The authoritative product decisions remain DF-11 through DF-13 in
[`functional-decisions.md`](../product/functional-decisions.md): the application can remain local forever,
an individual server can run with or without application authentication, a shared instance requires
accounts, and collaboration between gardeners remains deferred. ADR-0010, ADR-0013, and ADR-0015 remain
authoritative for synchronization, built-in identity, cookies, privacy, and observability.

This plan starts from the validated G2e implementation on `origin/feat/g2e-validation`. It does not
declare any task complete, select an authentication library, or by itself authorize processing real user
data.

## Required outcome

Hortinis must support four states without changing the synchronization invariants:

1. a browser can create and retain one local data set without a server or account indefinitely;
2. an explicitly configured single-user server can expose one internal synchronization scope without
   application authentication;
3. a single-user server can protect that same model with one authenticated account; and
4. a shared server can isolate one synchronization scope per account while every enrolled device for that
   account synchronizes the same data.

Authentication and a scope are different boundaries. A synchronization scope partitions data. An
authenticated server-side access context authorizes access to that partition. A scope identifier is never
a credential, and an untrusted client-supplied scope identifier is never sufficient authorization.

## Identity and binding model

Keep these identifiers distinct:

| Identifier               | Authority and lifetime                                          | Purpose                                                                                           | Security rule                                                                    |
| ------------------------ | --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `localDataSetId`         | Created by the browser and retained with local data             | Partitions standalone records, pending operations, bases, conflicts, and bindings                 | Never authorizes server access                                                   |
| `serverInstanceId`       | Created by one server installation and retained across restart  | Detects replacement of a server behind the same origin                                            | Opaque, non-secret, and never used as a credential                               |
| `synchronizationScopeId` | Created and controlled by the server                            | Partitions records, receipts, changes, tombstones, cursors, generations, and reconciliation state | Obtained only from trusted server configuration or an authorized session context |
| `accountId`              | Created by an authenticated server                              | Owns the permitted synchronization scope or scopes                                                | Never accepted from the browser as proof of identity                             |
| `deviceId`               | Created during enrollment when device management is implemented | Binds sessions and supports device visibility and revocation                                      | Does not own business or synchronization data                                    |
| `sessionId`              | Created and rotated by the server                               | Resolves an authenticated account, device, and authorized scope context                           | Random opaque credential carried only in a protected cookie                      |

For the first release, one account owns one synchronization scope. Keep the database relationship
explicit so a later accepted decision can allow more than one scope without equating `accountId` and
`synchronizationScopeId`.

The browser persists a binding from one `localDataSetId` to one active server origin and
`serverInstanceId`. It must not silently upload the data set to another server, another server instance at
the same origin, or another authenticated account.

## Server access modes

Use one explicit, closed configuration value:

| Mode                  | Server-side scope resolution                                      | Permitted deployment                                                         |
| --------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `single-user-no-auth` | Every data request receives the one configured internal scope     | Explicitly selected trusted environment; never represented as user isolation |
| `single-user-auth`    | The authenticated sole account resolves to the one internal scope | Individual installation requiring application login                          |
| `multi-user-auth`     | The authenticated account resolves to its owned scope             | Shared instance with isolated accounts                                       |

There is no implicit fallback between modes. An absent, unknown, or incomplete production configuration
fails closed. `single-user-no-auth` requires explicit operator selection and documentation that every
party able to reach the service can read and modify its data. It is not a secure way to expose an instance
to mutually untrusted users. Before it processes personal data, the operator must provide and document a
compensating access boundary such as a single-user local device, authenticated private network, or
operator-managed authenticated reverse proxy. An unrestricted LAN or public listener is not an accepted
compensating control.

All modes produce the same immutable server-side `SynchronizationAccessContext`. Controllers delegate to
an access-context resolver before invoking synchronization services. Services and persistence components
receive the trusted context explicitly and never derive it from request bodies, query parameters, record
identifiers, or operation identifiers.

## Security and data-protection completion rule

The following rule applies to every task in this plan.

- Use the stable [OWASP Application Security Verification Standard 5.0.0](https://github.com/OWASP/ASVS/releases/tag/v5.0.0)
  as the requirements catalog. S1 records the accepted verification level and any requirement that is not
  applicable with a reason; authenticated and networked personal-data paths target at least Level 2 unless
  the accepted security decision requires a stronger control.
- Apply deny-by-default authorization, least privilege, permission validation on every request, secure
  session management, CSRF protection, prepared SQL, closed input contracts, safe error handling, TLS,
  dependency review, and security-event logging from the applicable OWASP requirements and cheat sheets.
- Apply GDPR purpose limitation, data minimization, storage limitation, integrity and confidentiality,
  accountability, and data protection by design and by default. Treat stable account, scope, device,
  session, connection, and cookie identifiers as personal data whenever they can relate to a person.
- Apply the CNIL developer and personal-data security guidance for authentication, authorization profiles,
  password handling, logging, retention, deletion, and incident readiness.
- Do not put credentials, session identifiers, CSRF tokens, account identifiers, device identifiers, scope
  identifiers, garden content, precise locations, request or response bodies, or concrete user routes in
  ordinary operational logs. A security log may contain only the documented, minimized fields required
  for a stated detection or investigation purpose, with access control and automatic retention.
- Add misuse, cross-scope, failure, and rollback tests with the functional tests. A task that changes a
  trust boundary is incomplete when only its success path or type checks pass.
- Update the data inventory, threat model, retention matrix, processing-purpose record, and operator/user
  documentation whenever the task adds or changes personal-data processing.
- Do not claim that code alone makes a deployment GDPR-compliant. Record operator configuration and
  organizational obligations, the lawful-basis decision, information and rights handling, processor or
  provider relationships, breach procedures, and the result of the DPIA screening before real-user use.

Normative legal requirements come from the official
[GDPR text](https://eur-lex.europa.eu/eli/reg/2016/679/oj). Implementation guidance is taken from the
[CNIL GDPR developer guide](https://www.cnil.fr/en/gdpr-developers-guide),
[CNIL user-profile guidance](https://www.cnil.fr/en/sheet-ndeg8-manage-user-profiles), and
[CNIL security guide](https://www.cnil.fr/sites/cnil/files/2024-03/cnil_guide_securite_personnelle_ven_0.pdf).
Relevant OWASP implementation references include the
[authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html),
[IDOR prevention](https://cheatsheetseries.owasp.org/cheatsheets/Insecure_Direct_Object_Reference_Prevention_Cheat_Sheet.html),
[session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html),
[CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html),
[password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html),
[REST security](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html),
[TLS](https://cheatsheetseries.owasp.org/cheatsheets/Transport_Layer_Security_Cheat_Sheet.html), and
[logging](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html) cheat sheets. OWASP
guidance supports verification but does not replace the controller's legal assessment.

## First-connection matrix

The binding workflow must classify both sides before transferring data:

| Local data set | Authorized server scope | Required behavior                                                                                          |
| -------------- | ----------------------- | ---------------------------------------------------------------------------------------------------------- |
| Empty          | Empty                   | Bind atomically and begin normal synchronization                                                           |
| Populated      | Empty                   | Bind, establish the server generation, then submit the retained outbox without changing operation identity |
| Empty          | Populated               | Bind and bootstrap the complete server state through the accepted snapshot boundary                        |
| Populated      | Populated               | Require anchored full reconciliation; never choose, overwrite, or merge implicitly                         |

Until G4 implements anchored reconciliation, the last two cases may be enabled only when their complete
safe path exists. Otherwise the client records a durable, user-visible `reconciliation_required` state and
preserves local data and pending intent.

## Implementation tasks

### S1 — Accept the access, scope, security, and privacy design

- Status: `planned`.
- Depends on: accepted product decisions DF-11 through DF-13 and accepted ADR-0010, ADR-0013, and
  ADR-0015. R13 and P0.5 own this task rather than acting as prerequisites to it.
- Scope: record an ADR and implementation-neutral specification for the identifier model, three access
  modes, trusted `SynchronizationAccessContext`, scope ownership, local-to-server binding, sign-out,
  session expiry, revocation, server replacement, account changes, and configuration transitions.
- Security work: create a threat model covering an untrusted browser, cross-account access, IDOR,
  credential theft, CSRF, XSS-assisted requests, session fixation, replay, brute force, malicious server
  replacement, database compromise, logs, backups, and an operator misconfiguration. Map accepted controls
  to stable OWASP ASVS 5.0.0 identifiers and record the verification level.
- Data-protection work: create the initial data inventory and flow diagram; state purposes and recipients;
  classify identifiers and garden/location data; propose retention and deletion by category; record the
  operator/controller boundary, required privacy information, rights handling, breach procedure, and DPIA
  screening criteria.
- Excludes: selecting or implementing an authentication library and claiming legal compliance.
- Acceptance: every transition has a deny-by-default outcome; no client-controlled identifier grants
  access; each personal-data category has a purpose and proposed lifecycle; unresolved legal or security
  choices are explicit blockers rather than permissive defaults.

### S2 — Define scope-neutral application boundaries and wire contracts

- Status: `planned`.
- Depends on: S1.
- Scope: define `LocalDataSetId`, `ServerInstanceId`, `SynchronizationScopeId`,
  `SynchronizationAccessContext`, server-capability discovery, binding states, and closed bootstrap/error
  contracts before implementing adapters. Keep normal one-scope synchronization routes free of a
  client-selected scope identifier. Define how a future authorized multi-scope selector would be checked
  without making identifiers secret.
- Security and privacy acceptance: TypeSpec rejects unknown properties and malformed identifiers; errors
  do not reveal whether another account or scope exists; credentials and tokens never appear in URLs;
  cache rules prevent sensitive authenticated responses from being stored unintentionally; contract
  examples contain synthetic data only.
- Validation: generation drift, OpenAPI lint, JSON Schema validation, focused negative cases, and shared
  TypeScript/Java parsing fixtures pass.

### S3 — Partition the PostgreSQL synchronization model

- Status: `planned`.
- Depends on: S1 and S2.
- Scope: add the schema version that creates server-instance and synchronization-scope state and makes
  scope part of every technical record, accepted receipt, change, tombstone, retired identifier, lock,
  uniqueness rule, foreign key, and index. Scope operation and record identity as
  `(synchronization_scope_id, identifier)`. Serialize publication within one scope without forcing
  unrelated scopes through the same lock.
- Migration rule: the repository is pre-production and no user-data ownership migration is required, but
  Flyway history remains versioned and tested. Do not rewrite a released or already-applied migration
  silently; document the supported development reset or deterministic technical-fixture transition.
- Security and privacy acceptance: every read and mutation is parameterized and requires a trusted scope;
  the same record and operation identifiers can exist independently in two scopes; database errors expose
  no content or identifiers through HTTP or logs; least-privilege database credentials and backup handling
  are documented for deployment work.
- Validation: clean migration, supported populated technical-fixture transition or documented reset,
  rollback, restart, constraints, indexes, concurrent publication, cross-scope collision, and transaction
  isolation tests pass with PostgreSQL.

### S4 — Enforce the trusted server access context

- Status: `planned`.
- Depends on: S2 and S3.
- Scope: introduce the narrow access-context resolver, pass its immutable result through controller,
  service, and persistence calls, and remove the hard-coded global scope. Implement explicit mode
  configuration with fail-closed startup validation. Provide a test-only resolver for automated scope
  isolation scenarios; do not ship a production header, query parameter, or request field that impersonates
  another scope.
- Security and privacy acceptance: authorization is centralized before workflow execution and verified
  again at the scoped persistence boundary; absent context, mismatched context, unknown mode, and disabled
  authentication deny access; `single-user-no-auth` requires explicit configuration and emits only a
  content-free configuration warning; production configuration cannot activate the test resolver.
- Validation: representative controller/service architecture rules, negative configuration tests, access
  denial tests, and log-capture tests pass.

### S5 — Partition browser data by a durable local data-set identity

- Status: `planned`.
- Depends on: S1 and S2.
- Scope: generate one `localDataSetId` without network access and associate it with records, tombstones,
  pending operations, accepted results, bases, conflicts, cursors, retry state, and synchronization leases.
  Add a versioned Dexie schema and a local server-binding record. Preserve atomic local projection and
  outbox writes.
- Transition rule: development data may be reset; no production data migration or ownership inference is
  authorized. The migration behavior and failure state must nevertheless be deterministic and tested.
- Security and privacy acceptance: never store passwords, session IDs, access tokens, refresh tokens, or
  other credentials in IndexedDB, local storage, or session storage; do not treat `localDataSetId` as a
  secret; prevent one local data set from reading, submitting, retrying, or advancing another data set's
  state; document the browser/XSS and shared-device threat boundaries.
- Validation: offline creation, reload, schema failure, rollback, local cross-data-set isolation,
  multi-tab fencing, and independent retry tests pass.

### S6 — Implement safe server discovery and first binding

- Status: `planned`.
- Depends on: S3 through S5.
- Scope: implement read-only server discovery and the atomic binding workflow. Persist the server origin,
  `serverInstanceId`, local data-set identity, access-mode capability, and synchronization boundary needed
  by the implemented generation level. Detect a replaced server at the same origin and require an explicit
  user decision rather than silently rebinding.
- Safe initial scope creation: a single-user server provisions its sole scope during controlled setup; a
  multi-user server provisions a scope through account creation. Normal synchronization requests never
  create an arbitrary scope from client input.
- Security and privacy acceptance: discovery returns no account, scope, user-content, or deployment-secret
  data; authenticated variants use protected sessions and CSRF controls when they mutate setup state;
  binding responses are non-cacheable where appropriate; server identity mismatch and replay fail closed.
- Validation: the empty/empty and populated-local/empty-server rows of the first-connection matrix pass
  through browser, Spring, and PostgreSQL; interruption cannot leave a half-binding or change operation
  identifiers.

### S7 — Validate scope isolation across the real topology

- Status: `planned`.
- Depends on: S3 through S6.
- Scope: extend the real topology to use two devices in scope A and one device in scope B. Cover create,
  dependent replace, deletion, tombstone pull, retry after ambiguous acknowledgement, cursor persistence,
  reload, and service restart.
- Security and privacy acceptance: A1 and A2 share A data; B receives none of it; identical record and
  operation IDs are independent across scopes; receipts, cursors, generations, tombstones, retired IDs,
  retries, and leases cannot cross scopes; adversarial context, cursor, and identifier substitutions fail
  without confirming another scope's existence; captured diagnostics contain no prohibited data.
- Acceptance: G2 behavior remains valid per scope, the test-only access mechanism is unavailable in the
  production build/profile, and the report states that authenticated user isolation is not yet implemented.

### S8 — Select the built-in authentication and session design

- Status: `planned`.
- Depends on: S1 and S4.
- Scope: record the authentication-library decision required by ADR-0013 and specify account bootstrap,
  login, password change and recovery, secure session creation and rotation, inactivity and absolute
  expiry, logout, concurrent sessions, device enrollment, revocation, administrative recovery, CSRF, rate
  limiting, security headers, TLS assumptions, and optional OIDC integration boundary.
- Security acceptance: use an established framework rather than custom cryptography; password storage uses
  the accepted adaptive password-hashing configuration and unique salts; session cookies are opaque and
  use the narrowest practical host/path plus `Secure`, `HttpOnly`, and accepted `SameSite` behavior;
  authenticated state-changing requests have explicit CSRF defense; login and recovery resist account
  enumeration, brute force, fixation, and replay.
- Data-protection acceptance: collect only account attributes required for the selected login and recovery
  flow; define purposes, lawful-basis inputs, retention, erasure, recovery records, security-log fields,
  and user information before implementation. A self-hosted installation must not require an external
  identity or email provider.

### S9 — Implement authenticated single-user access

- Status: `planned`.
- Depends on: S6 and S8.
- Scope: implement controlled first-account setup, authentication, server-side sessions, logout, expiry,
  and mapping of the sole account to the existing sole scope. Replace the no-auth resolver with the
  authenticated resolver when this mode is selected while keeping synchronization services unchanged.
- Security and privacy acceptance: all personal-data endpoints require a valid authorized context; session
  rotation and invalidation are atomic; password and session material never enter application logs or
  browser script-readable storage; CSRF, caching, TLS/forwarded-header, rate-limit, and generic-error tests
  pass; security logs are minimized, protected, and retained by documented purpose.
- Offline acceptance: expiry or logout pauses synchronization without blocking local reads and writes or
  discarding the outbox; reauthentication to the original account resumes; a different account cannot
  receive the pending work.

### S10 — Implement multi-user accounts and scope authorization

- Status: `planned`.
- Depends on: S7 through S9.
- Scope: implement account provisioning, one owned scope per account, server-side account-to-scope
  authorization, and authenticated access-context resolution. Keep account creation and scope creation in
  one controlled transaction; normal synchronization requests never create or select arbitrary scopes.
- Security and privacy acceptance: every endpoint validates account-to-scope ownership; deny-by-default
  tests cover horizontal and vertical privilege escalation, IDOR, account enumeration, expired sessions,
  replay, concurrent provisioning, and administrative paths; account attributes are minimized with
  explicit purposes and retention; account and scope identifiers remain out of ordinary logs and
  analytics.
- Acceptance: account A and account B receive distinct scopes; each can use the same record and operation
  identifiers independently; no API, cursor, error, or timing behavior grants or reveals cross-account
  access; and disabling an account invalidates its sessions without altering another account.

### S11 — Implement device enrollment and revocation

- Status: `planned`.
- Depends on: S8 through S10.
- Scope: implement device enrollment, device-bound sessions, additional-device bootstrap, device listing,
  optional user-supplied labels, last-activity behavior where justified, and revocation. Keep `deviceId`
  off business ownership and synchronization partition keys. Previously accepted data remains owned by
  the account scope after the originating device is revoked.
- Security and privacy acceptance: enrollment and recovery use one-time, expiring, replay-resistant
  authorization; revocation invalidates applicable sessions atomically; stale and concurrently revoked
  sessions fail closed; device metadata is minimized and has stated purposes, visibility, retention, and
  erasure behavior; device identifiers remain out of ordinary logs and analytics.
- Acceptance: devices A1 and A2 synchronize the same account A scope, account B remains isolated, and
  revoking A2 prevents new access without deleting A's accepted garden history or A1's access.

### S12 — Implement mode and binding transitions

- Status: `planned`.
- Depends on: S6 and S9 through S11.
- Scope: implement explicit transitions from `single-user-no-auth` to `single-user-auth`, from
  `single-user-auth` to `multi-user-auth`, sign-out, account change, server change, server replacement, and
  local-data-set cloning or disconnection. Reject a transition to a single-user mode while multiple owned
  scopes exist. Never infer ownership from whichever user logs in first unless the accepted setup contract
  explicitly performs and confirms that claim.
- Security and privacy acceptance: sensitive transitions require recent authentication or controlled
  operator setup; use atomic, auditable state changes and generic external errors; preserve pending local
  intent without exposing it to another account; define local data retention or deletion on shared devices
  and implement the user's selected outcome without presenting deletion as secure erasure when browser or
  backup limitations prevent that guarantee.
- Validation: interrupted transition, rollback, stale session, concurrent administrator, wrong-account,
  replaced-server, backup, and reload scenarios pass.

### S13 — Make G3 generations scope-aware

- Status: `planned`.
- Depends on: S7 and the accepted G3 contract refinements.
- Scope: implement G3 with one active generation per synchronization scope. Bind receipts, cursors,
  changes, retention, rollover, compaction, and indeterminate outcomes to the trusted scope context. A
  generation or cursor from one scope is unusable in another even when its encoded position is valid.
- Security and privacy acceptance: generation values remain opaque and non-secret; rollover and compaction
  administration is unavailable through public unauthenticated endpoints; operational events contain no
  account, scope, device, record, operation, or garden identifiers; denial responses do not expose another
  scope's state.
- Acceptance: every existing G3 scenario runs in one scope, focused adversarial tests cover cross-scope
  generation and cursor substitution, and concurrent rollover in one scope does not block or alter another.

### S14 — Reconcile a standalone data set with populated server state

- Status: `planned`.
- Depends on: S6, S13, and foundation G4. Authenticated uses additionally depend on S9 or S10 as
  applicable to their configured mode.
- Scope: use anchored snapshots and the retained local base/outbox to implement the empty-local/populated-
  server and populated-local/populated-server rows of the first-connection matrix. Keep binding, snapshot,
  reconciliation outcomes, and continuation cursors within the authorized scope.
- Security and privacy acceptance: snapshot pages are authorized on every request and non-cacheable;
  session expiry preserves resumable local work without extending authorization; reconciliation never
  reveals records from another scope; temporary snapshot and reconciliation data have documented expiry
  and automatic purge.
- Acceptance: no path silently overwrites server history, discards pending local intent, duplicates an
  accepted effect, or transfers local work to a different authenticated account.

### S15 — Publish security, privacy, and operational readiness evidence

- Status: `planned`.
- Depends on: S1 through S14 and the applicable foundation release gate.
- Scope: complete the ASVS mapping with reproducible evidence, automated dependency and secret checks,
  access-control and session tests, configuration review, penetration-test scope, retention/purge checks,
  backup/restore review, operator hardening guidance, privacy information, rights procedures, incident and
  breach runbooks, processing-record template, and DPIA screening result.
- Acceptance: no known blocking cross-account, session, configuration, logging, retention, recovery, or
  personal-data risk remains undocumented; `single-user-no-auth` limitations are prominent; authenticated
  modes fail closed; personal-data use in `single-user-no-auth` has a tested and documented compensating
  access boundary; evidence distinguishes automated checks, manual review, operator responsibilities,
  accepted residual risk, and matters requiring qualified legal or security review.
- Release rule: neither a shared multi-user deployment nor a field trial retaining real personal data is
  authorized until the applicable authenticated mode, recovery prerequisites, and this evidence pass.

## Dependency sequence

- Scope foundation: S1, S2, S3, S4, and S5 converge in S6, followed by S7.
- Authentication: S1 and S4 permit S8; S6 and S8 permit S9; S7 through S9 permit S10; S8 through S10
  permit S11; S6 and S9 through S11 permit S12.
- Recovery: S7 permits scope-aware G3 work in S13; foundation G4 and S13 permit S14.
- Release evidence: S1 through S14 and the applicable foundation gate permit S15.

S3 through S7 form foundation G2f and may be implemented before authentication with synthetic technical
data and trusted test contexts. They do not make an unauthenticated service safe for multiple users. S8
through S12 implement the production access modes owned by M4.1. S13 is G3's scope-aware generation work,
and S14 consumes G4 reconciliation rather than duplicating it. S15 is the security, privacy, and
operational release-evidence gate.

## Validation and handoff rule

Each implementation change records:

- its task ID and accepted decision references;
- contracts and migrations changed before adapters;
- security and privacy requirements exercised, including stable ASVS references;
- automated commands and manual reviews actually completed;
- test data classification and confirmation that fixtures are synthetic;
- personal-data fields, purposes, retention, logs, and documentation changed;
- unresolved risks, deferred controls, and why the next dependent task may or may not start; and
- English-language consistency under ADR-0018.

Use the repository commands in [`README.md`](README.md) and add focused contract, browser, Java,
PostgreSQL, security, and real-topology commands as their owning tasks introduce them. A passing general
build does not replace scope-isolation, authorization, privacy, recovery, or adversarial evidence.
