# ADR-0034: Spring email and password authentication

- Status: Accepted; R1 complete, R2 native backend validated with authenticated admission
  closed; R3 internal JDBC action-token/Boot SMTP foundation implemented, remaining workflows pending.
- Date: 2026-10-17
- Implementation owner: S9-R; S10-S15 consume the revised baseline.
- Partially supersedes: [ADR-0033](0033-built-in-authentication-and-sessions.md).

## Context

The implemented S9 password/passkey, self-service code recovery and host-ticket workflows require
substantial application orchestration despite delegating session storage to Spring. The user selected
a simpler initial product with verified email/password login, email self-service password reset and
controlled email invitations, with passkeys/MFA and saved recovery codes deferred. R2 replaces the
native backend login/session boundary while normal authenticated startup remains closed; the email
and browser replacement is incomplete. Existing security evidence applies only to the implementation
it exercised.

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

## R1 policy refinement (2026-10-17)

The [authentication specification](../authentication-and-sessions.md) is the numeric and transaction
policy: 24-hour invitations, ten-minute reset tokens, one current token per account/purpose, atomic
completion without login and account-wide revocation on reset/change. SMTP follows issuance commit;
failed/uncertain delivery never restores the old token or changes credentials. Resends share a
60-second email cooldown, three/hour and ten/day email quotas, with independent global/source limits.

Accept bounded ASCII email addresses; validate without trimming and lowercase the entire address for
a unique canonical key across pending/active/disabled accounts. Preserve validated delivery case; do
not rewrite provider aliases. This intentionally excludes case-distinct accounts and internationalized
mailboxes. Pending typo correction is cancel/new invitation, never active account/scope transfer.
Pending reservations purge seven days after original creation; resend cannot extend this deadline.

Use native thirty-minute request inactivity with no separate absolute lifetime or concurrency cap.
Background traffic can keep sessions alive indefinitely; remove historical twelve-hour factor ages,
five-session counting and session-management APIs. Native bodyless logout replaces target-based
logout; offline sign-out pauses sync and never automatically replays logout against a newer cookie.
Current-password proof protects password change. S11/S12 must specify other sensitive-action proof
before implementation. These simplifications retain explicit residual risks and S15 session review.

Password-only login leaves ASVS `v5.0.0-6.3.3` unresolved; verified email/reset is not a second login
factor. Do not inherit S8's strength resolution. Reset lifetime is tightened from the preliminary
30-minute R1 proposal to ten minutes for conservative token-lifetime coverage. Invitation's 24-hour
activation window is distinct and its lifetime interpretation remains explicit in the ASVS register.
R1 [contracts](../../api/authentication-contracts.md) are the future API, ahead of unchanged runtime.

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
