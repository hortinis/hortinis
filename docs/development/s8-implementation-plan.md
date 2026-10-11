# S8 implementation plan

## Analysis and scope

ADR-0013 chooses autonomous built-in accounts and opaque protected cookies, but leaves the library
selection to this step. ADR-0029 and the S1 threat/ASVS/lifecycle records require explicit authentication
strength, recovery and finite session policies before authenticated adapters. S4 currently rejects both
authenticated modes; S8 is a design increment, not activation of login or a production-readiness claim.
S7 and G2f are validated with synthetic contexts. Their scope boundary remains independent of identity.

Review the official documentation for the repository's Spring Boot 4.1.1 line, Spring Security 7.1,
Spring Session JDBC, WebAuthn and password encoders, and the pinned ASVS 5.0.0 requirements. Use
established authentication/cryptographic implementations, no custom signature validation, password
hashing or token encryption. Retain one Boot project, PostgreSQL/JDBC, Angular and same-origin delivery.

Select mandatory password plus user-verified WebAuthn for ordinary authenticated login. A partial
factor must not grant data authority. Avoid a conditional "MFA only after registration" policy and
avoid default accounts or an email/cloud dependency. Define limited controlled setup/enrollment and
recovery states so these cannot become weaker ordinary login paths. Record compatibility costs for
secure contexts, relying-party host stability, passkey-capable clients and authenticator recovery.

Define numeric bounds for sessions, challenges, setup, recovery, enrollment, recent authentication,
concurrency, abuse state and physical cleanup. Keep offline reads/writes independent. Logout must be
protected and fenced against an old offline instruction invalidating a newer session; background
synchronization cannot extend user inactivity indefinitely. Revalidation and acceptance must share a
committed ordering with disablement/revocation.

## Implementation sequence

1. Record ADR-0033 selecting Spring Security, WebAuthn and Spring Session JDBC, explicit Argon2id
   parameters through the framework, and an optional disabled OIDC boundary. Explain alternatives,
   deployment assumptions, additional libraries and fresh-schema impact. No dependency is installed
   merely to make the documentation step look executable.
2. Write the authentication/session specification: minimal identifiers, setup and login state machine,
   password/passkey changes, two-proof recovery, controlled operator recovery, enrollment/revocation,
   expiry/rotation/concurrency, CSRF, no-store contracts, cookie/header/TLS/proxy policies, bounded abuse
   handling and ordered authorization transactions. Distinguish S9, S10, S11 and S12 implementation work.
3. Refine the privacy inventory with the selected minimal account, public-key, secret, session,
   challenge, recovery, transient limiter and security-event fields; state purposes, recipients,
   retention/purge and user information. No email, biometric template, device fingerprint, telemetry,
   ordinary credential logs or new provider is introduced.
4. Update ADR-0013, architecture/open decisions, access specification, threat model, ASVS applicability
   and development handoffs. Resolve B01/B02 as design choices without marking their executable controls
   passed; activate the applicable factor/recovery rows and reconcile register counts. Keep deployment,
   deletion/backup, operational and legal/controller evidence as independent release gates.
5. Review every path for factor bypass, enumeration, fixation, stale logout, enrollment ownership,
   recovery downgrade, locking order, secret persistence, timing and shared-profile/offline limitations.
   Fix gaps, check English and cross-links, run documented repository validation, commit S8, then
   analyze and plan S9 before adding contracts, dependencies, schemas and adapters.

## Acceptance and verification limits

S8 delivers an accepted implementation handoff and numeric policy with primary-source attribution.
It adds no runtime endpoint, account, credential or authenticated mode. Public contracts are defined
with TypeSpec/OpenAPI in S9 before adapters; framework example endpoints do not silently become public
Hortinis contracts. Fresh frontend/backend databases remain the implementation assumption, without
populated-database upgrades. S9-S15 retain executable proof, complete authentication/device/transition
work, deployment/ASVS/privacy verification and the applicable foundation release gate.


## Completion evidence

Completed and reviewed on 2026-10-11. ADR-0033, the authentication/session specification and all linked
architecture, threat, ASVS and privacy handoffs are accepted. Review corrected stale cookie-deletion
behavior and bounded raw-ticket file cleanup. Documentation validation and the complete `pnpm validate`
suite pass; all 345 unique ASVS rows and updated disposition counts were checked separately.
This validates the S8 design deliverable only. S9-S15 retain runtime and release proof.
