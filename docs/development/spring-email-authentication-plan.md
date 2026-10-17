# Spring email and password authentication replacement plan

- Status: R1-R4 complete (2026-10-17); R5-R6 planned.
  Normal authenticated startup is closed during replacement.
- Date: 2026-10-17.
- Track: S9-R, replacement of the implemented S9 authentication subsystem before S10.
- Decision: [ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md).
- Parent: [S-track implementation plan](access-and-synchronization-scope-implementation-plan.md).

## Objective and authority

Replace the existing authentication engine with Spring-owned email/password login, server sessions,
CSRF and logout. Preserve self-service recovery through email. Use Spring Boot Mail for one
operator-configured SMTP service. Retain Angular, one Spring Boot application, JDBC/PostgreSQL,
explicit account-to-scope ownership and offline-first synchronization.

This plan supersedes the future implementation requirements for mandatory passkeys/MFA, saved
recovery codes, operator-ticket browser recovery and preservation of old authentication contracts in
the [S8 plan](s8-implementation-plan.md), [S9 history](s9-implementation-plan.md) and
[previous delegation history](spring-authentication-delegation-plan.md). Those documents describe
historical decisions and the existing runtime; their validation evidence does not validate this rewrite.
The original plan-only commit `ce5c59b` changed no runtime or generated contracts. R1 updated
TypeSpec/generated contracts and design records ahead of runtime; R1 changed no application
code, runtime dependency, schema or deployment configuration. R2 replaces the backend login/session
boundary with production admission closed; it does not implement the remaining email or UI flows.
Implement against fresh databases; no compatibility migrations or old-API wrappers. Do not advance S10 or claim production readiness as part of S9-R.

## Initial product boundary

- Login requires a verified email address, password and active account.
- Account states are pending verification, active and disabled. Account UUID and scope UUID remain
  distinct, stable identifiers; email is never a synchronization owner or partition key.
- Enrollment is controlled by an operator invitation. No public registration or first-visitor claim.
- Forgotten-password recovery uses a short-lived single-use email token and grants only password reset.
- Password reset and authenticated password change revoke existing sessions and require normal login.
- Email addresses cannot be changed through the initial UI/API. Correct erroneous pending invitations
  by cancellation and explicit new invitation; never transfer an active account to another mailbox.
- Defer passkeys, mandatory MFA, recovery-code packs, device enrollment implementation, email-change
  workflows, external identity providers, delivery queues and provider-specific mail APIs.
- Local-only and explicitly selected no-auth use remain available without SMTP. Authenticated
  enrollment and reset require working mail delivery. No automatic mode downgrade on SMTP failure.
- Mailbox control is the recovery authority. Loss of both password and mailbox has no self-service
  recovery path in this increment; do not add an implicit operator login or reset bypass.

## Required Spring component mapping

Use the Boot-managed dependency versions. Reuse native extension points rather than creating wrappers
that reproduce the previous workflows. Record any unavoidable exception and the code it replaces.

| Responsibility | Required Spring component | Application responsibility |
| --- | --- | --- |
| Password login | `HttpSecurity.formLogin`, `UsernamePasswordAuthenticationFilter`, `DaoAuthenticationProvider` | Small `UserDetailsService` mapping email to stable account identity and current account status/version |
| Password hashing | `PasswordEncoder` / `DelegatingPasswordEncoder`, existing accepted Argon2 encoder | Retain password policy, local blocklist and bounded expensive work |
| Authentication persistence | `HttpSessionSecurityContextRepository`, native session authentication strategy | Current-account/security-version checks; no custom authentication publication engine |
| Sessions | Boot session auto-configuration and `JdbcIndexedSessionRepository` | Account-wide revocation through the native repository; scope checks in protected transactions |
| CSRF | Spring Security CSRF support and the supported Angular/SPA integration | Fetch/refresh tokens at framework lifecycle boundaries; never persist tokens in local storage |
| Logout | Spring Security logout filter and handlers | API response and offline UI coordination |
| HTTP authorization | `SecurityFilterChain` / `authorizeHttpRequests` | Explicit account eligibility and account-to-scope authorization; deny unspecified routes |
| API responses | `AuthenticationSuccessHandler`, `AuthenticationFailureHandler`, `AuthenticationEntryPoint`, `AccessDeniedHandler` | Small fixed response shapes; no HTML redirect for synchronization APIs |
| Email | `spring-boot-starter-mail`, Boot mail auto-configuration, `JavaMailSender`, `SimpleMailMessage` | Narrow replaceable delivery adapter, two templates, fixed trusted application URL |
| Invitation/reset tokens | Spring JDBC `JdbcClient` and Spring transactions | Hashed, purpose-bound, expiring tokens and atomic consumption; these are action tokens, not login |
| Account mutations | Spring-managed services with `@Transactional` or `TransactionTemplate` | Activate account, replace password, increment security version and revoke sessions |
| Retention | Spring scheduling and JDBC | One bounded cleanup of expired invitation/reset records; Spring owns session expiry |
| Audit | Spring authentication events where suitable | Existing minimized event vocabulary, redaction and documented retention |

Do not use `oneTimeTokenLogin()` for invitation acceptance or password reset: these operations must
not create ordinary authenticated sessions. Spring supplies the infrastructure; the small action-token
service implements application semantics. Do not introduce a custom authentication filter, password
login controller, factor accumulator, session state machine or second session-authority table.

Use the native thirty-minute inactivity timeout initially. Remove MFA factor-age/step-up rules and
bespoke completed-session counting. Any concurrency cap must use a supported Spring session policy
and document its behavior rather than retaining custom full-session accounting. Keep HTTPS, secure
host-only cookies, CSRF, same-origin routing and minimized security headers/logging.

## Permission and contract matrix

Define TypeSpec/OpenAPI before adapters, including Spring's form-encoded login request and fixed API
responses. R1 settles exact paths, encodings and errors in the [API handoff](../api/authentication-contracts.md)
and TypeSpec; the table below summarizes their responsibilities.

| Operation | Authorization and result |
| --- | --- |
| Capabilities, CSRF bootstrap, anonymous session status | Public, no private account information |
| Password login | Valid password for verified active account; native session establishment |
| Request reset | Public, rate-limited; identical acknowledgement for eligible and unknown addresses |
| Accept invitation | Unexpired invitation token plus policy-valid password; activate, no auto-login |
| Complete reset | Unexpired reset token plus policy-valid password; revoke sessions, no auto-login |
| Data, identity and synchronization | Current active account/security version and owned scope |
| Change password | Current authenticated account and current password; revoke sessions |
| Logout | Native session logout with CSRF protection |
| Issue/resend invitation | Authorized host command only; never exposed as public registration |
| Unspecified routes | Denied |

All unsafe browser operations retain CSRF protection, including token completion and login. A token
or email address never selects an arbitrary scope. Token purpose cannot be changed by the request.
Document token-free error responses, no-store behavior and how the PWA handles expired authentication.

## Email and token lifecycle

Use one shared token component with explicit invitation/reset purposes, account binding, hashed random
secret, expiry and atomic consumption. R1 fixes the [numeric policy](../architecture/authentication-and-sessions.md#numeric-policy):
24-hour invitations, ten-minute resets, 60-second email cooldown and three/hour plus ten/day email
mail-attempt quotas, with independent request/source/global limits. These are application choices, not
implicit framework settings. Validate bounded ASCII email, preserve delivery case and uniquely
canonicalize the entire address to lowercase; no provider-specific alias rewriting. Pending account
reservations expire seven days after original creation, irrespective of resend.

Opening a link must not consume the token or mutate account state. Completion requires an explicit
POST, protecting against mail scanners and previews. Build links from a trusted configured origin,
never request Host headers. Prevent token leakage through request logs, referrers, analytics or browser
persistence. Use fixed trusted-origin fragment links read into transient memory and cleared before other page work.

For invitation acceptance/reset, validate the password before the transaction; then recheck the
account and token, consume it, update credentials/status and invalidate other applicable tokens in one
transaction. A rollback preserves both prior credentials and the usable token. Concurrent completion
has one winner. Reset/change increments the security version and revokes native sessions in the
mutation boundary; protected requests recheck current authority so stale session writes cannot restore
access. Resend replaces the preceding token. A successful reset never returns a garden session.

Use synchronous SMTP with explicit connection/read/write timeouts and no delivery queue initially.
Send after committing token issuance; do not hold a database transaction during network delivery.
Send only to the account's stored address. Mail failure never activates an account or changes a
password; the user/operator can resend. Keep public reset acknowledgement generic even on delivery
failure, with minimized operational failure reporting. Rate-limit unknown and known addresses alike
and review observable timing differences before release. SMTP secrets are deployment secrets, never
committed. Do not log recipients, tokens, mail content or provider exception messages.

## Implementation increments

Every increment starts with analysis and a concrete implementation plan, then implementation, code
review, fixes, appropriate validation and a commit. Delete replaced code with its replacement; do not
leave two enabled authentication systems. Intermediate work must not enable an incomplete authenticated
mode. Preserve no-auth isolation and synthetic test fixtures.

### R1 — Decision, contracts and inventories

- [x] Finalize lifecycle bounds, endpoint contracts, email policy and the simplified session policy.
- [x] Reconcile ADR-0033, the authentication specification, API handoff, deployment instructions,
  threat model, ASVS mapping and privacy inventory with ADR-0034. Mark obsolete assertions explicitly.
- [x] Inventory deletions and establish production Java/TypeScript/schema line-count baselines.
- [x] Review mailbox recovery, pending-account authorization and SMTP failure behavior.
- [x] Validate documentation and contracts; do not claim the target runtime exists yet.

#### R1 implementation, review and evidence — 2026-10-17

- Analysis: inspected clean working tree at `ce5c59b`, root guidance, ADR-0034 and the S-track; proposed
  policy/contract/inventory-only changes before implementation. No unrelated starting changes existed.
- Decisions: native form login (`email`, `password`), native thirty-minute request inactivity without
  absolute deadline/cap, bodyless native logout, session-backed CSRF and explicit browser refresh.
  Fixed email syntax/canonical uniqueness, pending correction/purge, token bounds, rate/send/cleanup
  limits, SMTP I/O bounds and post-commit failure behavior are now in the authentication specification.
  Invitation/reset completion never logs in; reset/change revoke all account sessions transactionally.
- Contracts: eight replacement auth paths, form-encoded login, minimal status, JSON action/password
  requests, fixed errors and no-store/joint CSRF-cookie declarations. Regenerated OpenAPI/schemas from
  TypeSpec; removed obsolete MFA/ticket/recovery-code/session-target schemas from the target API.
  Runtime still implements historical S9 and does not conform to this replacement contract yet.
- Inventory: [deletion/retention map and reproducible baselines](spring-email-authentication-r1-inventory.md):
  10,839 production Java lines (127 files), 6,173 production TypeScript lines (71 files), 319 schema SQL
  lines (four files). Production additions/deletions are zero in R1; generated contract removal does
  not count as runtime reduction. Preliminary TypeScript total was corrected to exclude test support.
- Review/fixes: tightened the initial reset proposal from 30 to ten minutes; retained explicit 24-hour
  activation classification review. Added pre-native-filter syntax validation because Spring trims
  usernames, equivalent-cost ineligible-account provider failure, form duplicate/query/encoding bounds,
  BREACH-masked CSRF wire characters, strict end-of-input token/email/CSRF patterns and fixed
  auth-specific 400 schema. Native logout supersession is
  reconciled across offline/access handoffs. Mail-attempt quotas charge unknown/ineligible/failure
  branches before lookup; no timing claim follows. Pending-account lifetime cannot be extended by resend.
- Security/privacy review: mailbox loss/takeover/reassignment, pending authorization, token consumption/
  replacement/rollback/lost response, SMTP failure/uncertain delivery and stale-session writes reviewed.
  Threats T16-T21 and SMTP/mailbox inventory added. ASVS retains all 345 unique rows: 171 Applicable,
  seven Selected L3, 37 Conditional, zero Pending decision, three Deviations, 42 N/A, 85 Outside L2.
  B01 strength is reopened; native idle-only sessions and invitation lifetime interpretation require
  S15 review. Historical MFA evidence cannot satisfy the replacement.
- Validation: `pnpm docs:validate` passed (eight tests plus links/anchors/ADR/index checks);
  `pnpm contracts:validate` passed (TypeSpec format, exact regeneration, local/schema/Redocly lint,
  contract tests and invalid-OpenAPI rejection). `pnpm contracts:test` with normal Node subprocesses
  confirmed all 21 tests, including six replacement authentication checks. Documentation Git subprocess
  access initially hit sandbox EPERM; the normal-permission rerun passed. Final repository whitespace,
  tracked-ignore and text/EOL checks passed. Baseline reproduction matched the recorded totals.
- Scope review: English repository content, synthetic `.test` mailboxes/structural tokens, no secrets,
  application code, SQL, dependency/lock, Compose or deployment configuration edits. No email sent,
  push/deployment, R2, S10 or new runtime validation. Full application/browser/topology suite was not
  run for this contract/documentation-only increment and is not claimed as new design evidence.
- Handoff: R1 complete only. R2-R6 remain planned; S9-R is not implemented/validated and S10 remains
  blocked. SMTP timing equalization/delivery, native runtime/races/browser tests, B01/S15 risk review
  and the independent device/transition/deletion/privacy/deployment gates remain outstanding.

#### R1 review follow-up — 2026-10-17

- Finding 3 addressed in the host-command contract: invite/resend/cancel all accept one validated email
  through protected standard input. Resend/cancel resolve only the matching existing pending account,
  preserve stored delivery address/scope and recheck eligibility transactionally. No generated UUID
  discovery, new lookup API or database inspection is required after a failed initial send. R4 must
  test this workflow and reject unknown/active/disabled targets without mutation. Runtime remains
  unchanged; this fixes the R1 specification, not an implemented command.
- Findings 1 and 2 remain open as Q1/Q2 below. Their final resolution is a
  mandatory R6/end-of-S9-R gate, not deferred S15 work. R1 stays complete with these explicitly tracked
  follow-up questions; S9-R cannot be marked complete or handed to S10 while either remains open.
- Follow-up validation: `pnpm docs:validate` passed eight tests plus documentation checks;
  `pnpm contracts:validate` passed all 21 contract tests, generation/format and lint checks. Reviewed
  the failed-initial-send, case-variant and ineligible-target command paths against the specification.
  Repository whitespace, tracked-ignore and text/EOL checks passed; only English documentation
  changed. No runtime command, dependency, schema, HTTP contract or real mail behavior is implemented
  or validated by this follow-up.

### R2 — Native password login and session lifecycle

- [x] Configure Spring form login, provider, context persistence, CSRF, logout and API handlers.
- [x] Adapt account identity and current-authority checks to verified email/password authentication.
- [x] Remove factor/partial-session publication and custom normal-login orchestration as replaced.
- [x] Test unknown/disabled/pending accounts, wrong passwords, fixation, CSRF, expiry and account
  isolation. Check stale security versions cannot authorize protected transactions.

#### R2 implementation and review — 2026-10-17

- Starting state: clean at `c1f85ad` after R1 `f56bee1`; read repository guidance, ADR-0034,
  authentication/session/API policy, R1 inventory and S-track before proposing the implementation plan.
- Admission: normal `single-user-auth` startup now rejects before installation mode initialization;
  there is no production bypass property/profile. Existing host-ticket commands fail before database,
  file or listener activity. No-auth keeps its stateless chain; auth routes return fixed no-store 503
  without allocating a session. The replacement graph is exercised only by test-source assembly.
- Native boundary: form login at `/api/v1/auth/login`, one `DaoAuthenticationProvider`, erased native
  UUID/version principal, native context persistence/fixation, session-backed BREACH CSRF and native
  bodyless logout. Success/failure/entry-point/denial handlers emit closed JSON with no redirects.
  Input validation precedes native trimming/parsing and crypto, including encoded-body bounds,
  duplicate decoded fields, query rejection, exact email/password policy and malformed UTF-8/escapes.
- Storage/authority: fresh V4 email/canonical uniqueness, pending/active/disabled status, nullable pending
  password, verification timestamp and security version. Current authority uses native primary rows,
  principal indexing, verified active account/version and committed installation/scope/mode.
  Protected transactions retain account-before-session-before-scope ordering; native repository
  deletion participates in the account transaction. No action-token schema or mail dependency added.
- Deletions: removed custom normal login/publication, factor/freshness/partial-session state,
  admission/counting, authority-reference attributes/index, target logout/session APIs and retired
  mutation controller. The old setup/recovery/credential services depended on that state and were
  removed for compilation/coherence; their R3/R4 replacements remain unimplemented. Legacy WebAuthn
  adapter/dependency, ticket-file primitives and obsolete tables remain dormant for R4/R5 deletion.
  Removed historical workflow tests exercised those deleted behaviors; they do not validate the
  replacement. No tests were moved into production or counted as production reduction.
- Minimal frontend adjustment: accept the R1 BREACH-masked CSRF alphabet in transient memory, with a
  focused test. Old screens/transport workflows remain R5 work and are not exposed by an enabled mode.
- Review/fixes: eliminated duplicate local/global provider registration (failed-password work
  ran twice); excluded other tests' configuration from the isolated native assembly. Verification
  counters assert one expensive comparison for unknown/pending/disabled/unverified/wrong-password
  paths. Native provider events record only minimized verification outcomes; they do not claim an
  atomic transaction publishing a login. Native session expiry has no factor deadline or count cap.
  Tightened percent escapes to ASCII hex, frontend CSRF end-of-input validation and cookie-only URL
  transport. The syntax/origin guards now use Spring's own decoded servlet-path matchers and reject
  encoded auth paths; real HTTPS tests prevent encoded-path query/form bypass before native trimming.
- New evidence: 40 cases in `NativeEmailAuthenticationIntegrationTest` use the real provider, hashing,
  filter chain, native JDBC sessions and PostgreSQL. Request admission is replaced by a controlled
  test double there; the real bounded limiter/crypto gates have separate unit coverage. Cases cover
  unknown/pending/disabled/unverified/wrong-password equivalent-cost failure, exact form encoding,
  invalid/oversize/chunked/duplicate/query/media/Unicode inputs, native fixation/CSRF/logout/cookies,
  expiry/inactivity renewal, minimal status, storage failure, identity/scope substitution, version
  invalidation, rollback and account/session transaction locks. A delayed real login after committed
  version change saves a stale context that cannot authorize status or protected synchronization.
  No-auth HTTP and isolated host-process checks exercise closed admission without session/file writes.
- Manual security/privacy review: current authority is checked inside content transactions; native
  primary rows replace application authority references; version fencing prevents stale saves restoring
  access. Reviewed T01-T04/T07-T08/T11/T13/T15 and native portions of ASVS
  `v5.0.0-7.2.3`/`v5.0.0-7.2.4`, `v5.0.0-3.5.1`, `v5.0.0-8.2.2`/`v5.0.0-8.4.1` and
  `v5.0.0-15.4.3`; this is bounded evidence, not a
  requirement-level compliance finding. Native-event audit stores fixed verification/logout outcomes,
  with daily/startup thirty-day retention; no subject, email, source, token, password or session mapping.
  Synthetic `.test` mailboxes, runtime-generated test TLS material and canary diagnostics only.
  Repository content/path language reviewed under ADR-0018. Password-only strength, native lifetime,
  deployment/privacy controls and R5/R6 browser logout/tab behavior remain independent open gates.
- Custom-code review: the input guard and email validator implement R1 wire policy that the native
  filter does not enforce; they never authenticate. User lookup maps email to UUID/version; current
  account/native-row queries retain transactional scope authorization. The existing allowlisted JSON
  converter, fixed response handlers, observation and minimized event/retention adapters remain small
  application boundaries. None recreates native context publication, fixation, CSRF or logout.
- Validation: final `pnpm validate` passed: documentation/link/ADR checks (eight tests), 21 contract
  tests plus exact generation/format/lint checks, frontend format/lint/type/architecture checks (ten
  architecture tests), 369 browser unit tests, 11 standard Chromium tests, frontend development and
  production builds, wrapper/toolchain checks, backend build/Spotless/Checkstyle/PMD, 231 Java unit
  tests, 130 PostgreSQL integration tests, seven topology-runner tests and six real-topology Chromium
  tests, plus whitespace/tracked-ignore/text/EOL checks. Focused strict-verification native/quality
  checks also passed. Existing Checkstyle debt and Angular bundle-budget warnings remain; changed
  R2 production and integration sources introduce no Checkstyle warnings and PMD reports zero
  violations. Removed workflow tests and retained historical adapter/UI tests are not new-design
  evidence. Both application JARs exclude all 23 deleted production classes and synthetic authority
  fixtures, and contain the native input guard.
- Validation repairs: documentation Git subprocess initially needed normal sandbox permissions;
  rerun passed. Overlapping an early formatter with Gradle disturbed its generated cache; stopped the
  daemon, retained the old cache separately and reran serially. The first full run stopped on the new
  hardening's formatting/duplicate-literal checks; fixes, focused checks and the final complete suite
  passed. No failure is represented as a validation pass.
- Size evidence against R1's `ce5c59b` baseline (same physical-line/exclusion rules): production Java
  108 files / 6,571 lines, +710/-4,978 (net -4,268); production TypeScript 71 / 6,173, +1/-1;
  fresh-schema SQL four / 319, +12/-12. Tests are separate: +1,184/-7,232 lines, including the new
  servlet suite and removed obsolete workflow fixtures; no test movement contributes to production
  reduction. HTML/SCSS, dependencies, locks, generated contracts and deployment topology are unchanged.
  Documentation and application session-tracking YAML are separate from those source totals.
- Handoff: R3-R6, Q1/Q2 and S10-S15 remain open. No email sent, mail dependency, reset/invitation/password
  mutation implementation, full UI replacement, deployment, push or production-readiness claim.

#### R2 review follow-up — 2026-10-17

- Fixed the form content-type parameter allowlist to compare names case-insensitively. Valid UTF-8
  forms with `Charset`, `CHARSET` or mixed-case names now reach native login; unknown parameters and
  non-UTF-8 charsets remain rejected before password verification.
- Added four real-provider login regression cases covering parameter-name casing and extended the
  negative media checks with uppercase unknown parameters and mixed-case non-UTF-8 charset names.
  Before the fix, lowercase passed and the other three cases failed with 400 as expected.
- Validation: focused strict-verification checks passed all 44 native cases plus Spotless, Checkstyle
  and PMD. Final `pnpm validate` passed, including 231 Java unit tests, 134 PostgreSQL integration
  tests, frontend unit/browser tests, contract/documentation checks, builds and all six topology
  browser tests. Existing Checkstyle and Angular bundle-budget warnings remain; changed Java files
  have no Checkstyle warnings and PMD reports no violations. Final documentation checks also passed.
- The public contract and production admission gate are unchanged; R3-R6, Q1/Q2 and S10 remain open.

### R3 — Shared action tokens and Boot email

- [x] Add the Boot mail starter, explicit SMTP timeouts and narrow delivery adapter; update dependency
  locks and verification metadata. Use a local synthetic mail test double, never real recipients.
- [x] Implement minimal invitation/reset persistence with hashed secrets, purpose, expiry and atomic
  consumption; use Spring JDBC/transactions rather than an authentication/session engine.
- [x] Test expiry boundaries, wrong purpose, replacement, concurrent consumption, failed delivery and
  rollback. Verify fixed origin, no secret logging and that GET never consumes tokens.

#### R3 implementation and review — 2026-10-17

- Scope: approved analysis against clean `c3723ba`; shared internal action primitives only. No action
  controller, account activation/reset/change workflow, host command or browser replacement is exposed.
  Normal authenticated startup and retired commands remain closed. Native R2 filter/provider/session,
  CSRF/logout, transaction-time scope authorization and security-version fencing remain authoritative.
- Storage: fresh V4 action table binds account/installation and a closed invitation/reset purpose,
  SHA-256 verifier, creation/expiry and one current token per account/purpose. Raw canonical 32-byte
  CSPRNG secrets are transient delivery material only; no token archive or consumption history.
  `JdbcClient` primitives require writable caller transactions, lock account before token, recheck
  current eligibility and enforce `now < expiresAt`. Invitations last at most 24 hours and never
  beyond seven days from original pending creation; resets last ten minutes. Replacement/consumption/
  invalidation delete or replace verifiers transactionally; caller rollback restores prior state.
- Mail: Boot-managed mail starter, JavaMailSender and SimpleMailMessage behind one replaceable
  synchronous delivery interface. Explicit 5,000 ms connect/read/write limits, two concurrent attempts per process,
  no waiting queue/retry. Issuance commits before delivery, with enclosing/after-commit transaction
  synchronization rejected. Stored validated recipient, fixed sender and two plain-text templates use
  only the trusted HTTPS origin and prescribed action fragments. Failed/uncertain send keeps the
  committed replacement, never restores its predecessor or changes credentials. Only fixed delivery
  outcomes and fresh references enter diagnostics. Required STARTTLS/certificate checks and disabled
  protocol debug/startup/mail-health probes are validated without sending a startup message.
- Admission: provisional 60-second, three/hour and ten/day canonical-email windows and 30/hour plus
  100/day installation windows precede account lookup and share invitation/reset attempts without
  refund. Email HMAC keys retain 24-hour idle state; request keys retain 15 minutes. Both limiters share
  one 4,096-key ceiling and deny without evicting restrictions. Request/source admission for future
  action endpoints remains R4; the unchanged R2 login/read limiter still owns existing HTTP gates.
- Cleanup: startup and each 60-second tick process one batch of at most 500 expired tokens and one
  batch of at most 500 overdue pending accounts. Account locks precede token deletion; locked rows
  may wait for a later tick. Pending removal cascades its dependent authentication rows, releases the
  reservation and preserves scopes, garden data and committed mode. There is no unbounded drain loop.
  Spring still owns session expiry; daily/startup thirty-day security-event retention is retained.
- Review/fixes: added a shared aggregate admission-key ceiling, exact pending-deadline recheck before
  issuance, floor-to-microsecond JDBC cutoffs to prevent early cleanup through timestamp rounding,
  fixed configuration failures and validation of bound mail-property maps. The internal selector/reservation hook runs only after admission inside issuance, allowing R4
  to compose provisioning atomically without a separate prelookup transaction. No raw token,
  recipient, message, provider exception or stable account/scope value is logged. Action tokens never
  pass through login, publish a SecurityContext or acquire data authority. Custom code implements R1
  action semantics, transport isolation and numeric bounds; it does not replace a Spring engine.
- New tests: 41 R3 PostgreSQL primitive/cleanup/transaction cases and five local SMTP cases,
  including deferred commit rejection, rollback of token plus account fields, wrong purpose/binding,
  replacement/replay, two concurrent consumers, concurrent post-commit deliveries, exact/submicrosecond
  expiry, seven-day pending purge, batch/locked-row bounds and sanitized startup/scheduled failures.
  Four additional R2 native GET cases prove action-link opening preserves token/account state. New
  database-independent mail configuration/template/admission checks also cover the shared key bound.
  Existing native R2 tests remain regression evidence for R2, not R4 workflows.
- Timing: one focused synthetic internal sample measured accepted SMTP at 98,802 microseconds,
  refusal at 23,210, unknown at 11,436 and quota denial at 113. These are local fixture samples,
  include JDBC/assertion overhead and are not stable performance bounds or public HTTP measurements.
  Real local read/write stalls each exercise the 5,000 ms I/O limit; the synthetic connect socket
  verifies the actual transport receives 5,000 ms without external network access. A real SMTP
  acceptance is still not proof of inbox delivery. No timing equalization claim follows.
- Size against R2 `c3723ba`: production Java +764/-14 lines (116 files / 7,322 total), SQL +19/-0
  (four / 338), TypeScript +0/-0 (71 / 6,173). Tests are separate (+1,499/-2), with no moved tests or
  historical-test deletion counted as production reduction. Against R1's `ce5c59b` baseline, Java
  is +1,469/-4,986 (net -3,517), TypeScript +1/-1 and SQL +31/-12. HTML/SCSS and generated contracts
  are unchanged; build/dependency/lock/verification/YAML and documentation changes are separate.
- Deletions: no legacy workflow is coherently replaced by R3's internal primitives alone. Dormant
  ticket/file, WebAuthn and obsolete table/browser removal remains R4/R5. No tests of deleted historical
  workflows count as replacement evidence, and no compatibility wrapper is introduced.
- Validation: final `pnpm validate` passed: eight documentation tests plus link/ADR/index checks,
  21 contract tests and exact generated/format/schema/OpenAPI checks, ten web architecture tests,
  frontend format/lint/type checks, 369 web unit tests, 11 standard Chromium tests, development and
  production builds, wrapper/toolchain checks, backend build/Spotless/Checkstyle/PMD, 273 Java unit
  tests, 184 integration tests, seven topology-runner tests, six real-topology Chromium tests and
  whitespace/tracked-ignore/text/EOL checks. The 42 new mail unit cases and 46 R3 integration cases
  are fresh evidence; full-suite totals also include retained historical regressions that do not
  validate the replacement design. R2's 48 native cases include four new inert-link GET regressions.
  Focused `--dependency-verification=strict` R3/native/limiter and quality checks passed. Boot-managed
  Mail/Angus/Jakarta artifacts were reviewed with locks/verification metadata; no unrelated version
  upgrade. Both application JARs include the new store and exclude R3/topology fixtures. Changed Java
  sources have no Checkstyle warnings; PMD reports zero violations. Existing Checkstyle debt and
  Angular's 2.51 kB initial-bundle warning remain. Fixtures use disposable PostgreSQL, synthetic
  `.test` addresses and loopback SMTP only; no real email, SMTP credential, push or deployment.
- Validation repairs: early static checks found naming/duplicate-literal/format issues; all were fixed.
  An initial full run stopped at Spotless after the final composition hook was added during that run;
  formatting and focused checks passed, then the complete suite passed on the final frozen code.
  Failed or superseded runs are not reported as passes. Final evidence/documentation checks also pass.
- Constraints: Q1/Q2 remain open for R6, before S9-R completion/S10. The new unknown-attempt fixture
  intentionally reproduces Q1's exhausted shared budget after 30 no-send attempts; this is evidence of
  the unresolved defect, not availability or complete quota enforcement. Process-local counters still
  cannot enforce quotas across separate HTTP/host processes. R4 must compose provisioning and token
  issuance atomically and supply generic public responses; R4/R6 must measure/equalize complete
  known/unknown/failure/quota response timing. Local transport timeout tests do not prove enumeration
  resistance. R5 fragment clearing/browser/offline flows, R6 completion and all S10-S15 gates remain open.

#### R3 origin review follow-up — 2026-10-17

- Fixed the mail adapter's case-sensitive HTTPS scheme check and raw-origin rendering. Browser checks,
  action links and the remaining dormant WebAuthn adapter now use one framework-independent trusted
  application-origin parser, extracted from R2's existing policy. It accepts mixed-case HTTPS,
  lowercases DNS hosts and removes explicit port 443 while preserving non-default ports and existing
  R2 trust restrictions. Removed the two duplicate validators and the old WebAuthn-owned public helper
  after updating every caller; no compatibility wrapper or retired workflow is introduced.
- Regression: before the fix, uppercase/mixed-case HTTPS failed and lowercase HTTPS retained host
  case/default port in the new mail test. Nine new cases cover mixed-case schemes/canonical links,
  canonical browser-origin matching and SMTP rejection of IP/trailing-dot origins already denied by
  R2. Mail tests use a synthetic sender; no real email or SMTP credentials.
- Validation: focused strict dependency-verification mail/browser/configuration tests, all 48 native
  R2 and 46 R3 integration cases, Spotless, Checkstyle and PMD passed. Final `pnpm validate` passed
  with 282 Java unit tests, 184 integration tests, 369 web unit tests, 11 standard and six topology
  Chromium tests, contract/documentation/architecture checks, builds and repository gates. The three
  before-fix regression failures are not counted as passes. Existing Checkstyle and Angular bundle
  warnings remain; changed sources add no Checkstyle warnings and PMD reports zero violations.
  No dependency, schema or HTTP contract changed. Final documentation checks passed. Authenticated
  production admission, retired commands, Q1/Q2, response timing and R4-R6/S10 readiness are unchanged.

### R4 — Invitation, reset and password change

- [x] Adapt the controlled host command to create/resend email invitations for the intended scope.
- [x] Implement transactional invitation activation, password reset and authenticated password change.
- [x] Keep incomplete accounts inaccessible, prevent scope reassignment and revoke sessions on changes.
- [x] Test repeat/concurrent completion, disabled accounts, failed transactions and lost responses.

#### R4 implementation, review and evidence — 2026-10-17

- Scope: approved analysis against clean `d260d06`, including R3 `a4da3b7` and its origin fix.
  Implements only R4. Normal authenticated HTTP startup remains closed by the existing admission
  bean, with no configurable bypass. New host commands run independently; retired ticket/recovery
  commands remain rejected. R5 UI replacement, full R6 handoff, S10 and production readiness stay open.
- Native reuse: Spring MVC controllers and existing native CSRF/security chain; Jackson duplicate,
  depth and trailing-input enforcement with bounded closed action parsing; existing PasswordPolicy,
  local blocklist, DelegatingPasswordEncoder/Argon2 and two-job crypto limiter. JdbcClient and explicit
  TransactionTemplate boundaries retain account-before-session locking. Boot's indexed JDBC session
  repository and existing REQUIRED transaction customization perform revocation; no second session
  authority, custom authentication publication, token login or password-login controller is added.
- Account glue: non-authoritative snapshots supply canonical email context and credential/version
  comparison before hashing. Completion rejects enclosing transactions, prepares outside the mutation
  transaction, locks/rechecks the account and current token, then consumes and mutates atomically.
  Acceptance verifies/activates only the bound pending account. Reset/change replace the verifier,
  invalidate action tokens, advance security version and delete every indexed account session in that
  same transaction. Current-password change also rechecks native authority and the credential snapshot.
  No workflow creates an authenticated session. Lost-success retry is a fixed conflict.
- Host glue: closed three-command parser and bounded ASCII stdin, followed by an explicit non-web Boot
  JDBC/Flyway/mail graph outside the web scan. Invite locks the installation/designated sole scope,
  rejects committed no-auth/populated/owned state and atomically commits mode, pending account and
  invitation token. Resend/cancel lock and resolve only canonical-email pending targets; neither can
  change stored delivery case, scope or original pending deadline. Cancellation removes only the
  unactivated reservation and cascading tokens, preserving scope/content/mode. Fixed exits distinguish
  committed issuance followed by failed delivery. No UUID discovery, raw ticket file or listener.
- Delivery/admission: reuse R3's prelookup reservation and post-commit SMTP boundary; its selector may
  now return no eligible reset account. Request/source/global limits precede lookup; existing email
  and installation mail budgets and send permits are reserved before selection. Unknown/ineligible,
  per-email denial and recipient SMTP failure share 202. Capacity/storage failures retain fixed
  account-independent errors. No database transaction spans SMTP, no queue/retry or new quota store.
- Deletions: retire ticket issuer/file handling/persistence/lifetime, the ticket table and their
  dedicated tests. Remove the redundant session-removal passthrough; real mutation services use the
  existing native component directly. Retain WebAuthn dependencies, remaining obsolete tables and
  browser workflows for coherent R5 removal. R2's retired-route assertion is adjusted only for newly
  implemented R4 routes; it remains R2 regression evidence.
- Fresh evidence: `R4AccountActionsIntegrationTest` has 59 new servlet/account cases with real native
  login, hashing, CSRF and Boot JDBC sessions. Per-test delegates use the real request/mail limiters;
  R3 retains aggregate-capacity/cleanup evidence. Cases exercise closed JSON/media/UTF-8/byte/scalar
  bounds, fixed errors/no-store, eligibility and account/scope substitution, exact expiry, replacement,
  replay/lost-success, two concurrent completers, invalid-password nonconsumption, activation rollback,
  reset/change rollback including native deletion, changed preparation snapshots and delayed native
  login/session saves after reset. GET/link opening is inert and completions never log in.
- Host evidence: fourteen fresh separate-JVM cases use disposable PostgreSQL and loopback STARTTLS with a generated
  synthetic certificate. They exercise canonical stored-recipient targeting, concurrent sole-owner
  provisioning, cancellation, unknown/active/disabled rejection, issuance rollback including initial
  mode, failed first send followed by resend, populated/undesignated/no-auth denial and non-web context
  enforcement. Cancellation followed by an explicit corrected invitation preserves the empty scope
  while allocating a new account, even with anonymous native sessions in committed authenticated mode.
  Unconfigured-installation session residue still denies provisioning. A configuration-failure case
  additionally checks redaction under verbose logger settings.
  Sixteen fresh unit cases cover the closed parser and bounded single-line stdin before Boot startup.
- Public timing: one focused complete HTTPS request/response sample measured accepted SMTP at 33,626
  microseconds, refusal at 19,099, unknown email at 11,953 and per-email quota denial at 10,553. Each
  returned identical 202 `{accepted: true}` with no-store. These are individual local fixture samples,
  with fresh controlled mail counters per branch; they include HTTP/TLS/JDBC/SMTP work, unlike R3's
  internal samples. They are neither stable bounds, statistical evidence nor timing equalization.
- Validation: final `pnpm validate` passed on the corrected code: eight documentation tests and
  link/ADR/index checks; 21 contract tests plus exact generation/format/schema/OpenAPI checks; ten web
  architecture tests, frontend format/lint/type checks, 369 web unit tests, 11 standard Chromium tests,
  development/production builds, wrapper/toolchain checks, backend build/Spotless/Checkstyle/PMD,
  276 Java unit tests, 237 PostgreSQL integration tests, seven topology-runner tests and six real
  topology Chromium tests, plus whitespace/tracked-ignore/text/EOL checks. Fresh R4 evidence is the
  59 HTTP/account cases, 14 host cases and 16 parser/input unit cases above. Retained historical tests
  are regressions, not replacement-design evidence. Separate static checks passed; changed Java files
  have no Checkstyle warnings and PMD reports zero violations. Existing Checkstyle debt and Angular's
  initial-bundle budget warning remain. Both application JARs contain the six new R4 classes and
  exclude all five retired classes and R2/R3/R4/topology fixtures. Final documentation checks pass.
- Validation repairs: initial compile/static checks and synthetic expiry/session fixtures needed fixes.
  The R2 retired-route list was corrected for the new R4 endpoints; a mock clock unavailable during
  startup cleanup and timing-loop re-stubbing also caused failed runs. Those failures are not counted
  as passes. A native Boot initializer now rejects web-context/mode overrides before context refresh,
  discovered during isolation review. A later review corrected an overbroad session-emptiness check:
  anonymous CSRF rows in committed authenticated mode cannot reserve the unowned empty scope after
  cancellation. Fresh unconfigured claims retain their stricter check. Final evidence follows the
  corrected fixtures and code.
- Size against `d260d06`, using R1 physical-line/exclusion rules: production Java +732/-562
  (118 files / 7,476 lines, net +170); SQL +0/-19 (four / 319); TypeScript +0/-0 (71 / 6,173).
  Against R1 `ce5c59b`, Java +2,196/-5,559 (net -3,363), TypeScript +1/-1 and SQL +31/-31.
  Tests are separate: +1,596/-604; no test deletion or movement contributes to production reduction.
  Documentation is separate: nine files, +224/-39 lines. HTML/SCSS, generated contracts, dependencies,
  locks, verification metadata and deployment configuration are unchanged.
- Review: token/account substitution, lock ordering, eligibility changes during password work,
  single-use/replacement/expiry, rollback and stale-save fencing reviewed. Application code supplies
  the documented account/action semantics and wire bounds; Spring retains authentication/session,
  transaction and SMTP machinery. Repository language reviewed under ADR-0018. Synthetic PostgreSQL,
  `.test` addresses and loopback SMTP/TLS material only; no real email, SMTP credentials, push or deploy.
- Constraints: Q1/Q2 remain open for R6/end of S9-R. Provisional unknown-address charging can exhaust
  delivery capacity without a send. Fresh isolated command processes reset their local counters;
  immediate cross-process resend success is evidence of Q2, not complete shared quota enforcement.
  Complete public HTTPS samples are distinct from R3 internal JDBC/SMTP samples. Neither generic
  acknowledgements nor fixture measurements establish timing resistance; final response handling and
  evidence remain R6 work. No S9-R completion or S10 handoff is authorized.

#### R4 response negotiation review fix — 2026-10-17

- Review of `bc097aa` reproduced a late MVC negotiation failure: an incompatible or malformed
  `Accept` could turn a completed action into an empty 500 through the generic exception advice.
  The fixed authentication error advice could also fail to serialize its response.
- Authentication MVC action acknowledgements, CSRF/session reads and shared fixed errors now set
  `Content-Type: application/json` explicitly, matching the existing native filter handlers. No
  account/action transaction, native login/session/CSRF behavior or production admission gate changes.
- Four fresh parameterized PostgreSQL/servlet cases cover HTML-only and malformed `Accept` headers:
  the complete invitation/reset/password-change sequence, single-use replay conflicts, CSRF/session
  reads, invalid input and throttling. Existing rollback cases now exercise storage failures with
  HTML-only `Accept`; all R4 success/error helpers also require the JSON content type.
- Validation: the focused R4 suite passed all 63 cases. Final `pnpm validate` passed with 276 Java
  unit tests, 241 PostgreSQL integration tests, 369 web unit tests, 11 standard and six topology
  Chromium cases, plus all documented contract/documentation/architecture/static/build/repository
  checks. A first full run passed the tests but stopped on four PMD duplicate-literal findings in
  the extended fixture; constants corrected those findings before the successful final run.
  Existing Checkstyle and bundle warnings remain; changed production files have no new warnings.
- Fixtures remain synthetic and local. Q1/Q2, timing resistance, R5/R6 and S10 remain unresolved;
  this fix does not expand R4 scope.

### R5 — Angular replacement and deletion

- [ ] Keep login, invitation acceptance, forgot-password, reset and password-change screens only.
- [ ] Use Spring's login encoding and CSRF lifecycle; fetch minimal session status for the shell.
- [ ] Preserve offline reads/writes, outbox, binding provenance and account-matched sync resumption.
- [ ] Remove WebAuthn/MFA dependencies, credential/challenge tables, saved recovery codes, ceremony
  models, operator-ticket files/cleanup, obsolete API models and frontend flows. Retain the host CLI
  entry point, password policy, scope authorization, abuse limits and minimized audit capability.
- [ ] Edit the fresh schema directly; keep the R1 target contracts aligned through TypeSpec regeneration, never manual generated edits.
- [ ] Verify built artifacts contain no retired authentication implementation or runtime dependency.

### R6 — Complete review, validation and S-track handoff

- [ ] Run the checks in [Development](README.md): full backend/frontend/static/build suites,
  contract generation checks, documentation checks, browser tests and real offline topology.
- [ ] Review authorization, token races, transaction failures, revocation, mailbox trust, logging,
  repository language and SMTP deployment behavior.
- [ ] Report additions/deletions separately for production Java, frontend, SQL, tests and documentation.
  No target is met merely by moving code or deleting tests. Review each custom component against the
  Spring mapping; justify any custom machinery replacing framework functionality.
- [ ] Resolve and close [Q1/Q2](#open-questions-required-before-s9-r-completion) with reviewed decisions
  and actual synthetic delivery/host-process evidence before S9-R completion or S10 handoff.
- [ ] Mark S9-R implemented only with evidence. Reconcile S10-S15 dependencies and obsolete policy
  references. Keep multi-user, device, transition and production-readiness gates open.

## Open questions required before S9-R completion

| ID | Status and owner | Question and known failure | Required closure at R6 |
| --- | --- | --- | --- |
| Q1 | Open; S9-R R6, with R3/R4 delivery evidence | How should public reset admission and delivery accounting prevent unknown-address requests from exhausting mail capacity for legitimate resets and operator invitations while preserving generic public responses? The current rule charges every valid address to the shared 30/hour budget without refunds: one anonymous source can exhaust it with 30 distinct unknown addresses in 87 seconds under the source/minute limits, even though no email is sent. | Select and document the final quota/accounting policy; reconcile affected contracts, deployment/privacy/threat records and add synthetic known/unknown flood tests demonstrating legitimate reset and operator invitation availability without account enumeration. |
| Q2 | Open; S9-R R6, with R3/R4 host-process evidence | How are email/global quotas enforced across the running HTTP service and separate isolated operator command processes? The current in-process counters and fresh per-process HMAC key restart on each invocation; the promised shared cooldown/hour/day quotas cannot be enforced by that storage alone. | Select and document the final accounting/enforcement boundary and any persistence/privacy lifecycle; test repeated and concurrent host invocations interleaved with HTTP issuance. Counters must obey the selected policy, with no raw address/token logging. Do not treat a normal CLI invocation as merely the S15 server-restart assumption. |

Neither question is resolved by R1's passing structural contract/documentation tests. Retain the
current numeric/accounting candidate visibly as provisional in the affected specification until the
reviewed resolution replaces it. R6 must record the accepted decisions, implementation and actual
synthetic validation evidence, close both rows and reconcile the S-track before marking S9-R complete
or allowing S10 to consume it.

## S-track impact and obsolete information

| Area | Treatment |
| --- | --- |
| S1-S7 scope/offline foundation | Retain implementation and evidence; update only authentication-specific handoffs |
| S8 policy | Historical validated design; mandatory password+passkey, no-email recovery and factor freshness are superseded for the new baseline |
| S9 runtime | R2 replaces the backend login/session boundary and closes authenticated admission; R3 supplies internal token/mail primitives; R4 implements account actions/host invitations; R5-R6 remain incomplete, and historical tests are not replacement evidence |
| Prior delegation plan | Historical implementation log; preserving all old flows/contracts is no longer a requirement |
| S10 provisioning | Consume controlled email invitations after S9-R; preserve distinct accounts/scopes and transactional ownership |
| S11 devices | Remains future work; do not require passkeys or old operator/recovery ceremonies as prerequisites |
| S12 transitions/deletion | Preserve explicit ownership and offline fencing; replace references to two-factor freshness with separately specified password reauthentication policy |
| S13-S14 synchronization | Unchanged scope/reconciliation invariants; authenticated paths consume the replacement S9 boundary |
| S15 release evidence | Reassess password-only/mailbox recovery strength and SMTP privacy/operations; no inherited MFA or ASVS Level 2 compliance claim |

## References

- [Spring form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html)
- [Spring session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Boot email](https://docs.spring.io/spring-boot/reference/io/email.html)
