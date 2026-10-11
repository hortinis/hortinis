# ADR-0033: Built-in authentication and sessions

- Status: Accepted
- Date: 2026-10-11
- Implementation owner: S8 design; S9-S12 adapters and S15 release verification.

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

The [Boot dependency coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
list Security 7.1.1 and Session 4.1.1 for the repository's Boot 4.1.1 line, checked on 2026-10-11.
Spring Security supplies [factor-aware authorization](https://docs.spring.io/spring-security/reference/servlet/authentication/mfa.html)
and a [WebAuthn adapter with replaceable persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html).
Use those mechanisms behind explicit Hortinis application contracts; their example URLs are not an
accepted public API. Security framework objects stay inside the authentication adapter. Scope/business
workflows continue receiving immutable trusted application contexts.

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

### Sessions and request defense

Use PostgreSQL-backed Spring Session as the opaque-cookie transport and established session repository.
Generate cookie secrets through its `SessionIdGenerator` extension with 32 independent CSPRNG bytes;
do not use the UUIDv7 business identifier generator. The cookie is `__Host-hortinis-session`, `Secure`,
`HttpOnly`, `SameSite=Strict`, `Path=/`, without Domain or persistent Max-Age. A dedicated first-party
host makes `/` the narrowest practical path under the `__Host-` rules. Session secrets are never
returned in JSON or copied to browser script storage. Disable URL rewriting and remember-me.

The [session ID extension](https://docs.spring.io/spring-session/reference/configuration/common.html)
and [JDBC cleanup](https://docs.spring.io/spring-session/reference/configuration/jdbc.html) are framework
capabilities. Hortinis adds bounded absolute/user-inactivity policy and transactional account/device
invalidation; a repository's idle timeout alone does not satisfy that policy. No JWT access/refresh
scheme or positive authorization cache replaces current ownership/revocation checks.

Use Spring Security's session-backed CSRF protection plus the existing exact Origin/Fetch Metadata
boundary. Expose a no-store CSRF bootstrap to same-origin script memory only; require its header on
all unsafe methods, including setup/login/challenge/logout/recovery. Rotate after authentication and
session rotation. CSRF tokens are not login credentials and grant no scope access. Framework
[CSRF behavior](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) and
[session fixation controls](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
are implementation references; explicit JSON/generic-error adapters and tests are still required.

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
