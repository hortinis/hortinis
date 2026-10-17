# ADR-0034: Spring email and password authentication

- Status: Accepted direction; implementation pending.
- Date: 2026-10-17
- Implementation owner: S9-R; S10-S15 consume the revised baseline.
- Partially supersedes: [ADR-0033](0033-built-in-authentication-and-sessions.md).

## Context

The implemented S9 password/passkey, self-service code recovery and host-ticket workflows require
substantial application orchestration despite delegating session storage to Spring. The user selected
a simpler initial product with verified email/password login, email self-service password reset and
controlled email invitations, with passkeys/MFA and saved recovery codes deferred. The runtime has not
yet been replaced. Existing security evidence applies only to the implementation it exercised.

## Decision

Use Boot-managed Spring Security form login, its password provider/encoder, security contexts, session
fixation protection, CSRF and logout. Use Spring Session JDBC through Boot auto-configuration. Use
Spring Boot Mail (`spring-boot-starter-mail`, `JavaMailSender`) with operator-configured SMTP and simple
templates. This reuses the existing Spring stack and avoids provider-specific APIs, a new identity
service, delivery queues and a custom authentication engine.

Use Spring JDBC and transactions for a small shared purpose-bound invitation/password-reset token
component. Store hashes, consume atomically and enforce expiry. These are action tokens, not ordinary
login credentials: do not route them through token login merely to reuse authentication machinery.
Activation/reset completes without automatic login. Password changes/reset revoke existing sessions;
current account/security-version/scope checks remain inside protected application transactions.

Use pending, active and disabled accounts with stable account UUIDs independent of email. Initial
email enrollment is verified by invitation acceptance; public registration and email changes are
outside this increment. Keep password policy, blocklist, abuse limits and minimized audit records.
Defer passkeys, mandatory MFA, saved recovery codes and operator-ticket browser recovery. No fallback
silently lowers the configured access mode.

## Consequences and supersession

Mailbox control becomes sufficient to reset the password. SMTP is required for authenticated
invitation/reset, while local-only and no-auth modes remain independent of email. SMTP may be
self-hosted or operator-selected; no particular external provider is mandatory. Email addresses and
mail delivery introduce processing/retention and deployment requirements that must be documented.
Accounts without mailbox access cannot use self-service recovery; no operator bypass is implied.

ADR-0033 remains the history of the current implementation and the source of unchanged password,
privacy and ownership requirements. Its mandatory passkey/MFA, recovery-code and no-email requirements
are superseded as implementation targets. The old factor/session orchestration is not a compatibility
requirement. Fresh databases need no compatibility migration. Old ASVS/MFA conclusions must be
reassessed; this decision makes no new production, legal-compliance or security-level claim.

The [implementation plan](../../development/spring-email-authentication-plan.md) defines component
mappings, phased replacement, deletion targets, validation and S-track reconciliation. S9-R must finish
before S10 consumes the new authentication baseline. S11-S15 retain their independent gates.
