# Authentication HTTP contract handoff

- Owner: S9-R R1, [ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md).
- Status: R1-R6 contracts/adapters and replacement validation complete, 2026-10-19.
  Normal authenticated startup remains closed.
- Sources: `contracts/typespec/authentication-models.tsp` and `authentication-operations.tsp`.
- Policy: [authentication and sessions](../architecture/authentication-and-sessions.md).

The generated artifacts describe the complete target. R2 implements CSRF/status, native login and
logout; R4 implements invitation acceptance, reset request/completion and password change, with
normal authenticated production admission still closed. R3 supplies shared token/mail primitives;
R5 implements the replacement browser. The isolated R4 host commands issue/resend/cancel invitations. Retired login/session mutation routes are disconnected and
cannot authenticate. Historical S8/S9 tests are not evidence for these adapters. No compatibility
aliases are introduced. Synchronization/access contracts and mode boundaries remain unchanged.
Auth routes in no-auth mode return fixed 503 without allocating an auth session.

## Endpoint matrix

Every response, including errors, has `Cache-Control: no-store`. JSON bodies are closed. Every POST
requires the native session cookie (an anonymous CSRF session suffices on public operations) plus
`X-CSRF-TOKEN`. CSRF proof grants no account authority. There are no unsafe GETs.
Authentication success and error responses explicitly use `application/json`, including when `Accept`
is incompatible or malformed; response negotiation must not fail after a committed action.

| Method and path under `/api/v1/auth` | Input | Eligibility and successful response |
| --- | --- | --- |
| GET `/csrf` | None | Public; 200 `{token, headerName: "X-CSRF-TOKEN"}`; may allocate an anonymous session. |
| GET `/session` | None | Public; 200 `{state: "anonymous"}` or current verified active `{state: "authenticated", accountId}`. No new session. |
| POST `/login` | `application/x-www-form-urlencoded`: exactly `email`, `password` | Native Spring form login; 200 authenticated status. Configure `usernameParameter("email")`; no JSON login controller. |
| POST `/invitations/accept` | JSON `{token, newPassword}` | Current invitation for its pending account; 200 `{completed: true}`, then normal login. |
| POST `/password-reset/request` | JSON `{email}` | Public; identical 202 `{accepted: true}` for eligible, unknown, pending, disabled, email/installation delivery quota, transport-capacity denial or recipient-specific SMTP failure. |
| POST `/password-reset/complete` | JSON `{token, newPassword}` | Current reset for its verified active account; 200 `{completed: true}`, all sessions revoked, then normal login. |
| POST `/password` | JSON `{currentPassword, newPassword}` | Current verified active session and current password; 200 `{completed: true}`, all sessions revoked including caller. |
| POST `/logout` | No body/query | Native CSRF-protected logout of current cookie session; 200 `{completed: true}`, including anonymous state with valid CSRF. |

Capabilities remain public at `/api/v1/server/capabilities`. Authorized identity/bootstrap/data/sync
require current account/security-version and owned scope. Invitation issuance/resend/cancellation are
host-only commands, with no public registration or admin-browser endpoint. Deny unspecified routes.
Old setup, two-step password/passkey login, step-up, credentials, recovery codes, operator recovery,
activity, session list/revoke and target-body logout are absent from the replacement contract.

## Encoding and fixed errors

Email is an ASCII mailbox with bounded dot-atom local part/DNS labels and 254-byte total bound. Preserve
validated delivery case; canonicalize the whole address to lowercase for unique identity. The schema
checks syntax/length, not database uniqueness or canonicalization. No trim, Unicode conversion or
provider alias rewriting. See the specification for exact unsupported forms and pending corrections.

Login has exactly two UTF-8 form fields, encoding literal `+` as `%2B` and password special characters
through a standard form encoder. Validate decoded email before Spring's native username trimming;
the syntax/size guard never authenticates or replaces the native filter. Do not put credentials in query parameters; reject extra/repeated
fields, malformed UTF-8/percent encoding and unsupported content types. All POST bodies are limited
to 16,384 encoded bytes; invalid/oversize input is fixed 400. Other bodies are closed JSON with at most
two nesting levels. Password schemas count Unicode scalars; adapters also reject lone surrogates and
check 1,024 UTF-8 bytes. Invitation/reset tokens are canonical 32-byte base64url secrets, route-bound
to purpose and account. Neither caller-selected account/scope nor email/purpose override is accepted.

| Status | Fixed body | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`, `The request is invalid.` | Syntax, body/media/encoding/bounds or password-policy rejection; no token consumption. |
| 401 | `AUTHENTICATION_FAILED`, `Authentication failed.` | Login unknown/pending/disabled/wrong password, or invalid current password on change. |
| 401 | `AUTHENTICATION_REQUIRED`, `Authentication is required.` | Password change without current authenticated authority. |
| 403 | `ACCESS_DENIED`, `Access is denied.` | CSRF/origin/authorization rejection. |
| 409 | `AUTHENTICATION_PRECONDITION_FAILED`, `The authentication precondition no longer holds.` | Uniform expired/used/replaced/wrong-purpose/ineligible action token or changed credential snapshot. |
| 429 | `AUTHENTICATION_THROTTLED`, `Authentication attempts are temporarily limited.` | Request/source/global request limits, independent of account existence; `Retry-After: 60`. All reset delivery-quota/capacity denials still acknowledge 202. |
| 503 | `ACCESS_UNAVAILABLE`, `The access service is unavailable.` | Account-independent access infrastructure failure. Recipient-specific SMTP failure is never exposed here. |

Each operation declares only applicable errors. No HTML, redirects, input reflection, token/email,
provider exception or delivery status is returned. Success/error schemas cannot certify uniform timing;
synchronous SMTP branches require explicit R3/R6 timing evidence before release.

## Native framework handoff

`UsernamePasswordAuthenticationFilter`, `DaoAuthenticationProvider` and the native session
strategy establish the login context, with small JSON success/failure handlers. `UserDetailsService`
maps canonical email to stable account identity and current eligibility; scope checks stay in protected
transactions. `HttpSessionSecurityContextRepository` and Boot's JDBC Spring Session remain authoritative.
The cookie is `__Host-hortinis-session`, Secure/HttpOnly/Strict, Path=/, no Domain/persistent Max-Age.
Native thirty-minute request inactivity applies, with no separate absolute expiry/concurrency cap.
No cookie secret, email, factor state, absolute deadline or logout target appears in JSON status.

Use `HttpSessionCsrfTokenRepository` and framework BREACH handling; expose the request token through
bootstrap and pass it through the explicit Angular same-origin header adapter. Refresh after login,
logout, credential mutations and session loss. All unsafe pre-auth requests remain protected. Do not
persist CSRF or automatically replay mutations after 403. Native `LogoutFilter` and handlers invalidate
context/session/CSRF, then return fixed JSON. No target field or custom logout publication engine.

## Token and offline behavior

Invitation lifetime is 24 hours; reset lifetime is ten minutes. GET only opens the static page. Fixed
trusted-origin fragment links are cleared before other page work and submitted by explicit POST.
Atomic completion rechecks eligibility/current token/expiry, consumes, updates credentials/status and
invalidates applicable tokens. Reset/change increment security version and revoke all native sessions
in the mutation transaction. Rollback preserves the token and old credentials. Lost-success response
retries receive 409; normal login or a fresh reset resolves the outcome. Neither completion logs in.

Expiry, logout or denied current authority pauses synchronization while preserving local reads/writes
and outbox. Only account-matched reauthentication resumes the retained binding. Offline sign-out pauses
locally; native logout is not saved for automatic replay against a newer cookie. On reconnect the user
explicitly resolves status and chooses login or a new sign-out. Fence tabs and in-flight requests. R5 supplies fresh browser tests for delayed responses and this changed logout policy rather than inheriting old target tests.

## R1 evidence boundary

Contract tests cover exact paths/methods, form media type and required fields, closed variants,
anonymous minimization, email/token/password bounds, absence of retired models/routes, fixed errors,
no-store and joint CSRF/cookie declarations. They do not exercise native filters, hashing, SMTP,
canonical uniqueness, token transactions, timing, session revocation or offline behavior. Those remain
R2-R6 work. The [R1 inventory](../development/spring-email-authentication-r1-inventory.md) and replacement
plan record actual checks and the outstanding S-track gates.

## R4 adapter evidence boundary

Fresh R4 servlet/HTTPS and PostgreSQL fixtures exercise closed JSON/UTF-8/byte bounds, native CSRF,
no-store fixed errors, eligibility/ownership, replacement/expiry/replay, concurrent completion,
rollback and native session revocation. Password preparation runs outside the completion transaction;
locked account/token/current-authority checks precede mutations. Acceptance/reset/change never
establish a SecurityContext or an authenticated session. Real loopback SMTP is synthetic only.

Generic reset bodies do not prove timing resistance. Complete synthetic HTTPS response samples are
recorded separately from R3 internal JDBC/transport samples in the
[R4 evidence](../development/spring-email-authentication-plan.md#r4-implementation-review-and-evidence--2026-10-17).
At R4 completion Q1/Q2 and browser/final validation remained open; the R6 record supersedes that
quota/evidence boundary. Timing resistance and production admission remain open. No contract or generated
artifact was changed to accommodate these adapters.

## R6 delivery admission revision

TypeSpec remains authoritative. Only committed eligible issuance spends durable shared email/global
mail reservations. Unknown/ineligible requests do not spend mail capacity. Resets have 20/hour and
80/day; host invitations/resends ten/hour and 20/day, without borrowing; email cooldown/three-hourly/
ten-daily limits span purposes. Public delivery quota/capacity/failure branches always acknowledge
202, including recipient-specific token-store/commit failure with no delivery/retry. Shared
pre-eligibility storage/clock failures remain 503 for known and unknown addresses; only request limits
produce 429. No quota count or delivery status is exposed.
A one-second acknowledgement floor adds bounded padding; slow SMTP/HTTP tails remain observable.
See [fresh R6 evidence](../development/spring-email-authentication-r6-evidence.md); generic output and
local timing distributions do not establish timing resistance or production readiness.
