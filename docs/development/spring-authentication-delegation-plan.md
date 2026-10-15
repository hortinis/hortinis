# Spring authentication delegation

## Scope

Preserve S9 user flows, public JSON contracts, offline behavior and security invariants on fresh
databases. Prefer existing Spring Security 7.1.1 and Spring Session JDBC 4.1.1 mechanisms.

## Implementation steps

1. Use Spring Security request contexts and factor authorization for synchronization access. Load
   current authority through a read-only context repository; never cache revocation-sensitive
   authority in the HTTP session. Keep transaction-time ownership and revocation checks. Review
   and test missing, partial, expired, revoked and valid authority before committing.
2. Delegate browser security headers, session fixation and local logout to Spring components while
   preserving explicit transaction boundaries, delayed-cookie protection and closed JSON errors.
   Review servlet and HTTPS tests before committing.
3. Audit password, WebAuthn, CSRF and persistence extension points against the exact installed
   framework source. Replace compatible custom mechanisms; document specific incompatibilities
   rather than inventing wrappers that reproduce the old machinery. Run the documented validation
   suite, review the final diff and commit the remaining changes.

## Constraints requiring application checks

- Scope ownership and revocation are rechecked while holding transaction locks.
- Authentication challenges are consumed in a separate committed transaction before proof parsing.
- Recovery grants restricted enrollment authority, never normal data access.
- Background synchronization does not extend user-interaction inactivity deadlines.
- Cookie changes are released after commit; delayed logout must not clear a newer cookie.
- Public-key persistence excludes original attestation and optional authenticator metadata.

## Framework assessment

Spring's Webauthn4JRelyingPartyOperations 7.1.1 expects retained registration attestation during
assertion verification and saves credential updates directly through its repository. Its internal
object converter is not publicly configurable. Replacing the minimized WebAuthn4j adapter with this
implementation would require additional reconstruction and publication adapters, or changes to the
privacy/concurrency guarantees. Keep library verification and supported counter handling unless a
smaller compatible integration is demonstrated.

## Step 1 implementation boundary and verification

The proposed production change affects `AuthenticationRuntimeConfiguration`,
`AuthenticatedSynchronizationAccessResolver`, `SynchronizationAccessConfiguration`, and a new
read-only Spring `SecurityContextRepository` adapter. Spring loads the current database authority
once per request and creates password and WebAuthn factor authorities only for a completed, current
normal login. The synchronization resolver maps that Spring principal into the existing immutable
application context. Database locks and current ownership/epoch checks remain in synchronization
transactions; a request-scoped principal cannot authorize a transaction after revocation.

Explicit route policy:

- `/api/v1/server/capabilities` remains public discovery.
- `/api/v1/auth/**` keeps its existing workflow-specific checks, CSRF and proof admission.
- Health probes remain available without account authentication.
- `/api/v1/sync/bootstrap`, `/api/v1/sync/bindings/confirm`, `/api/v1/sync/operations` and
  `/api/v1/sync/changes` require both Spring factor authorities.
- Unsupported routes fail closed. The separate no-auth configuration remains unchanged.

Review checks before accepting this implementation:

- Missing, malformed, partial, recovery-only, expired, wrong-mode and revoked authority must deny.
- A valid two-factor session must reach all four synchronization endpoints.
- Supplied account headers and arbitrary saved session principals must never grant identity.
- Revocation between request-context loading and transaction admission must still deny.
- Public discovery, anonymous account status and health probes must remain usable.
- Context lookup failure must produce the existing minimized unavailable response, including when
  Spring resolves its deferred context before `ExceptionTranslationFilter` runs.
- Request completion must clear the Spring context; no identity may leak to a reused worker thread.
- No request-context loading may allocate a session or renew user-interaction inactivity.

Run the resolver/context tests and real authenticated runtime tests first, then the complete backend
build, documentation and contract checks and browser/topology validation. Review the resulting diff
and commit only after those checks pass.

## Remaining framework boundaries

- Password hashing and verification already delegate to Spring PasswordEncoder/Password4j. The
  wrapper retains text/cost bounds and equivalent-cost rejection; DaoAuthenticationProvider would
  need additional snapshot plumbing while publication must still recheck that same account.
- Session storage, cookie serialization and rotation already delegate to Spring Session and the
  Servlet API. Database authority rows encode recovery/admission/absolute and explicit-interaction
  policies; replacing them with a principal cache would lose those checks or need another adapter.
- CSRF validation and storage already delegate to Spring Security. The extension enforces the
  selected 256-bit token, header-only contract and transactional bootstrap without implicit cookies.
- Recovery codes, protected operator tickets, scope ownership and proof publication are application
  workflows. Spring's generic one-time-token login does not implement their two-proof restricted
  recovery policy.
- The strict JSON parser preserves pre-body committed proof consumption, closed properties, exact
  scalar types, canonical binary encodings and document bounds. Framework defaults do not establish
  that public contract automatically.
- Browser offline binding and persistence are frontend application responsibilities.

These retained boundaries must not be described as hand-written cryptographic implementations.
The cryptographic work is delegated to maintained libraries; the remaining code defines policy,
binding, persistence and publication.

## Execution status

Step 1 was reviewed and committed after 266 unit and 282 PostgreSQL integration tests passed. Its
real Spring runtime test covers public discovery, all synchronization routes, partial/recovery-only
sessions, revocation and request identity cleanup. Frontend and contract checks also passed.

Step 2 delegates header emission and local invalidation to Spring components. The complete final
backend build passed, including 266 unit and 282 PostgreSQL integration tests, servlet/logout,
HTTP and signed-proof coverage, PMD, formatting and architecture checks. Documentation, contracts,
Compose, 366 frontend tests, 11 standalone browser tests and 6 topology browser tests also passed.
The disposable topology was removed after validation. The pre-existing production web bundle
budget warning remains unchanged.

The compatibility audit is complete. The remaining application policies cannot be replaced with
default framework mechanisms while preserving the accepted S9 contract. This migration increases
production Java by 109 physical lines: it adds the current-authority context adapter and framework
configuration while removing direct servlet identity lookup. It improves framework ownership but
does not deliver a large code-volume reduction. No schema, public contract, production dependency
or user-flow change was introduced.

## Boot session auto-configuration implementation plan

Use Boot's JDBC session auto-configuration for production repository creation and servlet filter
registration. Replace the manual production factory with the supported repository customizer,
transaction-operations and cookie-resolver beans. Keep REQUIRED transaction participation, closed
scalar serialization, 256-bit session identifiers, immediate saves, explicit filter ordering and
application-owned cleanup. Flyway remains the schema owner. Retain the old explicit factory only as
an integration-test fixture; the real Boot runtime tests must use the actual auto-configured bean.

Review and verify real session bootstrap, rollback, cookie rotation and authenticated request access.
No-auth startup may load the standard session infrastructure, but its stateless security chain and
application routes must neither allocate sessions nor emit cookies or use them as account authority.
Operator invocations remain non-web. Run backend, documentation, contracts and topology checks before
committing. This infrastructure step does not decide the pending larger session/recovery policy choice.

Boot session wiring passed the complete backend build and the final no-auth cookie/session regression,
including 266 unit and 284 PostgreSQL integration tests across the final check stages. Documentation,
contracts and six topology browser tests passed; disposable topology resources were removed.
Production Java decreased by 15 lines, with nine configuration lines added. The manual repository
factory now exists only in integration-test sources.

The user selected alignment with Spring's standard session and recovery flows on 2026-10-14.
The next implementation may revise bespoke S9 internal guarantees to use the framework model, while
retaining mandatory password plus user-verified passkey login, recovery features and offline sync.
This supersedes the earlier assumption that every bespoke internal guarantee must remain identical.

## Standard authentication rewrite sequence

1. Persist completed authentication with Spring's HttpSessionSecurityContextRepository and native
   User/factor authorities. Use Spring's JSON security modules for serialization; no password is
   retained. Remove the custom request security-context loader. Retain current database ownership
   checks during this intermediate step so the existing API remains reviewable and secure.
2. Move password authentication to AuthenticationManager/DaoAuthenticationProvider and passkeys to
   Spring relying-party operations, replacing the bespoke proof orchestration where compatible.
3. Replace parallel full-session authority, lifetime, session listing/revocation and admission with
   Spring Session and factor authorization. Retain application-specific account ownership, recovery
   codes and protected operator provisioning. Update the specification and tests for standard session
   activity/expiry behavior rather than perpetuating the old parallel state machine.
4. Review and measure the resulting simplification, run the documented checks, fix findings and
   commit each completed step. Mandatory two-factor data access and all recovery/offline user flows
   remain acceptance criteria.

Native context persistence passed 267 unit and 284 PostgreSQL integration tests, plus contracts,
documentation and six topology browser tests. The old custom request-context repository is removed.
The current transition retains transaction-time ownership and epoch checks while authentication is
persisted using Spring's credential-free User and factor authorities. Only account UUIDs are indexed;
login names and passwords are not cached. The next step replaces manual password authentication with
Spring's provider pipeline, followed by parallel authority/lifetime/session-management removal.

Password authentication now uses Spring's DaoAuthenticationProvider and ProviderManager for normal
login, step-up and password-based passkey recovery. The provider owns unknown-user dummy work and
credential erasure; a transient exact account snapshot supports transactional publication rechecks.
Storage failures retain their unavailable response rather than becoming credential failures. Review
excluded the temporary snapshot from serialization. The complete backend build passed 270 unit and
284 PostgreSQL integration tests, formatting, static analysis and architecture checks. Documentation
and contract validation also passed. This stage replaces provider orchestration; the larger session
state reduction remains in progress.

## Native session registry implementation plan

Use Spring Session's principal index to count, list and select completed sessions. Store only the
non-authorizing logout target alongside the existing native context and ceremony reference. Keep
native cookie identifiers internal. Remove the duplicated full-session admission and listing SQL;
revocation continues to remove the one-use ceremony records in the same transaction. Filter indexed
sessions by the current account epoch and native factor lifetime. Preserve the five-session limit,
account locks, two-factor admission and existing JSON shapes. The registry must use Boot's actual
repository; integration fixtures must enable the same principal index.

This step removes duplicated discovery and admission queries. The following lifetime step makes
Spring's request activity and factor authorization authoritative and removes the custom inactivity
clock. Validate concurrent admission, partial-session exclusion, same-account targeting, revoked
contexts, serialized target bounds, and the complete backend suite before committing.

Native session discovery passed the complete backend build with 271 unit and 284 PostgreSQL
integration tests, plus 366 frontend tests, documentation and contract validation. Review fixed
repository proxying, lazy repository resolution for non-web contexts, explicit native idle expiry,
and a rejected-principal fixture affected by standard principal indexing. Framework security/session
diagnostic logging is disabled to keep login names and cookie identifiers out of logs. Full normal
reauthentication now starts the new factor lifetime. The duplicate inactivity clock is the next step.


## Native request inactivity implementation plan

Delegate the 30-minute full-session inactivity timeout to Spring Session and Boot. Remove the
application interaction timestamp, its SQL writes, and the duplicate idle calculation. Retain the
activity route as a compatible authenticated acknowledgement. Ordinary requests, including background
synchronization, refresh native inactivity; password plus passkey reauthentication starts a new
12-hour factor lifetime. Limited proof ceremonies retain independent five-minute boundaries. Cleanup
reads native expiry metadata and removes expired transport/ceremony records under the existing locks.
Update TypeSpec descriptions before implementing this behavior and regenerate contracts. Review
password/passkey recovery, transaction-time epoch checks and offline binding behavior, then run the
backend, frontend, contract and topology checks before committing.

Native request inactivity passed the complete backend build: 269 unit tests and 286 PostgreSQL
integration tests, formatting, PMD and architecture checks. The obsolete interaction-clock unit
cases were replaced by native expiry/factor-age runtime coverage. A new concurrent activity/cleanup
regression verifies that cleanup locks and rechecks native expiry before deleting linked ceremony
state. Boot's timeout property owns native inactivity; transport rotation no longer accepts or sets
an idle lifetime. The separate management facade and interaction column are removed. This step
removes 84 production Java lines. Six real topology browser tests passed and their disposable
resources were removed; the 366 frontend tests also passed during this migration. Mandatory password
plus passkey authorization, recovery workflows, account epoch checks and offline synchronization
remain covered. The broader framework migration is still in progress; this measurement is for the
inactivity step, not a claim of an overall code-volume reduction.
