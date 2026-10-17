# Spring email and password authentication replacement plan

- Status: Approved product direction; implementation planned, not started.
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
No application code, dependency, schema, generated contract or deployment configuration changes are
part of recording this plan. Implement against fresh databases; no compatibility migrations or old-API
wrappers. Do not advance S10 or claim production readiness as part of S9-R.

## Initial product boundary

- Login requires a verified email address, password and active account.
- Account states are pending verification, active and disabled. Account UUID and scope UUID remain
  distinct, stable identifiers; email is never a synchronization owner or partition key.
- Enrollment is controlled by an operator invitation. No public registration or first-visitor claim.
- Forgotten-password recovery uses a short-lived single-use email token and grants only password reset.
- Password reset and authenticated password change revoke existing sessions and require normal login.
- Email addresses cannot be changed through the initial UI/API. Define operator handling of erroneous
  pending invitations without silently transferring an active account to another mailbox.
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
responses. The endpoint names below are responsibilities; settle paths in phase R1.

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
secret, expiry and atomic consumption. Select and document numeric token lifetimes, request bounds,
resend throttles and email canonicalization/uniqueness rules in R1; these are not implicit framework
settings. Do not silently apply provider-specific alias or address rewriting.

Opening a link must not consume the token or mutate account state. Completion requires an explicit
POST, protecting against mail scanners and previews. Build links from a trusted configured origin,
never request Host headers. Prevent token leakage through request logs, referrers, analytics or browser
persistence. Prefer a URL fragment read into transient page memory and cleared before submission.

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

- [ ] Finalize lifecycle bounds, endpoint contracts, email policy and the simplified session policy.
- [ ] Reconcile ADR-0033, the authentication specification, API handoff, deployment instructions,
  threat model, ASVS mapping and privacy inventory with ADR-0034. Mark obsolete assertions explicitly.
- [ ] Inventory deletions and establish production Java/TypeScript/schema line-count baselines.
- [ ] Review mailbox recovery, pending-account authorization and SMTP failure behavior.
- [ ] Validate documentation and contracts; do not claim the target runtime exists yet.

### R2 — Native password login and session lifecycle

- [ ] Configure Spring form login, provider, context persistence, CSRF, logout and API handlers.
- [ ] Adapt account identity and current-authority checks to verified email/password authentication.
- [ ] Remove factor/partial-session publication and custom normal-login orchestration as replaced.
- [ ] Test unknown/disabled/pending accounts, wrong passwords, fixation, CSRF, expiry and account
  isolation. Check stale security versions cannot authorize protected transactions.

### R3 — Shared action tokens and Boot email

- [ ] Add the Boot mail starter, explicit SMTP timeouts and narrow delivery adapter; update dependency
  locks and verification metadata. Use a local synthetic mail test double, never real recipients.
- [ ] Implement minimal invitation/reset persistence with hashed secrets, purpose, expiry and atomic
  consumption; use Spring JDBC/transactions rather than an authentication/session engine.
- [ ] Test expiry boundaries, wrong purpose, replacement, concurrent consumption, failed delivery and
  rollback. Verify fixed origin, no secret logging and that GET never consumes tokens.

### R4 — Invitation, reset and password change

- [ ] Adapt the controlled host command to create/resend email invitations for the intended scope.
- [ ] Implement transactional invitation activation, password reset and authenticated password change.
- [ ] Keep incomplete accounts inaccessible, prevent scope reassignment and revoke sessions on changes.
- [ ] Test repeat/concurrent completion, disabled accounts, failed transactions and lost responses.

### R5 — Angular replacement and deletion

- [ ] Keep login, invitation acceptance, forgot-password, reset and password-change screens only.
- [ ] Use Spring's login encoding and CSRF lifecycle; fetch minimal session status for the shell.
- [ ] Preserve offline reads/writes, outbox, binding provenance and account-matched sync resumption.
- [ ] Remove WebAuthn/MFA dependencies, credential/challenge tables, saved recovery codes, ceremony
  models, operator-ticket files/cleanup, obsolete API models and frontend flows. Retain the host CLI
  entry point, password policy, scope authorization, abuse limits and minimized audit capability.
- [ ] Edit the fresh schema directly; regenerate contracts from TypeSpec, never manually.
- [ ] Verify built artifacts contain no retired authentication implementation or runtime dependency.

### R6 — Complete review, validation and S-track handoff

- [ ] Run the checks in [Development](README.md): full backend/frontend/static/build suites,
  contract generation checks, documentation checks, browser tests and real offline topology.
- [ ] Review authorization, token races, transaction failures, revocation, mailbox trust, logging,
  repository language and SMTP deployment behavior.
- [ ] Report additions/deletions separately for production Java, frontend, SQL, tests and documentation.
  No target is met merely by moving code or deleting tests. Review each custom component against the
  Spring mapping; justify any custom machinery replacing framework functionality.
- [ ] Mark S9-R implemented only with evidence. Reconcile S10-S15 dependencies and obsolete policy
  references. Keep multi-user, device, transition and production-readiness gates open.

## S-track impact and obsolete information

| Area | Treatment |
| --- | --- |
| S1-S7 scope/offline foundation | Retain implementation and evidence; update only authentication-specific handoffs |
| S8 policy | Historical validated design; mandatory password+passkey, no-email recovery and factor freshness are superseded for the new baseline |
| S9 runtime | Existing implementation remains until replaced; historical tests are not evidence for email/password behavior |
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
