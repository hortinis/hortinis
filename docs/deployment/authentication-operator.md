# Authentication operator workflow

S9 implements the selected single-user authenticated runtime, browser account workflows and host
ticket command. Use synthetic data until the applicable foundation/S15 release gates pass. This
document does not authorize a personal-data trial or certify deployment security.

## Host capability and prerequisites

The backend executable accepts a closed `operator` command. It starts an isolated non-web Spring
context using the same Boot datasource/Flyway configuration as the application. It installs no HTTP
listener, default user, garden resolver or ordinary operator session. Ticket issuance grants only a
bounded enrollment/recovery opportunity; the owner must choose a compliant password and verify a
user-verified passkey before completing setup or both-factor recovery. Normal login follows.

Run as the authorized application host identity. Create an existing application-owned secret directory
with POSIX mode `0700`; the command requires secure relative traversal and refuses symlinks in every
path component. Ticket files are exclusively created as `0600`, with random non-secret filenames and
one canonical 43-character ticket value followed by a newline. No unsafe filesystem fallback exists.
The issuer refuses foreign-owned files, unexpected entries and unsafe permissions. Keep this dedicated
directory outside repository content, ordinary logs, business exports and ordinary backup collections.

Supply datasource credentials through the deployment's protected environment/secret mechanism, using
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. Standard Boot
Flyway settings apply, including separately managed migration credentials where configured. Set
`HORTINIS_ACCESS_MODE=single-user-auth` explicitly. Never put database credentials, passwords, recovery
codes or tickets in command arguments. The only arguments are action, absolute directory and, for
recovery, a canonical non-secret target UUID. Process output contains only fixed success/failure text.

The executable archive is built with `./gradlew :services:sync:bootJar`. Replace `backend.jar` below
with that archive's actual path. The directory path is an example; select the operator-managed location
for this installation rather than committing it as environment-specific configuration.

## Controlled first ownership

Before issuing a setup ticket, establish the intended sole owner and secure delivery channel. Setup
accepts only an empty, unowned sole scope. From a fresh unconfigured installation it atomically selects
`single-user-auth` with ticket/file publication; a failed publication restores the previous state.
An already matching authenticated installation may replace its pending setup ticket. A committed
no-auth or other mode cannot be converted by this command; S12 owns explicit mode transitions.

```shell
java -jar backend.jar operator setup-ticket --directory=/srv/hortinis/auth-tickets
```

A successful command reports `Operator ticket written to protected storage.` It prints no secret,
account identifier or supplied path. Reissuing replaces the earlier ticket and removes its old file.
At most one setup ticket is active for the installation. The ticket expires after ten minutes; the
browser ceremony expires after five minutes or earlier ticket expiry. The same current claimed
ceremony may retry without extending that original deadline. Another ceremony cannot take the claim.

Read and deliver the one current ticket through the deployment's approved private secret-handling
workflow. Do not paste it into ordinary support messages, recorded terminals, logs, URLs or shell
arguments. The intended owner supplies the value in the same-origin protected setup form, chooses a
password and registers a resident user-verified passkey. Setup creates the sole account/ownership,
first credential and ten recovery codes atomically, then requires normal password-plus-passkey login.
The owner stores the show-once recovery codes separately in a protected offline location.

## Exceptional recovery of both factors

Use normal two-proof recovery when possible: registered user-verified passkey plus unused code for a
forgotten password, or current password plus unused code for a lost passkey. A code alone never logs
in. Completing recovery invalidates old authority and requires normal login.

Host recovery is exceptional. Before issuance, the authorized operator must establish the intended
sole account owner's identity and entitlement using the deployment's previously accepted ownership
records and incident process. An unverified support message, account identifier or knowledge of garden
content does not establish ownership. If sufficient evidence is unavailable, do not issue a ticket.
The effective evidence, exceptional-access authorization and minimized record/retention policy remain
operator deployment responsibilities under the S15 privacy/security gate; do not collect credentials,
garden content or additional identity material into application logs.

Use the exact current account UUID from the operator's authorized installation inventory. The command
accepts only the current sole active account with its existing scope; it creates no account or scope.

```shell
java -jar backend.jar operator recovery-ticket --account=019a0b2d-9440-7ce7-b1e4-bfc0112c9f52 --directory=/srv/hortinis/auth-tickets
```

The UUID above is synthetic. Successful issuance does not change credentials or grant data authority.
The ticket is bound to the exact account, installation, mode and operator-recovery purpose. It expires
after ten minutes; replacement invalidates its predecessor. Deliver it only through the approved
protected channel to the verified owner, who chooses a new compliant password and verifies a new
passkey. The operator does not choose the owner's password or obtain an ordinary garden session.

Final recovery checks the current claimed ticket and consumed challenge again before atomically
replacing both factors, deleting obsolete credential/recovery verifiers and invalidating all old
sessions/ceremonies/tickets. Account, scope, stable user handle and synchronization history remain.
After normal login with the replacement factors, the owner generates a new recovery-code set under
recent normal authentication. No old code is resurrected after interruption, reset or rollback of a
later replacement step. An expired/consumed claim may require a new controlled ticket.

## Reconciliation and process outcomes

```shell
java -jar backend.jar operator reconcile --directory=/srv/hortinis/auth-tickets
```

Reconciliation uses the same installation/account locks as issuance, deletes expired or invalid ticket
rows and removes only recognized protected ticket files whose references are no longer current. It
never reads file secrets. An unconfigured empty installation can remove a file orphaned by a rolled
back publication without selecting a mode. Unsafe or unexpected directory entries cause fixed failure.

Exit code `0` means the requested host action completed. Exit code `2` means invalid arguments and no
context/database startup. Exit code `1` means missing/mismatched authentication configuration or an
operation failure; no supplied input, configuration value, exception message or cause is printed.
Investigate using authorized host/database health procedures; do not enable credential/body logging.

The runtime lifecycle adapter reconciles overdue database/file cleanup before accepting traffic and
maintains the selected physical-removal bound of 60 seconds, including next-access cleanup. Scheduled
and next-access cleanup are implementation behaviors; production operator, retention and restore
evidence remain S15 deployment gates.
