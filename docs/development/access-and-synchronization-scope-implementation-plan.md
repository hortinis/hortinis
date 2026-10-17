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

This plan starts from the validated G2e implementation on `origin/feat/g2e-validation`. S1's accepted
design is recorded in [ADR-0029](../architecture/decisions/0029-access-and-synchronization-scope.md),
the [access specification](../architecture/access-and-synchronization-scope.md),
[threat model](../security/access-threat-model.md),
[ASVS applicability register](../security/asvs-5.0.0-applicability.md), and
[privacy lifecycle](../privacy/access-data-lifecycle.md). S2 contract/parsing boundaries are validated;
S3 implements database partitioning; S4 implements explicit no-auth request resolution and mode
configuration; S5 implements durable browser ownership; S6 implements fenced discovery/first binding;
S7 validates synthetic isolation across the real topology. S8 selects the built-in authentication and
session design. S9 implements single-user authentication; multi-user authorization and personal-data
release remain closed until their respective implementation and applicable acceptance gates are complete.

## Authentication baseline revision — 2026-10-17

The [Spring email/password replacement plan](spring-email-authentication-plan.md) and
[ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md) define the approved
next authentication baseline. S8 and S9 evidence below is historical, not evidence for this rewrite.
Mandatory passkeys/MFA, saved recovery codes, operator-ticket browser recovery, no-email requirements
and preservation of their contracts are obsolete implementation targets. R2 replaces the backend
login/session boundary with normal authenticated startup closed during the incomplete replacement.
R1 finalized replacement policy/contracts/inventories; the generated API still describes the complete
future surface. R1-R3 are complete; R4-R6 remain planned. Scope isolation, stable identity and offline
preservation remain.

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

- Status: `validated` (design only, 2026-10-02).
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
- Artifacts: accepted ADR-0029 and access specification; AC-AS-01 through AC-AS-14 acceptance scenarios;
  T01 through T15 threat/control mapping; all 345 pinned ASVS 5.0.0 requirements classified; initial
  data inventory/flow, retention matrix, complete deletion/restore lifecycle, and operator rights,
  incident, and DPIA procedures.
- Accepted product outcomes: explicit operator claim preserves the existing sole scope when enabling
  login; logout preserves local offline access and pauses synchronization; complete account/scope
  deletion is specified now; authenticated-to-no-auth downgrades are rejected in the first release.
  Multi-user-to-single-user changes are also unsupported and fail closed.
- Remaining gates: authentication strength/mechanisms and numeric session limits belong to S8;
  production retention, transport, service credentials, deletion execution, and operator legal evidence
  belong to S12/S15 and deployment work. Offline retention/browser persistence and source-address
  logging deviations are explicit and preclude an unqualified ASVS Level 2 claim. S2-S15 are not
  marked implemented by this design validation.
- Validation evidence (2026-10-02): the documented `pnpm validate` suite passed, including contracts,
  frontend checks/tests/browser smoke tests, builds, and backend tests/PostgreSQL integration checks.
  Documentation review verified all 91 relative links in the 15 affected Markdown files, English
  content/path consistency, the pinned ASVS checksum, all 345 unique IDs/levels/dispositions, and the
  complete scenario/threat identifiers. Whitespace, ignore rules, and line-ending checks passed.
  Existing-code validation does not claim execution of the future AC-AS scenarios or ASVS controls.

### S2 — Define scope-neutral application boundaries and wire contracts

- Status: `validated` (contracts and parsing only, 2026-10-02).
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
- Artifacts: [access and binding contract refinement](../api/access-and-binding-contracts.md), TypeSpec
  discovery/bootstrap/confirmation and closed local/error models, generated OpenAPI/JSON Schemas,
  branded browser identities, validating Java identities, internal immutable access context, and
  strict parsers. One shared `access-contract-parsing` fixture contains eighteen schema groups and
  286 synthetic positive/negative cases; HTTP shapes exercise both emitters and local-only metadata stays outside
  public HTTP DTOs. Fixed generic errors and `no-store` headers are contract declarations.
- Handoff: S3/S4/S5 consume the identities and application types. S6 must implement expectation
  lifecycle, destination validation, empty-admission concurrency, local binding transactions, and
  actual response headers before advertising binding-aware exchange. Authentication/session/CSRF
  mechanisms remain S8 decisions. No scope resolver, migration, network adapter, or data transfer
  workflow is activated by S2; no runtime AC-AS or ASVS pass is asserted.
- Validation evidence (2026-10-02): the documented `pnpm validate` suite passed, including generated
  drift/lint/schema checks, frontend quality/type/architecture checks, 78 web unit tests, five browser
  smoke/offline tests, builds, 12 Java unit tests, and 25 PostgreSQL integration tests. The final ASCII
  origin refinement also passed contract validation and focused frontend/Java quality and parsing
  checks; all 285 shared cases agree. Documentation links, English content/path consistency, whitespace,
  ignore rules, and line-ending conventions were reviewed. Existing Checkstyle warnings remain in
  unchanged baseline code; new Java types/parsers introduce no such warnings.
- Cleanup validation (2026-10-03): consolidated the eighteen schema groups into one fixture without
  changing their 285 cases, removed repeated documentation additions, and reran `pnpm validate`
  successfully. All three consumers reject duplicate or unsupported schema groups and consume every
  declared case. Relative file links, UTF-8/LF conventions, and whitespace checks passed.
- Validation repair: the existing tombstone reload test now permits the coordinator's 15-second lease
  expiry and waits for the actual resumed cursor pair rather than an earlier request count. Runtime
  synchronization behavior is unchanged by this test correction.

### S3 — Partition the PostgreSQL synchronization model

- Status: `validated` (fresh-install database and synthetic service slice, 2026-10-10).
- Depends on: S1 and S2.
- Scope: add the schema version that creates server-instance and synchronization-scope state and makes
  scope part of every technical record, accepted receipt, change, tombstone, retired identifier, lock,
  uniqueness rule, foreign key, and index. Scope operation and record identity as
  `(synchronization_scope_id, identifier)`. Serialize publication within one scope without forcing
  unrelated scopes through the same lock.
- Migration rule: the repository is pre-production and no user-data ownership migration is required, but
  Flyway history remains versioned and tested. Do not rewrite a released or already-applied migration
  silently. S3 supports fresh installations only: V4 rejects any existing synchronization rows before
  changing the schema. Reset synthetic development databases and browser synchronization state; no
  populated-data backfill or ownership inference is supported.
- Security and privacy acceptance: every read and mutation is parameterized and requires a trusted scope;
  the same record and operation identifiers can exist independently in two scopes; database errors expose
  no content or identifiers through HTTP or logs; least-privilege database credentials and backup handling
  are documented for deployment work.
- Validation: clean migration, populated-database rejection and documented development reset,
  rollback, restart, constraints, indexes, concurrent publication, cross-scope collision, and transaction
  isolation tests pass with PostgreSQL.
- Implementation: V4 creates durable installation and UUID scope state, scoped keys/foreign keys/indexes,
  and transactional per-scope counters. Each acceptance locks its scope before receipt/record access;
  record-only advisory locks and the global sequence are removed. Every synchronization persistence
  operation requires the S2 immutable context and verifies installation/scope presence. Generationless
  cursors bind installation, scope, and position and reject foreign, legacy, and future positions.
- S4 handoff: the existing HTTP technical slice uses an internal adapter for its explicitly provisioned
  sole synthetic scope. Additional contexts exist only in integration-test sources. S4 replaces this
  compatibility adapter and contextless service entry points with request resolution and fail-closed
  mode configuration. No authentication, account relationship, binding adapter, or production access
  mode is activated by S3.
- Security/privacy review: synthetic fixtures only; no new personal-data category, credential, log
  identifier, or retention policy. Database T02/T10/T15 controls and AC-AS-03/04 isolation portions are
  exercised through scoped SQL, constraints, cursor checks, and controlled concurrency; HTTP access
  denial is generic and non-cacheable. Authentication and complete privacy/operational evidence remain
  S4/S8-S15 gates; no general ASVS or legal-compliance claim is made. Evidence addresses database
  portions of `v5.0.0-8.2.2`/`v5.0.0-8.4.1`, SQL parameterization under
  `v5.0.0-1.2.4`, and publication/resource ordering under `v5.0.0-15.4.3`.
  Production database privilege, protected transport, backups, and account authorization remain
  unverified; the documented deployment handoff does not mark those controls satisfied.

- Validation evidence (2026-10-10): `pnpm validate` passed, including contract checks, all frontend
  quality/build checks, 278 browser-side unit tests, nine Chromium smoke/offline tests, 68 Java unit
  tests, 51 PostgreSQL integration tests, backend quality/build gates, and four real-topology Chromium
  tests. Focused Spotless/Checkstyle/PMD checks passed; integration sources have no Checkstyle warnings
  and PMD reports no violations. Existing warning-only Checkstyle debt remains in baseline sources.
  Schema tests cover the fresh V1-V4 chain and rejection of each populated synchronization category
  before any V4 schema changes. Controlled concurrency proves an uncommitted A publication blocks a
  later A publisher while B commits using the same record/operation IDs. Restart retains identities,
  receipt results, and cursors; rollback includes the scope counter and deletion bundle.
- Manual review: both built application JARs contain V4 and exclude the synthetic context helper and
  integration test classes. Scope predicates, composite references, transaction/lock order, SQL
  parameterization, numeric pull ordering, minimized error diagnostics, development-only reset,
  least-privilege/backup handoff, and ADR-0018 English consistency were reviewed. Public HTTP shapes
  remain unchanged, so no TypeSpec/schema regeneration was required. S4 may start; S5-S7, authentication,
  production deployment/privacy controls, generation rollover, and real-user use remain separate gates.

### S4 — Enforce the trusted server access context

- Status: `validated` (2026-10-11).
- Depends on: S2 and S3.
- Scope: introduce the narrow access-context resolver, pass its immutable result through controller,
  service, and persistence calls, replacing S3's sole technical compatibility adapter and contextless
  service entry points. Consume the already-scoped persistence boundary. Implement explicit mode
  configuration with fail-closed startup validation. Provide a test-only resolver for automated scope
  isolation scenarios; do not ship a production header, query parameter, or request field that impersonates
  another scope.
- Security and privacy acceptance: authorization is centralized before workflow execution and verified
  again at the scoped persistence boundary; absent context, mismatched context, unknown mode, and disabled
  authentication deny access; `single-user-no-auth` requires explicit configuration and emits only a
  content-free configuration warning; production configuration cannot activate the test resolver.
- Validation: representative controller/service architecture rules, negative configuration tests, access
  denial tests, and log-capture tests pass.
- Implementation: [S4 implementation plan](s4-implementation-plan.md); mandatory
  `hortinis.access.mode` and `hortinis.access.public-origin`, atomic committed-mode setup on the fresh V4 schema, generic startup rejection
  of missing/unknown/authenticated/mismatched modes, and one static no-auth warning. Controllers
  resolve before operation validation/cursor lookup; services have no contextless overloads;
  persistence verifies instance/scope/implemented authority and committed mode. Test-only resolver
  selection remains in integration-test sources with no request impersonation input.
- Security review: Origin/Fetch Metadata rejects cross-origin browser mutations and simple-request
  content types. The mutation guard avoids raw-path matching so path parameters/alternate encoding
  cannot bypass it; Host/forwarded claims cannot relax it. An explicit public origin prevents
  DNS-rebinding trust. Same-origin compromise and non-browser
  no-auth access remain documented boundaries. No new credential/account processing or log fields
  are introduced; mode shares installation lifetime and request origin is transient only.
- Review findings fixed: removed all implicit service entry points, added unsupported-authority and
  committed-mode persistence checks, verified startup rollback/mode mismatch on restart, and removed
  raw-path and request-Host dependence from the origin guard. Representative architecture rules and production-source
  separation cover the trusted context and resolver boundary. English consistency reviewed.

- Validation evidence (2026-10-11): `pnpm validate` passed with contract/documentation checks,
  frontend quality/build checks, 278 browser unit tests, nine Chromium smoke/offline tests, 109 Java
  unit tests, 57 PostgreSQL integration tests, backend formatting/quality/build checks, and four real
  topology Chromium tests. Focused PMD checks report zero violations and new source files have no
  Checkstyle warnings; existing warning-only baseline debt remains. Both application JARs exclude
  the synthetic resolver/context helpers, integration tests, and removed compatibility adapter.
- Handoff: S5 may start. S6/S7 binding/topology, authentication/ownership, supported transitions,
  backup rollback protection, and real-user/security/privacy readiness remain their separate gates.

### S5 — Partition browser data by a durable local data-set identity

- Status: `validated` (2026-10-11).
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

- Implementation: [S5 plan and review](s5-implementation-plan.md). One physical Dexie database per
  local data set encloses every local table/query; version seven creates its offline UUIDv7 atomically.
  Older unowned schemas remain unchanged and rejected. Workflow transactions validate retained identity;
  identity-bearing leases fence foreign responses/retries even when other IDs collide. Binding storage
  accepts only matching-owner, closed, non-credential provenance; activation remains S6.
- Evidence: `pnpm validate` passed with 299 web unit tests, 11 standard Chromium tests, 109 Java unit
  tests, 57 PostgreSQL integration tests, four real-topology Chromium tests, and all contract, quality,
  build, documentation, and repository gates on the final reviewed revision. Coverage includes concurrent offline creation, real creation abort, reload/tabs,
  schema rejection with retained intent/no exchange, malformed identity/binding, local-state collisions,
  foreign response/retry/lease denial, independent recovery, and multi-tab expiry fencing.
- Review: corrected creation-event promise tracking, malformed-binding replacement, and array-shaped
  identity acceptance. Reviewed transaction boundaries, raw query isolation, unchanged operation wire
  shapes, diagnostic minimization, English consistency, and browser/shared-profile limits. S5 does not
  establish authentication, XSS protection, or binding activation; G2f remains in progress through S7.

### S6 — Implement safe server discovery and first binding

- Status: `validated` (2026-10-11).
- Implementation plan: [S6 implementation plan](s6-implementation-plan.md).
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

- Implementation: public closed no-store discovery, authorized bootstrap/preflight reobservation,
  15-minute bounded scoped expectations, transactional empty confirmation/first exchange, and fixed
  access errors. First admission shares the publication lock/transaction and never provisions a scope.
  Each content attempt reobserves exact destination identity without extending expectation lifetime.
- Browser: fresh Dexie eight stores owner-bearing readiness and initial progress separately from
  non-credential provenance. Explicit connection supports empty/empty and standalone-local/empty rows.
  Unbound startup stays local without submitted markers; populated first destinations pause for S14.
  Atomic activation advances exchange fences and invalidates leases; known acceptance/pull removes
  initial progress, while ambiguous exchange preserves original IDs/admission until bounded expiry.
- Validation: `pnpm validate` passed with 345 browser unit tests, 11 standard Chromium tests,
  109 Java unit tests, 67 PostgreSQL integration tests, five real-topology Chromium tests, nine browser
  architecture tests, contracts/generated-drift/schema/OpenAPI checks, documentation, builds, quality,
  wrapper/Compose/runner checks and repository checks. Disposal removes only test project volumes.
- Review: fixed expiry-timer completion races, stale binding-workflow writes, storage-only provenance
  activation, URL whitespace/control normalization, preflight identity checks with bounded issuance,
  database transaction promise tracking, unavailable access contracts, and retained-device/service-worker
  fixtures. New Java sources introduce no Checkstyle warnings; existing baseline warnings remain.
  Reviewed English consistency, DTO minimization, no-secret/prohibited-data diagnostics, exact IDs,
  rollback and scoped authority checks. No authentication, populated-state reconciliation, migration,
  full multi-scope topology or production privacy/security readiness is claimed; S7 may start.

### S7 — Validate scope isolation across the real topology

- Status: `validated` (2026-10-11).
- Implementation plan: [S7 implementation plan](s7-implementation-plan.md).
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

- Evidence (2026-10-11): final reviewed `pnpm validate` passed with 345 web unit tests, 11 standard
  Chromium tests, 112 Java unit tests, 68 PostgreSQL integration tests, six real-topology Chromium
  tests, nine web architecture tests, seven topology runner/Compose tests, and all documentation,
  contract, generated-artifact, quality, build and repository gates. A1/A2 share A while B independently
  replays colliding IDs and progresses during A acknowledgement loss; dependent bodies/revisions,
  receipt/journal values, tombstones, retirement, cursor/expectation substitution, reload and retained
  state after service restart are checked. Browser owner/retry/lease and diagnostic exclusion evidence
  complements S3-S6 transactional fencing. Generation isolation remains S13, since G2 is generationless.
- Review: production bootRun, main runtime, executable and plain archives exclude the separate fixture;
  production integration tests cannot load or activate it. Provisioning occurs only at controlled test
  startup. The resolver retains committed-mode checks and denies unknown/ambiguous markers. Dependency
  locks add configuration membership only, with unchanged coordinates/versions. English repository
  content, no public contract change, synthetic data and generated-project-only cleanup were reviewed.
  Review fixes corrected the one-shot mock enumeration and used the proxied DB-backed capabilities
  endpoint for restart readiness; the existing backend Compose health gate is retained.
- Handoff: G2f is validated for synthetic scope isolation; S8 may start. Authentication, enrollment,
  transitions, generations, reconciliation and production security/privacy readiness remain incomplete.

### S8 — Select the built-in authentication and session design

- Status: `validated` (2026-10-11), historical design; authentication mechanism/recovery policy
  partially superseded by ADR-0034 on 2026-10-17.
- Implementation plan: [S8 implementation plan](s8-implementation-plan.md).
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

- Artifacts: [ADR-0033](../architecture/decisions/0033-built-in-authentication-and-sessions.md),
  [authentication/session specification](../architecture/authentication-and-sessions.md), updated
  threat/ASVS/lifecycle records and architecture/development handoffs. Boot-managed Spring Security,
  WebAuthn/Password4j and Spring Session JDBC are selected for S9; no runtime dependency or endpoint
  is added in S8. Mandatory password plus user-verified passkey resolves B01 design; bounded two-proof
  recovery cannot grant data access. B02 design now has explicit session/challenge/ticket/recovery/
  enrollment/concurrency/CSRF/limiter/cleanup policy and ordered account/device/scope invalidation.
- Review: checked setup, normal/partial login, credential changes, recovery, enrollment, host recovery,
  expiry and logout for strength/authority bypass, enumeration, fixation, replay and secret persistence.
  Fixed delayed cookie deletion by forbidding logout/stale-data Set-Cookie; disclosed inactive-cookie
  residue and added all-tab target fencing. Added controlled raw-ticket directory/file cleanup and
  clarified minimum independent salt size. English content, dependency/contract sequencing, no-email
  autonomy, shared-profile/same-origin limits and security-log minimization were reviewed.
- Evidence (2026-10-11): final reviewed `pnpm validate` passed with 345 web unit tests, 11 standard
  Chromium tests, 112 Java unit tests, 68 PostgreSQL integration tests, six real-topology Chromium
  tests and all existing contract/documentation/generated-artifact/architecture/quality/build/repository
  gates. An additional register check confirms all 345 unique ASVS requirements and the reconciled
  counts: 168 Applicable, seven Selected L3, 40 Conditional, zero Pending decision, three Deviations,
  42 N/A and 85 Outside L2. These are applicability counts, not implementation passes. Library facts
  were checked against official framework sources; numeric values are accepted application policy.
- Handoff: S9 may start with TypeSpec/OpenAPI contracts before adapters and fresh databases. It must
  verify the licensed local password corpus, locked dependency compatibility, real crypto/session/
  transaction/browser behavior and cleanup. Authenticated modes still fail startup at S8. B01/B02
  executable evidence, B03-B05, D01-D03, operational/deletion/restore and S15 release gates remain open.

### S9 — Implement authenticated single-user access

- Status: `implemented` for the historical MFA baseline; S9-R R1-R3 are complete,
  R4-R6 are `planned`.
- Implementation plan: [S9 implementation plan](s9-implementation-plan.md).
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
- Evidence: TypeSpec/OpenAPI-first contracts and fresh-database persistence, host ticket command,
  runtime mode selection, session/CSRF filters, lifecycle cleanup, browser password-plus-passkey and
  recovery workflows, local/logout cookie fencing, strict frontend checks, PostgreSQL integration
  tests, and certificate-verified loopback HTTPS runtime tests. The default Compose example remains
  no-auth; TLS edge setup and applicable S15/foundation release gates remain deployment work.

### S9-R — Replace authentication with Spring email and password flows

- Status: R1-R3 `complete` (2026-10-17); R4-R6 `planned`.
  Normal authenticated startup remains closed.
- Implementation plan: [Spring email/password replacement](spring-email-authentication-plan.md).
- Depends on: the S9 scope/account boundary and ADR-0034; supersedes the old mechanism handoff.
- Scope: native Spring password authentication, sessions/CSRF/logout, Boot SMTP delivery, controlled
  email invitations, single-use password-reset tokens and deletion of obsolete MFA/recovery machinery.
- Acceptance: all R1-R6 checks in the replacement plan pass; account/scope and offline invariants hold;
  old implementation/dependencies/contracts are removed; production-code reduction is measured.
- R1 evidence (2026-10-17): [R1 completion/review record](spring-email-authentication-plan.md#r1-implementation-review-and-evidence--2026-10-17)
  and [inventory](spring-email-authentication-r1-inventory.md). Native form/session/CSRF/logout target
  contracts, token/email/resend/session policies, deployment/SMTP/privacy/threat/ASVS reconciliation
  complete. `pnpm docs:validate` passed eight tests and documentation checks; `pnpm contracts:validate`
  passed, with all 21 contract tests confirmed. Repository whitespace/tracked-ignore/text/EOL checks
  passed. Baselines: production Java 10,839 lines / 127 files, TypeScript 6,173 / 71, schema SQL 319 / 4;
  runtime additions/deletions zero. No application, dependency, SQL or deployment configuration changes,
  emails, push or deployment. Historical S8/S9 evidence is not validation of this target.
- R1 review: password-only strength reopens B01; synchronous SMTP timing, native lifetime/cap policy,
  invitation lifetime interpretation and runtime/token/revocation/browser evidence remain open R2-R6/S15
  gates. All 345 ASVS rows retained with counts reconciled; no new compliance/production claim.
- R1 review follow-up: host invite/resend/cancel target the pending account by validated email from
  protected standard input; no UUID discovery is needed after issuance failure. Runtime commands
  remain unimplemented. Findings 1/2 are open as
  [Q1/Q2](spring-email-authentication-plan.md#open-questions-required-before-s9-r-completion): public
  unknown-address exhaustion of the shared mail budget, and quota enforcement across isolated host
  processes/the HTTP service. Both must be resolved and validated by R6/end of S9-R; neither may be
  deferred to S15 or left open at S10 handoff.
- R2 implementation: [R2 record](spring-email-authentication-plan.md#r2-implementation-and-review--2026-10-17)
  tracks native form/provider/context/fixation, CSRF/logout, bounded input and verified-email/current-version
  authority. Removed custom normal-login and partial-session publication and their dependent retired
  routes. Fresh schema and a minimal masked-CSRF frontend adjustment precede full R3-R5 workflows.
  Final `pnpm validate` passed; historical S8/S9 evidence does not validate this boundary. Production
  authenticated admission and host workflows are closed, with no configurable bypass or second engine.
- R2 evidence (2026-10-17): 40 fresh native servlet/provider/JDBC cases; final full suite passed with
  231 Java unit tests, 130 PostgreSQL integration tests, 369 web unit tests, 11 standard and six topology
  Chromium tests, seven topology-runner tests, ten web architecture tests, 21 contract tests and all
  documented quality/build/documentation/repository checks. Reviewed native fixation/CSRF/logout,
  malformed/encoded inputs, eligibility, account/scope substitution, transaction locks, revocation
  rollback, stale saves, minimized events and English consistency. Both JARs exclude 23 removed main
  classes and synthetic authority fixtures. Java is 6,571 lines / 108 files (+710/-4,978); production
  TypeScript remains 6,173 / 71 (+1/-1), SQL 319 / four (+12/-12), using the R1 baseline rules.
  Removed dependent legacy setup/recovery code has no replacement yet; tests of deleted workflows
  were removed separately and are not counted as production reduction. Existing quality warnings,
  R3-R6/Q1/Q2, mailbox/strength, browser native-logout and production-readiness gates remain open.
- R2 review follow-up (2026-10-17): form charset parameter names now compare case-insensitively,
  with four new native-provider login cases and retained unknown-parameter/non-UTF-8 rejection.
  The [follow-up record](spring-email-authentication-plan.md#r2-review-follow-up--2026-10-17)
  tracks the regression and validation. Full `pnpm validate` passed with 44 native authentication
  cases and 134 PostgreSQL integration tests overall; authenticated production admission and Q1/Q2
  remain unchanged.
- R3 implementation: [R3 record](spring-email-authentication-plan.md#r3-implementation-and-review--2026-10-17)
  tracks shared hashed purpose/account/installation-bound JDBC action tokens, caller-transaction atomic
  consumption/replacement, bounded token/pending cleanup and post-commit Boot SMTP. Fixed fragment
  links, stored recipients, finite I/O and concurrent-send limits use synthetic local fixtures only.
  Validation evidence is recorded there; historical S8/S9 tests do not validate this design.
  Authenticated startup, retired commands and incomplete action routes remain closed. Q1's no-send
  budget exhaustion is reproduced, not resolved; Q2 remains open across isolated processes. R4-R6,
  full response timing, browser replacement and S10-S15 readiness remain incomplete.
- R3 validation (2026-10-17): final `pnpm validate` passed with 273 Java unit tests, 184 integration
  tests, 369 web unit tests, 11 standard and six topology Chromium tests, all contract/documentation/
  architecture/quality/build/repository gates. Fresh evidence is 42 mail unit cases, 46 R3 integration
  cases and four inert-link GET cases added to R2's native assembly; retained historical tests are
  regressions only. Focused strict dependency-verification checks passed. Production Java is 116
  files / 7,322 lines (+764/-14 versus R2); SQL four / 338 (+19/-0); TypeScript 71 / 6,173 unchanged.
  No legacy artifact is deleted without its future R4/R5 workflow replacement. Both JARs exclude
  synthetic fixtures; existing quality/bundle warnings and Q1/Q2 remain open.
- Handoff: S10 consumes S9-R after validation. No production-readiness or ASVS-level claim follows
  from adopting password-only login. SMTP/privacy and recovery-strength review remain S15 gates.

### S10 — Implement multi-user accounts and scope authorization

- Status: `planned`.
- Depends on: S7 through S9 and validated S9-R.
- Revised handoff: use controlled verified-email invitations; do not reintroduce old passkey or
  operator-ticket enrollment requirements.
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
- Depends on: revised S8 policy, S9-R and S10.
- Revised handoff: device management remains planned; passkeys/MFA and recovery codes are not
  prerequisites. Specify device authorization against the email/password baseline before implementation.
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
- Depends on: S6, S9-R and S10 through S11.
- Revised handoff: specify password reauthentication for sensitive transitions; historical two-factor
  freshness and operator-ticket ceremonies are not implementation requirements.
- Scope: implement explicit transitions from `single-user-no-auth` to `single-user-auth`, from
  `single-user-auth` to `multi-user-auth`, sign-out, account change, server change, server replacement, and
  local-data-set cloning or disconnection under ADR-0029. Reject authenticated-to-no-auth and
  multi-user-to-single-user transitions in the first release, including direct configuration edits and
  restored older modes. Never infer ownership from whichever user logs in first; controlled operator
  setup must explicitly perform and confirm the claim of the existing sole scope.
- Deletion scope: implement complete account/scope access invalidation and resumable purge across all
  inventory categories. Coordinate backup/restore suppression with P0.6 and S15; delete a local data set
  only by a separate explicit action. A deleted sole account never changes the server to no-auth mode.
- Security and privacy acceptance: sensitive transitions require recent authentication or controlled
  operator setup; use atomic, auditable state changes and generic external errors; preserve pending local
  intent without exposing it to another account; define local data retention or deletion on shared devices
  and implement the user's selected outcome without presenting deletion as secure erasure when browser or
  backup limitations prevent that guarantee.
- Validation: interrupted transition, rollback, stale session, concurrent administrator, wrong-account,
  replaced-server, offline pending logout, in-flight binding change, interrupted purge, backup/restore
  suppression, direct mode downgrade, and reload scenarios pass (AC-AS-06 through AC-AS-14).

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
- Depends on: S6, S13, and foundation G4. Authenticated uses additionally depend on validated S9-R or S10 as
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
- Revised authentication evidence: reassess password-only strength, mailbox recovery, verified email,
  SMTP delivery failures and privacy/retention. Do not inherit historical MFA compliance conclusions.
- Design baseline: consume the complete S1 applicability register and B01-B05 blockers rather than
  starting a new checklist. Verify complete deletion and restoration suppression, review D01-D03
  deviations explicitly, and distinguish verification target from requirements actually satisfied.
- Acceptance: no known blocking cross-account, session, configuration, logging, retention, recovery, or
  personal-data risk remains undocumented; `single-user-no-auth` limitations are prominent; authenticated
  modes fail closed; personal-data use in `single-user-no-auth` has a tested and documented compensating
  access boundary; evidence distinguishes automated checks, manual review, operator responsibilities,
  accepted residual risk, and matters requiring qualified legal or security review.
- Release rule: neither a shared multi-user deployment nor a field trial retaining real personal data is
  authorized until the applicable authenticated mode, recovery prerequisites, and this evidence pass.

## Dependency sequence

- Scope foundation: S1, S2, S3, S4, and S5 converge in S6, followed by S7.
- Authentication: S1 and S4 permitted historical S8; S6 and S8 permitted historical S9. S9-R now
  replaces that authentication baseline. S7 and validated S9-R permit S10; revised S8 policy, S9-R
  and S10 permit S11; S6, S9-R and S10-S11 permit S12. Historical mechanism requirements do not
  carry forward.
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
