# Built-in authentication and sessions

- Status: Accepted S8 design, 2026-10-11; runtime implementation and verification pending.
- Decision: [ADR-0033](decisions/0033-built-in-authentication-and-sessions.md).
- Related boundaries: [access and scope](access-and-synchronization-scope.md),
  [threat model](../security/access-threat-model.md),
  [ASVS register](../security/asvs-5.0.0-applicability.md),
  [data lifecycle](../privacy/access-data-lifecycle.md).

## Authority and minimized account model

The authentication adapter owns credentials, ceremonies, sessions and framework integration. A trusted
entry point returns only immutable account/device authority and factor freshness to the access
resolver. Synchronization workflows retain their existing explicit context and scope partitioning.
A session principal is not an ownership relationship; each protected request checks current committed
account status, authority epoch, device status where implemented, access mode and account-to-scope
ownership. Positive authorization is not cached across requests or revocation.

Store a generated account ID, canonical local login identifier, account status, owned-scope relationship,
authority epoch, selected verifier and necessary lifecycle times. Accept ASCII login names of 3-64
characters after lower-case canonicalization: a letter/digit first, then letters, digits, periods,
underscores or hyphens. Do not collect a real
name, email, telephone, birth date, precise location or fingerprint. Login names are private account
attributes, never authority or ordinary diagnostic fields. Account and scope IDs are distinct and
never reused. Operator powers remain separate from gardener access; there is no default admin account.

WebAuthn uses a random opaque user handle distinct from the login name, and protected credential ID,
public key, verified algorithm, sign counter/backup flags and optional user label of at most 64 Unicode
scalar values excluding NUL. Register at most eight credentials per account. Use `attestation=none`; discard unnecessary
attestation certificates, authenticator model/AAGUID and transport metadata after validation unless
an independently reviewed purpose requires them. Never collect biometric samples, PINs or private
keys. Use the established verifier for signatures, RP hash, exact origin, challenge, presence and
verification. Require verification in options and validate its flag in the response. A synced credential
with a zero/non-increasing counter needs the library's supported backup-aware policy; do not invent
universal counter rules or accept an invalid signature as recovery. Algorithm acceptance is a closed
set supported by the pinned verifier, tested before activation; unsupported algorithms deny.

The S9 adapter profile selects ES256 with P-256, RS256 with RSA keys of 2,048–4,096 bits, and
EdDSA with Ed25519. Rebuild the verified public COSE key without optional key ID, operations or IV
metadata; reject private material. Use the pinned library's counter-handler extension only for an
already verified backup-eligible credential: preserve the highest observed counter while accepting
verified resets/non-increasing counters from synced authenticators. Keep the library's strict counter
rule for non-backup-eligible credentials (including its zero/zero allowance). The library must first
verify signature, UP/UV and unchanged backup eligibility. One-use server challenges remain mandatory
for every proof, irrespective of counter behavior; adapter-only tests do not establish that lifecycle.

## Numeric policy

These are accepted application policy, not activated defaults or universal regulatory requirements.
Adapters must implement them before their mode/feature is available. A weaker change requires a new
reviewed decision; clock tests cover each equality/expiry boundary. All times use trusted UTC instants.

| Item | Accepted bound and behavior |
| --- | --- |
| Password | 15-128 Unicode scalar values, at most 1,024 UTF-8 bytes. Verify exact received text; no trim, normalization, case change or truncation. Permit spaces/paste/password managers; no composition or periodic-change rule. |
| Password verifier | Argon2id: 65,536 KiB, three iterations, parallelism one, independent random salt of at least 16 bytes, 32-byte result. At most two costly verification/hash jobs concurrently per service; overload fails generically without a hashing downgrade. |
| Common/breached-password check | Local versioned corpus with at least 3,000 policy-compatible common candidates plus breached-password coverage, checked at setup/change/reset. Reject login-name and `hortinis` context derivatives. No password or prefix goes to an external service. Corpus provenance/license/hash and coverage are verified in S9; absent/invalid corpus blocks authenticated activation. |
| Full session | 30 minutes since the last native session request, including synchronization, polling and CSRF/status requests; normal factors remain valid for 12 hours. Completing both normal factors again starts a new 12-hour factor lifetime; cookie rotation alone does not renew it. |
| Recent authentication | Both required normal factors verified within five minutes for credential changes, recovery-code replacement, enrollment/revocation, deletion, claims and other privileged transitions. A fresh bounded step-up rotates the cookie and CSRF state. |
| Pre-authentication/partial/recovery-only session | Five minutes absolute; no idle refresh; no binding identity, data, export, sync or ordinary enrollment authority. Maximum 1,000 live pre-authentication sessions per installation; exhausted capacity denies before allocation. |
| WebAuthn challenge | Independent 32-byte CSPRNG challenge, valid five minutes or the earlier parent-session/ticket expiry; consumed atomically once even on a failed verification. At most one current challenge per ceremony, eight live ceremonies per account. |
| Setup/operator recovery ticket | Independent 32-byte CSPRNG secret, valid ten minutes, one successful controlled enrollment only. At most one active ticket per target; replacement invalidates the earlier one. Server persists only its SHA-256 verifier and exact target/purpose/instance/mode. |
| Additional-device authorization | Five-minute, single-use ticket from recent full account authentication, bound to account, purpose and new browser ceremony. One account transaction creates enrollment and rotates its session; ticket alone does not grant garden access. |
| Recovery codes | Ten independent 32-byte CSPRNG codes, shown once, stored as SHA-256 verifiers with account/purpose and expiry. Each is usable once and expires after 365 days. Regeneration atomically invalidates the old set. Warn in the account UI within the last 30 days; expiration never deletes garden history. |
| Concurrent sessions | At most five full sessions per account; a sixth denies generically and offers authenticated session management. S11 allows one full session per enrolled device, rotating/replacing its previous session atomically. No automatic eviction of a different device's session. |
| Abuse windows | At most five password/recovery attempts per canonical-login key per rolling minute, 20 per trusted-source key per minute and 60 expensive authentication attempts installation-wide per minute. Check all applicable limits before work; no permanent account lockout or account-existence-dependent rate response. |
| Limiter state | Process-memory HMAC-SHA-256 keys with a random 32-byte per-process secret; no raw identifier/address persistence. Maximum 4,096 keys; expire after 15 minutes idle, clean every 60 seconds. Full capacity fails closed instead of evicting active restrictions. Restart resets transient counters; bound service restarts and test that residual operational assumption in S15. |
| Physical cleanup | Invalidate authorization synchronously. Delete expired sessions/challenges/tickets within 60 seconds during normal operation, immediately on next access and at startup before accepting traffic. Consumed recovery verifiers are removed in the consuming transaction; expired codes purge on the 60-second job. |
| Revoked device metadata | No access after committed revocation. Retain minimal status for authorized listing at most 30 days, then purge (including immediately overdue rows at startup). Account deletion overrides this period. |
| Security event retention | Rolling 30 days in the separate protected security store, automatic daily purge and overdue startup purge. Fixed event/outcome/reason, UTC and fresh request reference only in the baseline; no stable subject mapping. S15 verifies storage access and effective operator policy. |

Corpus checking is a narrow local reference-data capability, not telemetry or an implicit network
provider. S9 must obtain a reviewed licensed artifact and provide deterministic corpus fixtures; a tiny
demo list cannot satisfy the accepted policy. Secret generation, SHA-256/HMAC and constant-time secret
comparison use established JDK/framework primitives; they are never home-built cryptographic algorithms.

## Setup and account activation

Fresh `single-user-auth` starts with one explicitly designated empty technical scope and no account.
Only public capabilities, minimized health and the protected setup/CSRF ceremony are available until
controlled setup commits. An ordinary first request cannot claim the scope. The operator uses an
explicit local setup command, requiring host authorization, to create a target-bound ticket and write
its secret once to a new protected 0600 file in the application-owned 0700 secret directory. No secret appears in logs, command arguments, URLs or
default credentials. Reject a symlink, existing file, inconsistent mode/instance or competing setup.
The service removes its ticket file on consumption/expiry within 60 seconds and cleans overdue files
at startup; the directory is protected and excluded from business exports/ordinary backups. The operator
conveys the ticket to the intended owner through a protected channel, not a mandatory
application email provider.

A same-origin browser exchanges the ticket in a bounded POST body under CSRF/Origin protection for
setup-only authority, selects a policy-compliant password and registers a user-verified passkey. The
ticket cannot be an ordinary session or bypass password/passkey validation. Commit account activation,
credential records, explicit ownership, ticket consumption and committed mode consistently; concurrent
or interrupted attempts leave either no activated owner or the complete activated owner. Incomplete
setup expires without leaving an active password-only account. Complete normal two-factor login before
any personal-data endpoint becomes accessible.

S10 multi-user provisioning uses a controlled operator invitation for one intended account and creates
its new scope and relationship in the same transaction. No self-registration, request-selected scope,
email matching or automatic account linking is assumed. S12 alone implements claiming an existing
no-auth scope with backup/quiescence and explicit confirmation; S9 fresh setup never performs that claim.

## Login, rotation and expiry

Obtain session-backed CSRF state, then submit the canonical login name and exact password through a
closed no-store POST. Unknown/disabled/wrong-password cases perform the same dummy-verifier work under
identical limits and return the same failure shape/status. Do not serialize verifiers or arbitrary
framework principal objects into session storage. Persist only an allowlisted principal containing
opaque account/device references, authority epoch and factor times; erase transient credential material
when authentication completes. No saved-request body or redirect target captures login/content.

Partial password proof is not full authorization. WebAuthn options use discoverable credentials and
omit account-specific `allowCredentials` data from public/failed-login responses. A valid assertion must
resolve to the same active account as the password proof in the current ceremony. Reject factor mixing
across sessions, accounts or expired ceremonies. Replay fails after atomic challenge consumption.
Rotate the opaque session secret after password proof and again after full authentication; invalidate
its predecessor in the same ordered session workflow. Rotate CSRF state as well. Old, fixed or
uncommitted cookies cannot authorize access. Never upgrade partial authority solely because an account
has a registered credential.

Spring Session owns the 30-minute request inactivity limit and updates its last-access time for
ordinary requests, including background synchronization. Spring factor authorities retain their
12-hour lifetime; completing password plus user-verified passkey reauthentication starts a new
factor lifetime. Cookie rotation, CSRF refresh and the compatibility activity acknowledgement do not
renew factor proof. There is no separate qualifying-interaction clock or interaction timestamp.
Limited setup/password/recovery ceremonies retain their five-minute absolute boundary. Application
account ownership and epoch checks remain inside protected transactions. Cleanup reads native expiry
metadata rather than recalculating a second inactivity policy. Browser cookie closure is not proof
of server expiry; server limits and cleanup remain authoritative across restart.

Expiry/logout pauses synchronization and preserves offline data, outbox, frozen bodies and binding
provenance. Reauthentication resumes only the original retained origin/instance/account/scope and,
after S11, authorized device. An unrelated account cannot activate that outbox. Public discovery/status
cannot reveal account names, factor registration, scope population or credentials.

Completed session identity and normal factors are stored in Spring Security's native session context.
The application persists only limited ceremony state. Account ownership, epoch and mode are rechecked
inside protected transactions, holding the account and native session row locks through the work.
Native principal indexing owns full-session listing and revocation; one-use challenge rows retain
explicit account ownership independently of limited ceremony rows.

## Credential change and recovery

Normal password change requires current password, the new password policy and fresh normal factors.
Adding/removing a passkey or replacing recovery codes similarly requires recent full authentication.
Do not remove the last valid passkey without verified replacement. Change/reset increments the authority
epoch and invalidates all old sessions, partial ceremonies and incompatible tickets atomically. Return
to normal login rather than automatically making a recovery session fully authenticated.

| Path | Required proof | Permitted result |
| --- | --- | --- |
| Forgotten password | Current user-verified registered passkey plus one unused recovery code | Five-minute password-reset-only ceremony; choose new password, invalidate sessions and finish normal login. |
| Lost passkey | Current password plus one unused recovery code | Five-minute replacement-only ceremony; verify the new passkey, revoke the explicitly lost credential, invalidate sessions and finish normal login. |
| Lost both or no remaining recovery proof | Controlled host-operator ownership verification and target-bound recovery ticket | Restricted replacement of both factors, matching bootstrap assurance; no garden access, direct operator-chosen password or public bypass. |

Consume the code and restrict account recovery state in one transaction before issuing replacement
options. Failed/interrupted recovery can require another code or controlled recovery; never resurrect
a consumed code on reload. Proofs must target the same active account and cannot be mixed between
ceremonies. Generic external failure/status and bounded equivalent work cover nonexistent, expired,
used, disabled and wrong-target cases. No hints/security questions, automatic email link, recovery-code
alone login or unverified operator message authorizes replacement. Operator recovery initiation is a
host capability; document proportionate ownership verification and exceptional access in the operator
runbook before release. Remote support or the browser cannot call the host command.

Recovery codes should be kept in a separate protected place. Storing passwords, passkeys and recovery
codes in one compromised authenticator/profile reduces their independence; disclose that residual risk
and the lower phishing resistance of recovery codes. The service sends no credential material to an
email/identity provider. Optional user-controlled passkey synchronization may involve the user's vendor;
Hortinis neither selects nor contacts that vendor.

## Devices and committed revocation ordering

S9 resolves the sole authenticated account without pretending that a browser cookie is an enrolled
device. S11 adds explicit authorized enrollment; normal authentication on a new browser has enrollment-
only authority until the required enrollment transaction commits. Device IDs are generated server-side
and never inferred from a fingerprint, local data-set ID or passkey credential ID. A passkey may be used
on several enrolled devices; revoking one device does not delete account ownership or accepted history.
Labels are optional, bounded and excluded from diagnostics; activity uses day-level UTC display and
at most one daily update per device, separate from exact session security times.

For protected transactions acquire current account authority with a shared lock, then the applicable
device authority, then the existing scope lock where needed. Disablement, credential epoch changes,
revocation, claims and deletion use corresponding exclusive locks in the same account/device/scope
order. Resolve current ownership without trusting a saved session relationship. A permission check
outside the acceptance transaction is insufficient. Once invalidation commits, subsequent transactions
cannot accept or read protected data with the old authority. Work committed before invalidation keeps
its replay outcome; already released network data cannot be recalled. Test these races with real JDBC
transactions and delayed responses, not only mocked session status. Session persistence must not
resurrect an invalidated epoch/device when a stale request saves after logout or revocation.

## Protected online and offline logout

Online logout is POST-only, same-origin, CSRF-protected and no-store. Atomically invalidate the target
session, then advance the local exchange fence and clear transient authentication state. Spring Session
publishes and expires its cookie normally, including on logout and stale-cookie requests. A delayed
cookie-deletion response can clear a newer browser cookie and require reauthentication; it cannot
grant authority or undo server revocation. Transaction rollback retains server-side predecessor
state, but standard cookie publication may leave the browser referring to a rolled-back identifier.
The browser recovers through CSRF bootstrap and login. No `Clear-Site-Data` erases offline intent as a
side effect. Failed remote logout is explicitly pending; script cannot read the HttpOnly cookie.

Expose a fresh non-secret random logout target reference in authorized session status, distinct from
the cookie secret, account and device IDs. It grants no authority. Persist only that target reference,
origin and pending/logout fence in a dedicated local logout marker before stopping traffic; never
persist the session or CSRF token. Coordinate the marker across all tabs/data sets sharing this server
cookie, beyond the current per-data-set lease. While pending, suppress sync, activity and new login;
only public discovery and protected CSRF/logout completion may contact that origin. Server absolute
expiry continues even while offline.

On reconnection obtain fresh CSRF state and complete logout for the retained target before starting
another login. Under the server session lock compare the cookie's current non-secret target with the
submitted original target. If they match, invalidate the session; if no full target is active, return
an idempotent completed result; if a different target is active, return a generic precondition failure
without invalidating it. Recovery then requires explicit local acknowledgement, never an automatic
logout of the newer session. Spring Session owns cookie publication and expiration. Coordinate
login workflows across tabs to limit stale-response disruption; a delayed deletion may still require
reauthentication after interruption/reload. Server expiry/revocation and local response fencing remain
independent; a stale target reference alone can never revoke any
session. For logout of an expired/unknown full session, CSRF bootstrap supplies a bounded anonymous
ceremony through the explicit coordinated workflow. Native CSRF rejection may allocate an anonymous
transport session; that session alone grants no ceremony or data authority.
S12 implements interruption, multi-tab/session-change and delayed-response tests.

## HTTP, browser and deployment rules

Use explicit same-origin TypeSpec/OpenAPI 3.1 contracts before adapters. Authentication, session,
bootstrap, recovery and protected data responses and errors use `Cache-Control: no-store` and are
excluded from service-worker caches. Generic fixed JSON errors contain no login name, credential,
account/scope/device ID, existence hint or user input. Authentication failure is 401, authorized
precondition failure 409, invalid input 400, throttling 429 with a fixed 60-second Retry-After and
bounded overload 503; unknown/existing accounts share each applicable status and timing distribution.
No redirect/HTML error from a default filter bypasses the public JSON contract. Bound account/password
requests to 16 KiB; WebAuthn envelopes to 64 KiB, bounded nesting/token counts and decoded binary lengths
before cryptographic work. Narrow WebAuthn parsing does not relax synchronization's existing bounds.

Use the ADR's host-only Secure/HttpOnly/Strict cookie. No credentials in URLs, Authorization bearer
storage, localStorage, sessionStorage, IndexedDB, business exports, ordinary/security logs or analytics.
A CSRF bootstrap returns only necessary token/header metadata to transient script memory. Require the
session token header for all unsafe methods, even pre-auth setup/login/challenge/recovery/logout, plus
exact configured Origin/Fetch Metadata checks. Do not rely on preflight alone, allow wildcard credentialed
CORS or treat a LAN caller as trusted. Reject unsafe simple requests and repeated/opaque origins.

Authenticated public origins require HTTPS, valid configured RP ID/origin compatibility and a dedicated
first-party host. Development/tests may use explicitly test-only loopback HTTPS with disposable
certificates; production has no insecure-cookie switch. Disable raw forwarded-header interpretation by
default. A reverse proxy must isolate the backend, strip/overwrite forwarding fields and use an explicit
trusted peer configuration before those fields affect source limiting/transport. Host/forwarded input
never selects the RP, account, origin or scope. S15 verifies TLS termination, internal/database TLS,
certificate renewal, least privilege and secret lifecycle; ordinary Compose is development evidence.

Public shell headers: CSP with `default-src 'self'`, `script-src 'self'`, `connect-src 'self'`,
`img-src 'self' data:`, `font-src 'self'`, `style-src 'self' 'unsafe-inline'`, `object-src 'none'`,
`base-uri 'none'`, `frame-ancestors 'none'`, `form-action 'self'`, `worker-src 'self'`; no eval or
third-party runtime assets. Inline styles accommodate Angular's current runtime styles; this does not
permit inline scripts. Test static/production/offline shell compatibility and review style injection
risk at S15. Set X-Content-Type-Options nosniff, Referrer-Policy no-referrer, X-Frame-Options DENY and
restrict unused Permissions-Policy capabilities while allowing first-party WebAuthn. Enable HSTS
max-age 31,536,000 only on the verified production HTTPS host; includeSubDomains/preload are separate
operator decisions, not development defaults. The public offline shell never contains account data.

## Abuse, logs and privacy

Transient limiter keys cover canonical login and trusted source without logging or retaining their
values. Unknown/invalid login input follows the same bounded key/response rules. Do not evict active
limits to admit new attacker keys; capacity denial and global crypto concurrency bound memory/CPU.
Do not implement permanent attacker-triggered account lockout. The initial deployment has one active
backend limiter; scaling needs a reviewed shared limiter before activation. Proxy, restart/flood and
resource exhaustion assumptions remain explicit operational evidence, not account-existence leaks.

Separate protected security events from operational logging. Allow fixed login/recovery/setup/session/
revocation event, result/reason enum, UTC time and fresh request reference. Baseline has no subject
pseudonym, address, login string, credential ID or account/scope/device identifier. Raw failures from
cryptographic/framework adapters are translated to generic application diagnostics; do not enable
security DEBUG/TRACE, request/body capture, cookie logging or database bind-parameter logging. Counts
are not analytics. Privacy purpose/lawful basis, operator access, retention/purge and rights information
are recorded in the lifecycle; no S8 selection supplies a universal lawful basis or production approval.

## Implementation and evidence handoff

| Owner | Required implementation/evidence |
| --- | --- |
| S9 | Contract-first fresh sole-account setup, password/passkey and restricted recovery, JDBC sessions, CSRF/cookies/headers, expiry/rotation/concurrency, generic errors/limiter/cleanup, context mapping and server/browser offline pause/relogin tests. Authenticated mode remains unavailable until these checks pass. |
| S10 | Controlled provisioning plus explicit account/scope ownership; colliding identifiers, disabled-account and horizontal/vertical access tests, no cross-account existence or response leakage. |
| S11 | Authorized single-use enrollment, per-device session constraints/listing/activity cleanup, revocation races/restart and accepted-history preservation. |
| S12 | Claim/mode transitions, all-tab offline/logout target fencing, account/server changes, explicit local retention/deletion and resumable complete scope/account purge. |
| S15 and foundation gates | Full applicable ASVS evidence; independent security review/penetration scope; TLS/proxy/internal transport; corpus/hash/dependency updates; secret/log/backup/purge/restore controls; privacy information/rights/incident/DPIA/operator evidence. |

Design review covers every normal/partial/setup/recovery/enrollment/operator pathway for equal strength,
state/expiry/replay bounds, no authority inference, CSRF, enumeration and credential persistence.
Executable tests must include failure at each transaction boundary, concurrent ticket/code consumption,
wrong RP/origin/account/target, missing verification, replayed challenge, stale cookie writes, expiry at
exact boundaries, database/service restart, capacity/limiter saturation and diagnostic canaries.
S8 introduces none of those runtime controls and marks no ASVS requirement verified. D01-D03 offline/
logging deviations and host/shared-profile/same-origin residual risks remain separately disclosed.
