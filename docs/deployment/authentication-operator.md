# Authentication operator workflow

- Status: S9-R R1 deployment contract, 2026-10-17; email runtime and commands unimplemented.
- Authority: [ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md)
  and the [authentication specification](../architecture/authentication-and-sessions.md).

The current executable still implements historical S9 MFA and protected host tickets. The old
`setup-ticket`, `recovery-ticket` and ticket-file `reconcile` workflows are obsolete replacement
targets; they do not issue email invitations or reset links. This document describes the future
operator interface and prerequisites, not commands to run against the current binary. R1 changes no
runtime or deployment configuration. Keep all development data synthetic until S9-R, applicable
foundation gates and S15 pass; neither historical HTTPS tests nor this handoff authorize deployment.

## Configuration and trust boundary

Keep one Spring Boot executable/project and an isolated non-web `operator` entry point using Boot
JDBC/Flyway. No listener, default user, browser administrator or operator garden session. Fresh
single-user authenticated provisioning can reserve only the exact empty unowned sole scope; it cannot
claim/convert a committed no-auth installation. S10 owns additional accounts/scopes; S12 owns mode
transitions. Restart never changes committed mode or ownership by configuration alone.

Authenticated use requires an explicit HTTPS `HORTINIS_ACCESS_PUBLIC_ORIGIN`, validated TLS boundary,
secure host-only cookies and exact same-origin `/api/v1/` routing. Retain explicit access mode and
`HORTINIS_ACCESS_TLS_VALIDATED` validation. Ignore request Host/forwarded identity when creating links
or resolving authority. No arbitrary return URL or request-origin redirect is accepted.

R3 must add Boot's mail starter and wire a single operator-selected SMTP service through Boot Mail;
no provider-specific API or delivery queue. Self-hosted SMTP is supported; no Hortinis-operated or
particular external provider is required. Standard Boot property contract:

| Property | Deployment requirement |
| --- | --- |
| `spring.mail.host`, `spring.mail.port` | Explicit SMTP destination/port, allowlisted operator egress. |
| `spring.mail.username`, `spring.mail.password` | Protected deployment secrets where SMTP authentication is required; never committed or passed as CLI arguments. |
| `spring.mail.properties[mail.smtp.auth]` | Explicit authentication policy for the selected service. |
| `spring.mail.properties[mail.smtp.starttls.enable]`, `[mail.smtp.starttls.required]`, `[mail.smtp.ssl.checkserveridentity]` | Require certificate-validated STARTTLS for network SMTP; never silently fall back to cleartext. Any local trusted relay exception needs documented S15 transport review. |
| `spring.mail.properties[mail.smtp.connectiontimeout]`, `[mail.smtp.timeout]`, `[mail.smtp.writetimeout]` | Each 5,000 ms; no infinite default. |
| `hortinis.authentication.mail-from` | Proposed R3 application property: one validated fixed sender mailbox, never caller input. No display-name/header interpolation. |

These properties are a target configuration contract, not changes to application YAML, Compose or
secret files. Keep the application origin as the sole URL configuration; do not add a second mail-link
origin. Missing/invalid required authenticated-mode mail configuration must fail startup with a fixed
message; transient SMTP unavailability must not trigger a mode downgrade or prevent valid password
login. Local-only/no-auth use requires no SMTP and sends no mail. No startup test email is permitted.

## Controlled invitation command contract

The following command shapes are specified for R4, not implemented in R1:

| Host command | Input and result |
| --- | --- |
| `operator invite` | Read exactly one bounded email line from protected standard input, never a password/token argument. Atomically reserve pending account/email and intended fresh sole scope, issue invitation and commit, then attempt SMTP. No active account or login is created. |
| `operator resend-invitation --account=<uuid>` | Explicit pending account target only, same stored mailbox/scope. Replace the token under quotas, commit and attempt SMTP. |
| `operator cancel-invitation --account=<uuid>` | Pending account only; invalidate token and remove unactivated reservation. Never delete an active/disabled account or transfer its scope. |

Use authorized host identity and protected datasource/migration credentials through the existing Boot
configuration mechanism. Do not print or log mailbox, account ID, token, link, configuration, SQL error
or provider exception. Fixed process outcomes: exit 0 for committed action and SMTP acceptance (or
successful cancellation), exit 2 for invalid command/input, exit 1 for throttling, configuration,
persistence or delivery failure. Delivery failure after commit must report fixed text explaining that
the pending invitation was committed and may be resent after the cooldown; it must not imply rollback.
Exit 0 SMTP acceptance is not proof of inbox delivery. A lost process result is resolved by an explicit
same-account resend/cancel, never automatic scope provisioning.

Invite syntax/canonicalization and the database unique constraint are defined in the specification.
Duplicate/case-variant active or disabled emails never become new accounts. To correct a pending typo,
cancel that reservation and explicitly issue a new invitation to the intended mailbox; never retarget
an existing active account. Raw ticket directories/files are not part of the replacement deployment.
Remove their old configuration and cleanup only when the runtime replacement removes the dependency.

## Mailbox recovery and delivery failures

Invitation links expire after 24 hours; password-reset links after ten minutes. Resend replaces the
previous token, including when the new SMTP attempt fails. The old email then cannot be used. No
transaction is held across SMTP. A committed current token remains valid until expiry/replacement even
if the sender receives a timeout; do not retry automatically after uncertain delivery. Use synchronous
bounded sends with two concurrent calls and the documented quotas; there is no hidden delivery spool.

The two fixed plain-text templates contain the application name, action, expiry and trusted-origin
fragment link. They include no garden content, account/scope ID, password or tracking assets. Only the
stored validated delivery address selects the recipient. Disable SMTP client protocol debug, provider
exception logging, edge/body/query logging, and tracking/link-rewrite services where operator-controlled.
Mail infrastructure/mailboxes may retain messages and action secrets; document their recipients,
security, retention and backup policies. A scanner must not activate/reset through GET.

Public reset requests always return the same acknowledgement for unknown/pending/disabled accounts,
mail quotas and recipient-specific SMTP failure. Operational diagnostics use only fixed delivery
outcome codes and fresh references. Synchronous sending still needs timing equalization and evidence
in R3/R6. Operators investigate SMTP availability through protected infrastructure procedures, without
turning on body/recipient/credential logging. Authentication never downgrades during outage.

Mailbox control can replace the password. Mailbox compromise or reassignment therefore threatens the
account. Loss of password plus mailbox has no supported recovery: host-ticket browser recovery,
operator-chosen password, email retargeting and implicit impersonation are absent. Explain this before
enrollment. Optional MFA/passkeys, saved recovery codes, email changes and extra notifications are deferred.

## Readiness and restore gates

R2-R6 must prove native login/session/CSRF/logout, pending denial, token races/rollback, SMTP timeout
and failure, no leaks, timing and offline browser behavior. S15 must review SMTP transport, sender
configuration/deliverability, abuse/restart limits, mailbox recovery strength, indefinite active-session
risk and processor/retention policies. Backups can contain token verifiers and email; expiry remains
absolute after restore, sessions/old credentials must not be resurrected, and complete deletion/restore
suppression remain S12/P0.6/S15 gates. Fresh schemas need no compatibility migrations.

The default Compose topology remains synthetic no-auth development. No SMTP host, credential, real
recipient, new container or authenticated deployment recipe is added by R1.
