# Authentication operator workflow

- Status: S9-R R4 host invitations implemented, 2026-10-17.
  Normal authenticated HTTP startup remains closed pending R5/R6.
- Authority: [ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md)
  and the [authentication specification](../architecture/authentication-and-sessions.md).

Normal authenticated HTTP startup remains closed while S9-R is incomplete. R4 implements only the
new invitation host commands and complete account-action adapters. The historical `setup-ticket`,
`recovery-ticket` and `reconcile` commands are rejected before context/database/file/listener activity.
There is no configurable startup bypass or operator password-reset bypass. The isolated host graph
uses non-web Boot auto-configuration for JDBC/Flyway/mail and never loads the web application's scan,
security chain or access-mode startup admission bean. Local-only and explicitly
selected no-auth remain available without mail. Keep development data synthetic until S9-R, applicable
foundation gates and S15 pass; isolated R2 HTTPS tests do not authorize deployment.

## Configuration and trust boundary

Keep one Spring Boot executable/project and an isolated non-web `operator` entry point using Boot
JDBC/Flyway. No listener, default user, browser administrator or operator garden session. Fresh
single-user authenticated provisioning can reserve only the exact empty unowned sole scope; it cannot
claim/convert a committed no-auth installation. S10 owns additional accounts/scopes; S12 owns mode
transitions. Restart never changes committed mode or ownership by configuration alone.

Authenticated use requires an explicit HTTPS `HORTINIS_ACCESS_PUBLIC_ORIGIN`, validated TLS boundary,
secure host-only cookies and exact same-origin `/api/v1/` routing. Retain explicit access mode and
`HORTINIS_ACCESS_TLS_VALIDATED` validation. Ignore request Host/forwarded identity when creating links
or resolving authority. Browser checks and action links share the same canonical configured origin:
HTTPS scheme comparison is case-insensitive, DNS hosts are lowercased and explicit port 443 is omitted.
No arbitrary return URL or request-origin redirect is accepted.

R3 adds Boot's mail starter and wires a single operator-selected SMTP service through Boot Mail;
no provider-specific API or delivery queue. Self-hosted SMTP is supported; no Hortinis-operated or
particular external provider is required. Standard Boot property contract:

| Property | Deployment requirement |
| --- | --- |
| `spring.mail.host`, `spring.mail.port` | Explicit SMTP destination/port, allowlisted operator egress. |
| `spring.mail.username`, `spring.mail.password` | Protected deployment secrets where SMTP authentication is required; never committed or passed as CLI arguments. |
| `spring.mail.properties[mail.smtp.auth]` | Explicit authentication policy for the selected service. |
| `spring.mail.properties[mail.smtp.starttls.enable]`, `[mail.smtp.starttls.required]`, `[mail.smtp.ssl.checkserveridentity]` | Require certificate-validated STARTTLS for network SMTP; never silently fall back to cleartext. Any local trusted relay exception needs documented S15 transport review. |
| `spring.mail.properties[mail.smtp.connectiontimeout]`, `[mail.smtp.timeout]`, `[mail.smtp.writetimeout]` | Each 5,000 ms; no infinite default. |
| `hortinis.authentication.mail-from` | R3 application property: one validated fixed sender mailbox, never caller input. No display-name/header interpolation. |

R3 application YAML supplies all three 5,000 ms timeouts, disables mail protocol debug and disables
startup connection tests and mail-health probes. No SMTP destination, sender, credential or Compose
mail service is committed. The authenticated graph validates the remaining required settings,
including explicit SMTP authentication policy and credentials when authentication is enabled.
JNDI, custom socket factories and certificate-trust overrides are rejected. Network SMTP requires
STARTTLS and certificate identity checks; no production cleartext relay exception is implemented.
Keep the application origin as the sole URL configuration; do not add a second mail-link
origin. Missing/invalid required authenticated-mode mail configuration must fail startup with a fixed
message; transient SMTP unavailability must not trigger a mode downgrade or prevent valid password
login. Local-only/no-auth use requires no SMTP and sends no mail. No startup test email is permitted.
R4 composes this delivery boundary with host invitation issuance and public reset requests. Normal
authenticated HTTP startup remains closed, and the replacement browser is R5 work.

## Controlled invitation command contract

R4 implements the following closed command shapes:

| Host command | Input and result |
| --- | --- |
| `operator invite` | Read exactly one bounded email line from protected standard input, never a password/token argument. Atomically reserve pending account/email and intended fresh sole scope, issue invitation and commit, then attempt SMTP. No active account or login is created. |
| `operator resend-invitation` | Read one bounded email line from protected standard input. Validate/canonicalize it and resolve only its existing pending account in this installation. Keep the stored delivery mailbox/scope, replace the token under quotas, commit and attempt SMTP. Never create an account. |
| `operator cancel-invitation` | Read one bounded email line from protected standard input. Validate/canonicalize it and resolve only its existing pending account in this installation. Invalidate its token and remove the unactivated reservation. Never delete an active/disabled account or transfer its scope. |

Use authorized host identity and protected datasource/migration credentials through the existing Boot
configuration mechanism. Do not print or log mailbox, account ID, token, link, configuration, SQL error
or provider exception. Fixed process outcomes: exit 0 for committed action and SMTP acceptance (or
successful cancellation), exit 2 for invalid command/input, exit 1 for throttling, configuration,
persistence or delivery failure. Delivery failure after commit must report fixed text explaining that
the pending invitation was committed and may be resent after the cooldown; it must not imply rollback.
Exit 0 SMTP acceptance is not proof of inbox delivery. A lost process result is resolved by an explicit
resubmission of the same email to resend/cancel, never automatic scope provisioning.

All three commands use the same email syntax/canonicalization and single-line input contract. No
account UUID, target-discovery command or database inspection is needed after initial issuance,
SMTP failure or a lost response. Resolve and recheck pending status/installation/mode in the mutation
transaction. Case variants may locate the same pending account but never change its stored delivery
address. Unknown, active or disabled targets cause fixed failure with no mutation; resend/cancel
never provision or retarget an account. Email and credentials remain absent from command arguments,
process output and diagnostics. R4 must test first-send failure followed by email-selected resend,
case variants, cancellation and denial for unknown/active/disabled targets.

The public mail-budget exhaustion and cross-process quota-enforcement questions remain open as
[S9-R Q1/Q2](../development/spring-email-authentication-plan.md#open-questions-required-before-s9-r-completion).
Their resolution and evidence are required by R6 before S9-R completion; this input-contract fix
does not settle either quota question.

Invite syntax/canonicalization and the database unique constraint are defined in the specification.
Duplicate/case-variant active or disabled emails never become new accounts. To correct a pending typo,
cancel that reservation and explicitly issue a new invitation to the intended mailbox; never retarget
an existing active account. R4 removes the retired ticket issuer, persistence, file handling, dedicated tests and fresh-schema
ticket table. There is no raw ticket directory, reconciliation command or ticket compatibility layer.
Remaining WebAuthn/browser/storage deletion belongs to R5.

Read email from an operator-protected file or pipe, never an argument or shell history. The input is
at most 254 ASCII mailbox bytes, optionally followed by one LF or CRLF; EOF without a terminator is
accepted. Additional lines, trailing data, invalid ASCII/syntax and more than 256 total bytes fail
with exit 2 before Boot starts. Protect the input source and normal datasource/SMTP configuration
with host access controls; the command does not create a secret file or discover a target UUID.
For example, substitute the built executable archive and your protected input path:

```shell
java -jar /path/to/hortinis-sync.jar operator invite < /protected/invitation-email
java -jar /path/to/hortinis-sync.jar operator resend-invitation < /protected/invitation-email
java -jar /path/to/hortinis-sync.jar operator cancel-invitation < /protected/invitation-email
```

Cancellation preserves the sole scope and committed authenticated mode. A later explicit invite may
reserve that empty, unowned scope again; cancellation never converts mode or removes content. Resend
cannot extend the original seven-day pending lifetime. A pending row that has reached that deadline
may still be explicitly cancelled before bounded cleanup removes it.

Q2 is observable in R4's isolated-process fixture: an immediate resend can pass a new process's fresh
counter. This is evidence of incomplete cross-process enforcement, not approval to ignore the
cooldown or proof of shared quotas. Operators must respect the provisional limits; R6 must replace
this limitation with the reviewed enforcement boundary before S9-R completion.

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
risk and processor/retention policies. Q1/Q2 must already be resolved at the end of S9-R, rather
than deferred to S15. Backups can contain token verifiers and email; expiry remains
absolute after restore, sessions/old credentials must not be resurrected, and complete deletion/restore
suppression remain S12/P0.6/S15 gates. Fresh schemas need no compatibility migrations.

The default Compose topology remains synthetic no-auth development. No SMTP host, credential, real
recipient, new container or authenticated deployment recipe is added by R1.
