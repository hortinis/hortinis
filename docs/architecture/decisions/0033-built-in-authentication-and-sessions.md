# ADR-0033: Built-in authentication and sessions

- Status: Partially superseded for the target design by ADR-0034; existing runtime unchanged.
- Date: 2026-10-11
- Implementation owner: S8 design; S9-S12 adapters and S15 release verification.

See [ADR-0034](0034-spring-email-password-authentication.md) for the approved email/password replacement.
The decisions below remain historical evidence and apply only where the replacement retains them.

## Supersession boundary (R1, 2026-10-17)

The sections below document historical S8/S9 decisions and the current runtime, not the replacement
contract. ADR-0034 and the revised [specification](../authentication-and-sessions.md) override local
login names with unique canonical verified email, mandatory MFA/two-proof recovery with password login
and mailbox reset, and ticket setup with controlled email invitations. Factor age, step-up, partial
sessions, recovery codes, passkey adapters, five-session admission, ticket-file lifecycle and targeted
logout/activity/session-list contracts are obsolete implementation targets. Do not preserve their API
compatibility or validate the new design using their tests.

Retain stable account/scope ownership, password policy/Argon2id/blocklist/bounded work, native Spring
context/session/cookie/CSRF infrastructure, current security-version checks, transactional revocation,
no-auth isolation and minimized diagnostics. Simplify their application glue when replacing runtime.
Spring Security must own normal form login and logout; Boot Mail owns SMTP infrastructure. Only the
small purpose-bound invitation/reset service implements action-token semantics. No runtime, schema,
mail dependency or host command is changed in R1. ASVS B01 is reopened; no new strength pass follows.

## Context

ADR-0013 requires autonomous built-in accounts and protected cookie sessions, with optional OIDC.
ADR-0029 separates account authority from local data-set, device and synchronization scope identity.
S7 verifies synthetic scope isolation but neither authenticates people nor implements credentials.
The S1 threat model leaves authentication strength and numeric policy blocked at S8. A password-only
implementation cannot silently claim the ASVS 5.0.0 Level 2 target.

The repository already uses Spring Boot 4.1.1, Spring MVC and JDBC/PostgreSQL. Authentication should
use maintained framework mechanisms within that application and avoid an identity microservice,
Redis deployment, mandatory email provider or custom cryptographic protocol.

## Decision

Use Boot-managed Spring Security 7.1 and Spring Session JDBC 4.1 for built-in authentication,
request authorization, CSRF, fixation protection and durable server-side sessions. Add the Boot
security/session starters, `spring-security-webauthn`, the framework-required WebAuthn4J verifier and
Password4j when S9 implements the adapters. Resolve compatible versions through the accepted Boot BOM
where managed, pin and lock otherwise, and review the complete dependency graph before activation.
Do not copy illustrative framework default users, generated passwords, in-memory repositories,
permissive endpoints or conditional-factor examples into production.

Generate the local weak-password reference data during the Gradle build from a pinned SecLists
revision. Verify the upstream source, license and generated artifact checksums; cache the verified
upstream file under Gradle's user home; and package only the generated digest list. The running server
does not fetch data and remains offline-capable. A clean build needs network access to populate the
cache, while repeated builds can use the verified local cache.

The [Boot dependency coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
list Security 7.1.1 and Session 4.1.1 for the repository's Boot 4.1.1 line, checked on 2026-10-11.
Spring Security supplies [factor-aware authorization](https://docs.spring.io/spring-security/reference/servlet/authentication/mfa.html)
and a [WebAuthn adapter with replaceable persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html).
Use those mechanisms behind explicit Hortinis application contracts; their example URLs are not an
accepted public API. Security framework objects stay inside the authentication adapter. Scope/business
workflows continue receiving immutable trusted application contexts.

### Spring request authentication

Spring Security owns request contexts and requires password and WebAuthn factor authorities for
synchronization endpoints. `HttpSessionSecurityContextRepository` persists completed authentication
using Spring's native User and factor authorities. Spring's supported JSON security modules encode
the credential-free context; Java object deserialization is not enabled. Principal indexing stores
only the internal account UUID, not the login name. The synchronization resolver maps that principal
into a framework-independent application context and checks current account ownership and epoch. Transaction admission still locks and rechecks account epoch, session expiry
and scope ownership, so a request context cannot outlive concurrent revocation. Public capability
discovery and health probes remain public; authentication routes keep their distinct setup, login,
normal-factor-change and restricted-recovery admission rules. Storage failures deny access through
closed JSON errors, and Spring clears request identity at completion.

### Strength and recovery

Ordinary built-in login requires **both a password and a user-verified WebAuthn passkey**, from the same
active account in the same bounded login ceremony. Require user presence and `userVerification=required`;
reject a missing verification flag. Registration is mandatory before account activation. A password-only,
passkey-only or partially authenticated session cannot read binding identity or garden/sync endpoints.
Do not enable the framework's conditional rule that only requires MFA for already registered users.

This resolves B01's design question without claiming executable compliance. Password knowledge and
passkey possession plus authenticator verification are explicitly combined. Platform, roaming and
synced passkeys are allowed; no hardware-only Level 3 assurance is claimed. The server stores public
credential data, not an authenticator private key, PIN or biometric template. RP ID and exact public
HTTPS origin are controlled installation configuration, never derived from untrusted Host/forwarded
headers. A host change requires the explicit recovery/transition process rather than new implicit trust.

Use offline-held single-use recovery codes only together with a second independently verified proof:
password plus code to replace a lost passkey, or user-verified passkey plus code to reset a forgotten
password. These grant a short recovery-only workflow; completing replacement factors and a fresh normal
login is required before data access. Codes alone, email links, secret questions and an operator-set
user password are not alternative login paths. Controlled host-operator recovery can initiate a
restricted enrollment ceremony after documented ownership proof, with the same bootstrap assurance;
it cannot authenticate an ordinary garden session. Host compromise remains outside application
isolation, as in the existing threat model.

The normative [ASVS 5.0.0 catalog](https://raw.githubusercontent.com/OWASP/ASVS/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.flat.json)
remains the reference for strength, factor lifecycle and recovery. The detailed
[authentication specification](../authentication-and-sessions.md) defines each pathway, bounds,
privacy properties and adapter verification; no factor bypass is accepted.

### Passkey adapter profile

The S9 adapter uses Spring's wire option types and WebAuthn4j 0.31.9 verification with a
per-ceremony trusted account snapshot, required user verification and minimized current credential
state. Accept ES256/P-256, RS256/RSA 2,048–4,096 bits and EdDSA/Ed25519. Reconstruct public-only COSE
keys and discard optional authenticator metadata. The verifier's supported counter-handler extension
permits already verified backup-eligible credentials to reset/non-increase their counters, retaining
the observed maximum; other credentials retain the library's strict counter check. This exception
runs after signature, presence/verification and unchanged backup-eligibility validation. Every proof
still needs a consumed one-use server challenge. Signed tests cover all three algorithms and rejection
of incorrect bindings, flags, signatures, malformed client data and unsupported key profiles. These
adapter tests do not claim completed HTTP session, challenge lifecycle or release assurance.

Verification accepts the consumed server nonce and typed browser proof directly. Its RP, origin,
algorithm and verification policy come from the bound adapter, without rebuilding framework request
options. Persistence and verification share the immutable `AuthenticationCredential` public-key
model; the private verified-result constructor additionally binds the account handle, nonce, origin
and proof kind. Only publication under current account/session locks can grant authority.

`AuthenticationAccountService` owns both ordinary factor changes and restricted self-service
recovery. Shared password and registration publication paths keep their distinct authorization
checks: recent full authentication for ordinary changes, or the matching completed recovery ceremony
for recovery. Crypto remains outside publication transactions; nonce consumption remains separately
committed before proof parsing.

### Native session registry

Completed sessions are counted, listed and selected for revocation through Spring Session's native
principal index. The index stores only account UUIDs. Native idle expiry and Spring factor validity
exclude expired sessions; stale account epochs and partial contexts do not consume completed-session
capacity. A separate non-authorizing logout target preserves the public API without exposing native
cookie identifiers. Completing normal password plus passkey reauthentication starts a new 12-hour
factor lifetime. Account locks still serialize the five-session admission limit and revocation.
Spring Security and Spring Session framework logging is disabled in Boot configuration: diagnostic
logging in these libraries can include submitted login names, principals or session identifiers.
Application-owned minimized security events remain available.
Spring Session owns request inactivity; the separate explicit-interaction clock and database
column are removed. Background sync refreshes the native 30-minute idle timeout. Normal-factor
expiry remains independent of request activity. The activity endpoint is a compatible acknowledgement.

Completed identity and factor authority exist only in the native Spring Security context. Pending password authentication uses a native password-only context.
Limited setup and recovery state use bounded, typed Spring Session JSON attributes. There is no
parallel authority table or foreign-key lifecycle for sessions. Current account status, epoch, instance, scope and persisted mode remain transaction-time
checks. Protected transactions lock the account first and then the native session row. One-use
challenges store their account owner directly and are removed when their native reference is retired
or revoked; they do not require a duplicate full authority row.

### Password authentication

`AuthenticationManager` and `DaoAuthenticationProvider` own password authentication, credential
erasure and unknown-user equivalent-cost work. A provider-backed User retains the exact current
account snapshot only for the caller's transactional recheck; it is never cached as a principal.
The persisted context uses a separate credential-free native User with only the account UUID and
factor/epoch authorities. Login, step-up and password-plus-code recovery share this provider path.
The bounded PasswordEncoder adapter retains the selected Argon2id scheme and rejects unsafe stored
parameters before delegating computational work.

### Password storage

Use `Argon2Password4jPasswordEncoder` with explicitly configured Argon2id: 65,536 KiB total memory,
three iterations, parallelism one, independent random salt of at least 16 bytes and 32-byte output. Store the
algorithm/version/parameters with each verifier through `DelegatingPasswordEncoder`; accept only the
selected scheme in fresh installs. Do not rely on a default encoder's historical parameters or add
legacy/no-op schemes. Rehash on successful verification only when an accepted stronger policy requires
it; failed authentication never replaces a verifier.

The framework documents [Password4j-backed Argon2 configuration](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
The selected parameters exceed the [OWASP Argon2id minimum](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
S9 measures latency and bounded concurrency on the supported environment; S15 verifies the target
installation. Raising cost is allowed after review; silent reduction or hashing fallback is denied.

### Host command execution

Keep the local operator capability in the existing executable and application project. Its explicit
`operator` dispatch starts an isolated Spring context with `WebApplicationType.NONE`, the existing
Boot datasource/Flyway configuration and host-ticket components. The configuration sits outside the
ordinary application's component scan; it cannot install a listener, default user or garden resolver.
Closed arguments contain only action, protected directory and an optional non-secret target UUID.
No credential/ticket secret is accepted or printed. A dedicated logging configuration that discards events
and fixed process outcomes prevent framework/database failure diagnostics from exposing supplied
configuration. This preserves one backend project and established transactions without an additional
service, manual connection protocol or runtime administrative HTTP capability.

Only controlled fresh setup may select authentication from an empty installation with no committed
mode, atomically with ticket/file publication. Existing committed mode must match; a host invocation
is not an implicit no-auth conversion. Recovery retains host ownership-verification and protected
delivery requirements, and the ticket still grants only restricted replacement of both factors.

### Sessions and request defense

Use PostgreSQL-backed Spring Session as the opaque-cookie transport and established session repository.
Boot JDBC session auto-configuration owns production repository creation and servlet filter registration.
Its supported customizer retains the selected identifier, serialization and indexing policy; the named
transaction-operations bean uses REQUIRED participation so session writes share publication rollback.
Boot properties select timeout, immediate flush, save mode, minute-based native cleanup and filter
ordering. Flyway remains the schema owner. No-auth may load this standard infrastructure, but its
stateless access chain and routes neither allocate sessions nor use them as account authority.
Generate cookie secrets through its `SessionIdGenerator` extension with 32 independent CSPRNG bytes;
do not use the UUIDv7 business identifier generator. The cookie is `__Host-hortinis-session`, `Secure`,
`HttpOnly`, `SameSite=Strict`, `Path=/`, without Domain or persistent Max-Age. A dedicated first-party
host makes `/` the narrowest practical path under the `__Host-` rules. Session secrets are never
returned in JSON or copied to browser script storage. Disable URL rewriting and remember-me.

The [session ID extension](https://docs.spring.io/spring-session/reference/configuration/common.html)
and [JDBC cleanup](https://docs.spring.io/spring-session/reference/configuration/jdbc.html) are framework
capabilities. Hortinis adds bounded factor/ceremony policy and transactional account/device
invalidation; a repository's idle timeout alone does not satisfy that policy. No JWT access/refresh
scheme or positive authorization cache replaces current ownership/revocation checks.

Use Spring Security's session-backed CSRF protection plus the existing exact Origin/Fetch Metadata
boundary. Expose a no-store CSRF bootstrap to same-origin script memory only; require its header on
all unsafe methods, including setup/login/challenge/logout/recovery. Rotate after authentication and
session rotation. CSRF tokens are not login credentials and grant no scope access. Framework
[CSRF behavior](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) and
[session fixation controls](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
are implementation references; explicit JSON/generic-error adapters and tests are still required.

The browser guard uses Spring Security header writers for CSP, framing, content-type, referrer,
permissions and explicitly gated HSTS policy, including rejected requests before the security chain.
Local invalidation uses `SecurityContextLogoutHandler` within the publication transaction, with a
null context repository because native invalidation already removes the saved context and must not
allocate an empty replacement session. Target-fenced logout remains application policy.
Spring Session owns cookie publication and expiration using CookieHttpSessionIdResolver and
DefaultCookieSerializer; no route allowlist or after-commit cookie authorization adapter remains.
Spring Security owns CSRF token generation, deferred loading and storage through
HttpSessionCsrfTokenRepository. Unsafe requests without a token may create a native anonymous session
before denial; it grants no ceremony, account or synchronization authority. CSRF bootstrap admits a
bounded ceremony into that session. A small header-only request handler rejects parameter fallback
and duplicate headers. Tokens are opaque bounded text, including Spring's UUID representation.
A rolled-back transition cannot publish server authority, although the standard response handling
may issue a cookie referring to the rolled-back session ID. A stale deletion response can require
reauthentication in another tab; it cannot restore a revoked session or grant data access.
Session-ID rotation already delegates to the Servlet `changeSessionId` mechanism for all bounded
ceremony transitions; Spring's authentication-only strategy is not substituted for those transitions.

### OIDC boundary

OIDC remains an optional, disabled replaceable adapter. If separately activated, it must validate
issuer/subject binding, state, nonce, PKCE, signature, audience and replay; document how required
factor assurance and recent authentication are established. Never link by email or username, silently
provision a scope, or fall back to local/no-auth when the provider fails. Provider tokens stay
server-side with a separate reviewed lifecycle. A cross-site callback would need a reviewed bounded
correlation-cookie policy; it must not weaken the baseline Strict session cookie by default.

## Alternatives and rationale

- Password alone is simple but leaves the accepted strength blocker unresolved.
- TOTP adds a shared server secret and encryption/key-recovery responsibilities; WebAuthn avoids that
  secret and binds proof to the configured relying party. TOTP remains unselected.
- Passkey alone could combine authenticator possession and verification, but requiring the explicit
  password mechanism makes the application's two-proof normal/recovery boundaries straightforward.
- An external identity server or mandatory email/phone recovery compromises autonomous operation and
  expands recipients. OIDC remains opt-in after its separate assurance/privacy review.
- Stateless browser bearer tokens complicate immediate revocation and offline retention boundaries;
  server-side opaque sessions fit current JDBC transaction ownership without an additional service.
- A bespoke authentication/signature/hash implementation would duplicate established security behavior.

## Consequences and validation

Authenticated use requires a passkey-capable browser/authenticator and a stable secure RP host.
Authenticators may work locally without an identity provider; optional passkey cloud backup is controlled
by the user's authenticator vendor, not a Hortinis runtime dependency. Users should keep recovery codes
separately and may register a second authenticator. Browser profile/same-origin compromise can still
access offline data; sessions do not add offline encryption or remote wipe.

S8 installs no library, schema, cookie or endpoint and leaves authenticated modes rejected at startup.
S9 first defines TypeSpec/OpenAPI contracts, then adds locked dependencies and fresh-install account,
credential/session/challenge tables. No populated frontend/backend upgrade or ownership migration is
required for this session. S10 provisions separate owned scopes; S11 adds device-bound enrollment;
S12 implements transitions, offline logout and complete deletion. S15 retains deployment, operational,
ASVS and privacy evidence. The accepted [numeric policy](../authentication-and-sessions.md#numeric-policy)
is authoritative for their adapters; a design review is not executable release approval.

### Standard native session cleanup

Spring Session owns idle-expired transport rejection and scheduled physical deletion. Remove the
custom pre-request lifecycle filter, digest-based owner lookup and native orphan/validity sweeps.
Current factor and account/epoch checks reject unusable authority before cleanup; expired factor
contexts may remain stored until native idle expiry. Explicit revocation still deletes native rows
in its protected transaction. Anonymous CSRF sessions also use native idle retention.

Application-owned ceremonies, challenges, recovery codes, tickets/files and security events retain
startup and thirty-second scheduled cleanup. CSRF bootstrap replaces an unusable ceremony without
waiting for that job. These choices supersede immediate next-access physical purge. They remove
cleanup transactions and ticket filesystem reconciliation from ordinary request handling.

The native JDBC repository creates its own scheduler. Disable logging from its generic TaskUtils
error handler, which otherwise logs raw exception messages and stack traces. Existing application cleanup
jobs report fixed diagnostics; native cleanup failure retries at the next scheduled tick without
logging database details. Authorization never depends on a successful cleanup run.

### Native pending authentication state

Spring Session owns storage, rotation, principal indexing, invalidation and idle cleanup for every
session. Pending ceremonies use five minutes of native inactivity and an independent five-minute
absolute authorization deadline. Completed sessions use thirty minutes of native inactivity and
Spring factor validity. Account-associated recovery is indexed for revocation without authenticating
its principal or granting normal factors. The closed attribute codec bounds JSON and never enables
polymorphic deserialization for application ceremony data.

This replaces the bespoke installation-wide 1,000-pending-session admission counter. Existing request
throttles, cryptographic concurrency limits, challenge caps and five-completed-session admission remain.
The tradeoff is standard native idle retention: active requests can keep an expired ceremony's storage
alive, but cannot renew its authorization deadline. Bootstrap replaces expired state. Explicit
revocation deletes the indexed sessions and their proofs transactionally; scheduled application cleanup
removes orphaned or expired challenges/tickets, recovery codes and retained security events. Operator
ticket claims no longer rely on a session-authority foreign key. The fresh-database schema removes the
old table directly, with no migration compatibility layer.
