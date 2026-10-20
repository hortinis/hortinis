# Built-in authentication and sessions

- Status: R1-R6 implemented and validated, 2026-10-19; production activation remains closed.
  Normal authenticated startup remains closed; activation requires separate explicit approval.
- Decision: [ADR-0034](decisions/0034-spring-email-password-authentication.md).
- Owner: [S9-R](../development/spring-email-authentication-plan.md), before S10.
- Related boundaries: [access and scope](access-and-synchronization-scope.md),
  [API handoff](../api/authentication-contracts.md), [threat model](../security/access-threat-model.md),
  [ASVS register](../security/asvs-5.0.0-applicability.md),
  [data lifecycle](../privacy/access-data-lifecycle.md).

This document specifies the complete replacement target. R2 implements the native backend login,
CSRF and session/logout boundary under a closed production admission gate. R3 supplies internal
JDBC action tokens, bounded cleanup and post-commit Boot SMTP. R4 implements the complete account-action
adapters and isolated invitation commands; R5 supplies the replacement browser flows. Normal authenticated HTTP
startup stays closed, without a configuration bypass. ADR-0033 and historical S8/S9 implementation logs preserve the
old behavior, not validation of the replacement. Required passkeys, factor ages, partial/recovery
sessions, recovery codes, ticket files and completed-session counting are superseded. R2 removes
session-dependent legacy workflows; R5 deletes the remaining dormant WebAuthn, ceremony and recovery storage artifacts.

## Authority and minimized account model

Login requires a verified email, a valid password and an active account. States are `pending`, `active`
and `disabled`. Store stable distinct account/scope UUIDs, installation identity, delivery email,
canonical email, status, security version, password verifier when active, creation/verification times
and invitation expiry metadata. Pending accounts have no password verifier, authenticated principal,
binding identity or data authority. Invitation acceptance sets the password and verification time and
activates the preassigned account. Email is never an ownership key or synchronization partition.

Each protected transaction checks current committed status, security version, installation/mode and
account-to-scope ownership before content lookup. Retain account-before-session lock ordering and
revocation fencing; positive authorization is never cached across requests. Disablement, reset, password
change and deletion invalidate access even if a concurrent servlet later saves a stale native context.
Operator capability is host-only provisioning, never an administrative browser login or reset bypass.

### Email syntax, canonicalization and uniqueness

Accept ASCII dot-atom local parts of 1-64 bytes and DNS domains with at least two labels, each 1-63
characters, containing letters/digits and interior hyphens. Total length is at most 254 bytes. Reject
whitespace (including surrounding whitespace), controls, consecutive/leading/trailing local dots,
quoted local parts, comments, IP literals, trailing domain dots and Unicode addresses/domains.
ASCII IDNA A-label domains are accepted as ASCII DNS labels; do not perform implicit IDNA conversion.
Internationalized address support needs a separate reviewed contract change.

Validate first, then lowercase the entire address using locale-independent ASCII rules (`Locale.ROOT`
in Java). The resulting canonical value has a database unique constraint across pending, active and
disabled accounts in the installation. Concurrent case variants have one winner. Preserve validated
submitted case separately for SMTP delivery; use canonical form for login, lookup and abuse keys.
This deliberately treats local parts as case-insensitive: a mail system distinguishing case cannot
create two Hortinis accounts. Do not strip `+tags`, dots or otherwise merge provider aliases. Those
aliases may identify separate accounts; email uniqueness is not proof of one human per account.

Email changes are absent from the API/UI. An operator may cancel an erroneous pending invitation,
invalidate its token and remove that unactivated reservation, then explicitly invite the intended
mailbox. Never retarget a pending account in place or transfer an active/disabled account's scope to
another address. S9-R permits one sole-scope pending reservation; S10 owns additional accounts/scopes.
Cancel/expiry never deletes or assigns garden data. Automatically purge an unaccepted pending account
seven days after initial creation, regardless of resend, releasing its email reservation and unowned
sole-scope reservation. Operator cancellation and reissue are explicit new provisioning decisions.

## Numeric policy

These are application choices, not framework defaults or evidence of implementation. Trusted UTC
controls expiry: a token is valid only while `now < expiresAt`; equality is expired. Test all boundaries.

| Item | Accepted bound and behavior |
| --- | --- |
| Password | 15-128 Unicode scalar values, at most 1,024 UTF-8 bytes. Exact text, no trim/normalization/case change/truncation; allow paste/managers/spaces. No composition or periodic-change rule. Login accepts 1-128 scalars to fail generically for old/invalid candidates. |
| Password verifier | Retain Argon2id through `DelegatingPasswordEncoder` and the accepted Password4j encoder: 65,536 KiB, three iterations, parallelism one, independent salt at least 16 bytes and 32-byte output. At most two costly jobs concurrently; overload never lowers cost. |
| Password blocklist | Retain the pinned licensed local corpus, at least 3,000 policy-compatible common candidates plus breached-password coverage. Reject `hortinis` and email local-part context derivatives. No external password/prefix query. Validate at invitation acceptance, reset and change. |
| Invitation token | 32 independent CSPRNG bytes, canonical unpadded base64url (43 characters), SHA-256 verifier only. Valid 24 hours or earlier pending-account purge. One current invitation per pending account. |
| Reset token | Same secret profile, reset purpose only; valid ten minutes. One current reset per verified active account. This tightens the preliminary 30-minute proposal to ten minutes for conservative ASVS lifetime coverage. |
| Native session | Thirty minutes since the last native request, including sync, polling, status and CSRF. Anonymous CSRF sessions use the same timeout. No separate absolute expiry, factor-age rule, remember-me or concurrent-session cap. Active background requests can keep a session alive indefinitely; see the explicit session risk below. |
| Authentication attempts | Five per canonical-email key per rolling minute, 20 per trusted-source key per minute, 60 per HTTP process per minute (one active HTTP service initially). Token completion uses a token-digest key plus source/global limits; password change uses the current account key. Unknown/disabled/pending cases count equally. No permanent account lockout. |
| CSRF/status reads | 20 per trusted-source key per rolling minute and 60 per HTTP process per minute. No account-existence branch. Status never allocates a session. |
| Mail issuance/resend | Committed eligible token issuance costs one delivery reservation: canonical email cooldown 60 seconds, three/rolling hour and ten/rolling 24 hours across purposes. Resets have 20/hour and 80/day; invitations/resends ten/hour and 20/day; no borrowing. Combined ceiling 30/hour and 100/day. HTTP and isolated host processes share PostgreSQL accounting. SMTP failure, uncertain delivery and crash after commit remain charged. Ineligible/rolled-back issuance costs nothing. |
| Request limiter storage | Bounded in-process HMAC-SHA-256 keys with a random 32-byte process secret; no raw email/IP/token in keys or diagnostics. Maximum 4,096 keys, 15-minute idle expiry, 60-second cleanup; no active eviction. Restart resets request counters only. S15 retains single-service/request-flood assumptions; these counters do not enforce delivery quotas. |
| Delivery ledger | Installation-lifetime random 32-byte HMAC key and clock high-water mark; email HMAC/purpose/issuance timestamp reservations in restricted PostgreSQL. No account/token mapping or unknown-recipient trail. Lock the quota singleton before invitation/account/token locks. Reserve in the issuance transaction and roll back if ineligible. No transaction spans SMTP. Reservations count for exactly 24 hours, regardless of cancellation, consumption or process restart; bounded startup/pre-issuance/minute purge removes up to 500 expired rows. |
| Request bounds | 16,384 raw bytes for every authentication POST; login includes percent-encoded bytes. Closed JSON or exactly two form fields; reject duplicates, extra fields, query parameters, malformed UTF-8/escapes, lone surrogates and unsupported media types with fixed 400. JSON depth at most two. Bodyless logout has no query/body. No password hashing before syntax/size/abuse gates. |
| SMTP | Synchronous post-commit send, two concurrent sends per process, no waiting queue or automatic retries. Unknown requests acquire no transport permit. Capacity denial rolls back issuance and is public 202. Connection/read/write timeout each 5,000 ms; no infinite defaults. Neither concurrency nor the I/O bounds are an installation-wide send cap or whole-request deadline. Shared rolling delivery quotas independently bound committed issuance. |
| Cleanup | Immediate authorization expiry; consumed/replaced tokens removed in their mutation transaction. Bounded JDBC cleanup at startup/every 60 seconds, batches of at most 500, removes expired tokens and overdue pending accounts. Backlog may take several ticks; never authorizes expired state. Native Spring Session idle deletion runs every minute. |
| Security events | Retain minimized fixed event/outcome/reason, UTC and fresh request reference for 30 days; daily and overdue-startup purge. No subject/recipient/session mapping. |

R6 replaces the provisional no-send charging and process-local mail windows with the approved
[shared delivery policy](../development/spring-email-authentication-r6-evidence.md).
Unknown-address requests cannot spend delivery reservations; the invitation allocation is unavailable
to resets. Normal request/source/global request admission remains account-independent. Request-window
exhaustion returns fixed 429 with `Retry-After: 60`; this is minimum guidance, not an hourly reset promise.
All recipient-related outcomes, including email/installation delivery quota and transport-capacity
denial, acknowledge the same 202. Host commands retain fixed throttling/failure output.

A reservation and token/provisioning mutation commit together. Rejection, transaction failure or no
eligible account rolls back the reservation and replacement; committed failure/uncertain delivery does
not refund it. Cancel/reinvite cannot erase email history. Quota locking is bounded to one second;
shared pre-eligibility storage/lock failure returns fixed 503 without an in-memory fallback.
Recipient-specific token-store/commit failures receive generic 202 without delivery/retry; a failed
transaction rolls back reservation/token changes, while an uncertain commit may retain them unsent. An admission timestamp behind the last committed admission observation returns account-independent
503 and denies issuance and prevents cleanup from removing restrictions. Independent application clocks must be
synchronized; a forward clock jump can age windows early and remains an operator clock-trust risk.
Ordinary restart preserves key and reservations; restored older backups can omit recent sends and
must remain inaccessible through the existing restore gate, including waiting out the 24-hour mail
window. Runtime mail privileges are quota SELECT/INSERT and UPDATE of `last_observed_at` only, plus
reservation SELECT/INSERT/DELETE; no HMAC-key UPDATE, quota DELETE or schema authority is required. Privileged database tampering is outside application quota enforcement.

Public reset acknowledgements normally have a one-second minimum from controller entry, using
Spring MVC asynchronous completion and the Spring scheduler after synchronous account/mail work.
Once that work completes, padding failure cannot change its generic 202 acknowledgement: native
MVC async timeout uses the accepted response after two seconds, and scheduler rejection returns it
immediately. Completion cancels any queued padding callback; neither fallback repeats issuance or
SMTP. There is no mail queue or retry. Normal extra padding is at most one second; scheduler contention
can defer completion to the async timeout, and servlet scheduling/CPU delay can add latency. SMTP work
can take longer. Fast branch padding does not conceal slow SMTP, contention, source throttling or
network tails; rejection can skip the timing floor. No timing-resistance or ASVS ENUMERATION pass
follows; measured residual risk remains part of activation/S15 review.

## Spring ownership and session policy

Use Boot-managed versions and the component mapping in the replacement plan. `HttpSecurity.formLogin`
with `usernameParameter("email")` and the native `UsernamePasswordAuthenticationFilter` handles login;
`DaoAuthenticationProvider` and a small `UserDetailsService` supply current account credentials/status.
No password login controller, custom authentication filter, factor accumulator or publication engine.
Use native success/failure handlers, entry point and access-denied handler for fixed JSON responses;
disable HTML login redirects, saved-request replay, request caching and remember-me for the API.
Treat unknown, pending and disabled accounts through equivalent-cost provider failure, using the
provider unknown-user/dummy-verifier path rather than a fast disabled-account precheck. A small
non-authenticating syntax/size/parameter guard must validate the decoded email before the native
filter trims its username; reject surrounding whitespace and duplicate/query parameters there.
It delegates authentication unchanged to the native filter, never publishing its own context. The
[7.1.1 filter source](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/authentication/UsernamePasswordAuthenticationFilter.java)
was checked for this trimming behavior; R2 must test the real servlet pipeline.

`HttpSessionSecurityContextRepository`, the native session authentication strategy and Boot-configured
`JdbcIndexedSessionRepository` own context persistence, fixation defense, cookie transport, indexing
by stable account UUID and idle expiry. Retain credential-free allowlisted JSON session serialization;
never persist email, credentials or tokens in principals or enable Java object deserialization.
The security version accompanies native account authority; it is checked against current storage.
A concurrent reset may make a newly saved login context stale; protected operations still deny it.
Credential mutation must participate in the same database transaction as indexed session deletion;
retain the supported REQUIRED transaction customization rather than a second session table.
R2/R4 must demonstrate rollback and stale-save fencing through the real repository.

Use `__Host-hortinis-session`: Secure, HttpOnly, SameSite=Strict, Path=/, no Domain or persistent
Max-Age, independent 32-byte random secret. Disable URL rewriting. Only HTTPS at one configured origin
is supported for authenticated use. Do not trust arbitrary Host/forwarded headers or proxy identity.
No-auth uses its explicit isolated stateless chain; missing SMTP or credentials never changes mode.
Authentication routes are unavailable with fixed 503 in no-auth mode and allocate no auth session;
public capabilities remain available. Local-only startup contacts neither auth nor mail endpoints.

The simpler session policy intentionally removes the twelve-hour factor deadline and five-session
counter. A stolen live session can persist while requests continue, and many sessions can consume
storage. Password change/reset/disablement revoke all sessions; normal logout revokes the current one.
Rate limits, cookie/browser protection and current-authority checks mitigate but do not remove this
risk. ASVS session lifetime review (`7.1.1`, `7.3.1`, `7.3.2`) and production sizing remain open S15
evidence. Do not call this an ASVS/AAL compliance pass. S11/S12 must separately specify device and
sensitive-transition reauthentication before implementing those features; old MFA freshness is obsolete.

## CSRF and browser lifecycle

Use Spring's session-backed CSRF repository and BREACH-aware request handling. Expose the framework
request token through a minimal no-store GET `/api/v1/auth/csrf`, sending it only in `X-CSRF-TOKEN`.
Retain the small header-only handler as needed to reject duplicates/parameter fallback while delegating
masking/validation to Spring. Angular uses the explicit same-origin header adapter; do not assume its
cookie interceptor reads an HttpOnly session cookie. No second script-readable credential cookie is
needed. Login, invitation/reset completion, reset request, password change and logout all require CSRF,
including anonymous requests. Native CSRF rejection may allocate an anonymous session, never authority.

Fetch CSRF on page load before unsafe requests and again after login/logout, credential changes,
session loss or a 403. Do not blindly repeat a mutation after refresh. Status distinguishes only
anonymous/current authenticated account, never pending setup, email, scope, credential registration,
logout target or absolute expiry. A stale/disabled/unverified context is anonymous; storage outage
returns 503 instead of claiming absence. Existing session reads refresh native inactivity.
All API responses are no-store and excluded from service-worker caches; no credentials, action tokens
or CSRF in IndexedDB/localStorage/sessionStorage, URLs sent to the server or diagnostics.

## Invitation, reset and password change

Host-only invitation provisioning records the intended mailbox and exact preassigned empty sole scope
in a transaction; concurrent sole-owner invitations cannot create two owners. Fresh setup commits the
selected authenticated mode explicitly; a committed no-auth mode cannot be claimed or converted here.
Host invite/resend/cancel commands read the target email from protected standard input. Resend/cancel
resolve its unique canonical email only to an existing pending account; no UUID discovery is required.
They recheck current eligibility in the mutation transaction, preserve the stored delivery address and
scope, and fail without mutation for unknown/active/disabled targets. They never create or retarget
an account. No browser registration/issue/resend endpoint exists. A pending account remains
inaccessible even if its mailbox/password appears elsewhere. Resend never creates another account or changes its scope.

Use one JDBC action-token component with explicit invitation/reset purposes, account/installation
binding, verifier and trusted creation/expiry times. The route fixes purpose; request bodies cannot
supply account, scope, email override or purpose. Tokens are independent of the browser session and may
be completed on another browser after CSRF bootstrap. They are never passed to `oneTimeTokenLogin()`.
Reset issuance is allowed only for a verified active account, never a pending or disabled account.

Generate/replace and commit the token before calling SMTP; send only to the stored delivery address.
No database transaction spans network delivery. A synchronous send may fail or succeed with a lost
acknowledgement: retain the committed current token until expiry/replacement; do not restore an older
token. A resend invalidates the previous link even if the new email fails. Explain this operational
tradeoff; neither resend nor failed send changes credentials or activates an account.

Build fixed `/accept-invitation#token=...` and `/reset-password#token=...` links from the trusted
configured HTTPS application origin. No Host-derived URL, redirect/return URL, tracker, remote image
or third-party script. The page reads the fragment into transient memory and clears it with
`history.replaceState` before other work; clear secret memory on completion/navigation. Refresh then
requires reopening the email. GET and mail previews never consume tokens. Referrer-Policy is
`no-referrer`; disable link tracking and remove URL/body logging at every relevant layer. A mailbox,
mail provider, compromised browser/extension or link-rewriting mail scanner can still see the secret;
fragment handling reduces accidental HTTP leakage, not mailbox compromise.

Validate password syntax/blocklist and perform bounded hashing before the completion transaction.
Inside the transaction lock the account, recheck current status/security version, lock the matching
current purpose/token, enforce expiry, consume, activate or replace the verifier, invalidate other
applicable tokens and (reset/change) increment security version and delete all native account sessions.
Disablement invalidates both purposes. One concurrent completion wins; rollback restores the prior
verifier/status and usable token. A successful completion followed by a lost response is still consumed;
retries return generic 409 and the user tries normal login or requests a new reset. Validation failure
does not consume the token. Invalid/wrong-purpose/replaced/used/expired/ineligible cases share 409.

Authenticated password change requires both current account authority and current password; recheck
the snapshot after hashing before publication. A new password cannot select another account. Reset
and change revoke the caller too; neither completion returns an authenticated garden session. Other
browser sessions may stay anonymous until idle cleanup, but no account authority survives revocation.

Mailbox possession is the recovery authority and is weaker than the retired two-proof recovery.
Mailbox takeover/reassignment can permit account takeover; password-only login remains phishable.
Loss of password and mailbox has no self-service recovery, and an operator cannot retarget the active
email or choose a replacement password. Explain these limits before invitation acceptance. Additional
notifications, email change, MFA/passkeys, recovery codes and host-ticket recovery are deferred.

## Protected online and offline logout

Use Spring's bodyless CSRF-protected POST logout filter, context/session invalidation, CSRF cleanup
and a fixed JSON success handler. Native logout acts on the cookie's current session; no custom target
registry or target-fenced logout protocol remains. Anonymous logout with valid CSRF is successful;
missing/stale CSRF returns 403. Bootstrap/status then requires a deliberate new action.

Locally pause synchronization immediately and clear transient auth state on sign-out; preserve local
reads/writes and outbox. Offline sign-out cannot invalidate a remote session while disconnected. Do
not persist/replay a remote logout request against a later cookie or automatically fetch fresh CSRF
for an old logout. On reconnect, keep sync paused until the user explicitly resolves current status
and signs out again or signs in. Coordinate tabs and fence in-flight login/logout operations; native
delayed cookie responses can require login again. Fresh R5/R6 browser evidence covers these transitions under closed production admission. Authentication
expiry never discards or transfers pending work; only the original account binding may resume it.

## Delivery, privacy and evidence handoff

Use Boot Mail auto-configuration, `JavaMailSender` and `SimpleMailMessage` behind a narrow replaceable
adapter with two fixed plain-text templates. SMTP configuration/credentials are operator secrets.
Fail authenticated startup for absent/invalid required configuration, not for a transient delivery
outage; configured SMTP outage makes invitation/reset unavailable without disabling normal valid login.
Never downgrade access mode. Public reset always acknowledges eligible/unknown/pending/disabled and
recipient-specific SMTP failure identically; only account-independent infrastructure failure may be
503. Report fixed operational delivery outcome, without recipient, body, secret or provider exception.
Synchronous sending can distinguish send/no-send in slow tails. R6 adds bounded response padding and
fresh public HTTPS measurements, separately from internal SMTP/JDBC measurements. Generic JSON and
minimum response duration do not establish timing resistance; activation/S15 must review residual tails.

The [R3 evidence record](../development/spring-email-authentication-plan.md#r3-implementation-and-review--2026-10-17)
distinguishes its internal primitive/transport checks from full workflow evidence. The
[R4 record](../development/spring-email-authentication-plan.md#r4-implementation-review-and-evidence--2026-10-17)
adds account/host/HTTP evidence and complete synthetic HTTPS timing samples. Q1/Q2 were open at
that increment; the [R6 record](../development/spring-email-authentication-r6-evidence.md) supplies
shared-quota closure and complete replacement validation. Timing resistance remains unverified.

Required runtime evidence includes native filter login/CSRF/logout and fixation, status/authorization
for pending/disabled/stale versions, account/scope isolation, races/rollback, SMTP failure and timeouts,
fixed origin, no secret leakage, form parsing and browser offline/multi-tab behavior. Contract schemas
check shapes, not those behaviors. R6 supplies the complete replacement validation; S10-S15,
separate activation approval and applicable foundation release gates remain open. See the [R1 inventory](../development/spring-email-authentication-r1-inventory.md) for removal and
line-count baselines and the [operator handoff](../deployment/authentication-operator.md) for deployment.

Framework references checked on 2026-10-17 against the Boot 4.1 / Security 7.1 / Session 4.1 family:
[form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html),
[session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html),
[CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html),
[logout](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html),
[JDBC sessions](https://docs.spring.io/spring-session/reference/configuration/jdbc.html),
[Boot Mail](https://docs.spring.io/spring-boot/reference/io/email.html).
Numeric bounds, atomic action-token semantics and residual-risk choices above are Hortinis policy.

## R5 browser implementation boundary

The Angular entry point captures action fragments synchronously before importing/bootstraping the
application, then clears browser history with `replaceState`. Inert Angular action routes render the
shell forms. Secret values remain transient; completion/navigation clears them. The existing HTTP
adapter uses standard UTF-8 form encoding for native login, accepts reset's 202 acknowledgement and
refreshes Spring CSRF without retrying unsafe requests. Standalone startup remains local.

Origin-wide non-secret revisions, Web Locks and storage/BroadcastChannel notifications coordinate
cookie workflows. A pause immediately invalidates captured request revisions; Dexie advances exchange
fences and expires leases without deleting content/outbox/provenance. `logoutPending` means a local
pause requiring explicit resolution, never a queued server logout. A locally paused tab checks current
session status and chooses login or a fresh native sign-out; status alone never releases the pause.
A tab may keep work paused locally while another tab's login is in flight. Only explicit successful
login releases the pause, after which ordinary binding verifies exact account/instance/scope identity.
Login completion writes a separate acknowledgement of its exact pause revision; it cannot overwrite
a newer pause published by another tab. Unsafe account requests retain their revision across CSRF
bootstrap and check it before dispatch. Native logout also rechecks connectivity at dispatch, including
an intervening offline event. Retained no-auth bindings cannot acquire an account pause; without a
binding or observed native account service, public server capabilities must confirm authenticated mode
before publishing a pause. These checks preserve local data and never queue logout.
Native delayed cookie responses may still require another login; current server authority remains
checked inside protected transactions. The R5 record distinguishes browser evidence from internal
measurements. R6 closes Q1/Q2 with fresh shared-store/host/HTTP evidence; timing resistance and
production admission remain open.
