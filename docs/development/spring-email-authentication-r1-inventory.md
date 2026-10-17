# Spring email authentication R1 inventory

- Date: 2026-10-17.
- Baseline: `ce5c59b` (the approved replacement plan), clean working tree at R1 start.
- Owner: [S9-R replacement plan](spring-email-authentication-plan.md).
- Authority: [ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md).

R1 changes design, TypeSpec/generated artifacts and contract/documentation checks only. The map below records the R1 starting runtime; the R2 implementation record tracks components
removed with native login/session replacement and dependencies retired for coherence. Removing generated WebAuthn schemas is
contract cleanup, not a production Java/TypeScript/SQL reduction. Do not use historical tests as
validation of new login, token, SMTP or browser behavior.

## Production baselines

Count tracked production source at the baseline commit using physical `bytes.splitlines()` counts,
including comments/blanks and any unterminated final line. Exclude generated output, build/cache,
Java test/integration/topology source sets, TypeScript `.spec.ts`, all `src/**/testing/` helpers and
`apps/web/src/test-setup.ts`. Include all remaining `apps/web/src/**/*.ts`, not just authentication,
and all main Java and migration SQL. No test moved elsewhere can count as a production reduction.

| Area | Files | Physical lines |
| --- | --- | --- |
| Production Java (`services/sync/src/main/java/**/*.java`) | 127 | 10,839 |
| Production TypeScript (`apps/web/src/**/*.ts`, exclusions above) | 71 | 6,173 |
| Fresh-schema SQL (`services/sync/src/main/resources/db/migration/*.sql`) | 4 | 319 |
| Authentication/operator Java subset of the total | 61 | 7,502 |
| Authentication TypeScript subset of the total | 4 | 666 |

The preliminary analysis reported 75 TypeScript files / 6,439 lines before excluding four test-support
files (266 lines). The production baseline is 71 / 6,173; keep the exclusion consistent at R6.
HTML/SCSS, build/dependency configuration, tests, contracts and documentation are separate categories,
not silently folded into these production-code totals. R6 must report additions and deletions separately
and explain any new custom component replacing framework functionality.

Reproduce without checking out or changing files (run from the repository root):

```shell
python3 - <<'PY'
from pathlib import PurePosixPath
import subprocess

revision = 'ce5c59b'
paths = subprocess.check_output(
    ['git', 'ls-tree', '-r', '--name-only', revision], text=True
).splitlines()
java = [p for p in paths if p.startswith('services/sync/src/main/java/') and p.endswith('.java')]
typescript = [p for p in paths if p.startswith('apps/web/src/') and p.endswith('.ts')
              and not p.endswith('.spec.ts') and '/testing/' not in p
              and PurePosixPath(p).name != 'test-setup.ts']
sql = [p for p in paths if p.startswith('services/sync/src/main/resources/db/migration/')
       and p.endswith('.sql')]
groups = {
    'Production Java': java,
    'Production TypeScript': typescript,
    'Fresh-schema SQL': sql,
    'Authentication/operator Java': [p for p in java if
        p.startswith('services/sync/src/main/java/com/hortinis/sync/authentication/') or
        p.startswith('services/sync/src/main/java/com/hortinis/operator/')],
    'Authentication TypeScript': [p for p in typescript if
        p.startswith('apps/web/src/app/authentication/')],
}
for name, selected in groups.items():
    lines = sum(len(subprocess.check_output(
        ['git', 'show', f'{revision}:{path}']
    ).splitlines()) for path in selected)
    print(f'{name}: {len(selected)} files; {lines} lines')
PY
```

Use the same rules with the final replacement commit for comparison. R1's production totals are
unchanged; no schema/dependency/runtime deletion is authorized in this increment.

## Runtime removal and retention map

Java names below are under `services/sync/src/main/java/com/hortinis/sync/authentication/`, including
`http/` or `persistence/` where named. Remove only as the owning replacement becomes coherent; never
enable both engines or leave incomplete authenticated mode available. Fresh schema is edited directly
when the implementing phase owns it; no compatibility migration or old-route wrapper is needed.

| Existing implementation | Treatment and owner |
| --- | --- |
| `AuthenticationLoginService`, `AuthenticationPublication`, factor/partial-session branches in `AuthenticationSessionService`, `AuthenticationSession`, `AuthenticationIdentity`, `AuthenticationLifetime` | R2: replace password/normal authentication publication with native form login/provider/context and fixation strategy. Remove factor accumulation/step-up/freshness and custom state machine. Keep current account/security-version authorization. |
| `AuthenticationSessionAdmissionPersistence`, `AuthenticationSessionPublicationPersistence`, `SessionAuthorityReference`, completed-session list/count and logout targets | R2/R5: remove bespoke five-session admission, authority references and target APIs. Native principal index/session repository owns lookup/revocation. Keep transaction participation and stale-version fencing; no second session-authority table. |
| `AuthenticationSetupService`, `AuthenticationOperatorRecoveryService`, `AuthenticationOperatorTickets`, `OperatorTicketFiles`, `AuthenticationSetupPersistence`, `AuthenticationOperatorRecoveryPersistence`, `AuthenticationOperatorTicketPersistence` | R3/R4: replace controlled setup with pending-email invitation service and shared action-token store. Delete ticket browser recovery, raw files, file reconciliation and their configuration; never add an operator reset bypass. |
| `AuthenticationAccountService` and `AuthenticationRecoveryCodePersistence` | R4: retain only current-password change/reset account mutations and shared credential/version/revocation boundary. Delete passkey and saved-code management/two-proof recovery. |
| `BoundWebAuthnOperations`, `AuthenticationPasskeys`, `AuthenticationCredential`, `AuthenticationChallengePurpose`, `AuthenticationChallengePersistence` | R2-R5: delete WebAuthn adapter, proof/challenge models and passkey nonce lifecycle as replaced; do not retain challenges as email-login ceremonies. |
| `AuthenticationMutationController`, `AuthenticationReadController`, `AuthenticationRequestBody`, `AuthenticationResponseBody`, `AuthenticationProofBoundary`, `RequestAuthenticationSession` | R2-R5: remove login/logout orchestration and retired DTO/proof parsing; use native filter handlers. Keep only minimal read/action-token/password-change endpoints with closed replacement contracts. No password login controller or custom authentication filter. |
| `AuthenticationLifecycle`, `AuthenticationLifecyclePersistence` | R3-R5: replace application ticket/challenge/code/file cleanup with one bounded action-token/pending-account cleanup. Spring owns session idle cleanup. Keep security-event retention with minimized fixed failure codes. |
| `AuthenticationRuntimeConfiguration`, `AuthenticationSecurityConfiguration`, `AuthenticationSessionConfiguration`, `SessionAttributeCodec`, `AuthenticationSessionTransport`, `AuthenticationObservationService` | R2/R5: simplify wiring/codec/observation for credential-free native account context, native CSRF/logout and minimal status; remove partial ceremony/factor/target serialization and custom session transport behavior. Retain necessary allowlisted JSON, cookie policy and Boot repository extension points. |
| `AuthenticationAccountPersistence`, `AuthenticationAccountSecurityPersistence`, `CurrentAuthenticationAuthorityPersistence`, `AuthenticationNativeSessions`, `AuthenticationSessionRemovalPersistence` and authenticated access resolver | R2/R4: adapt to canonical email, pending/verified status and native principal/version; retain stable scope ownership, ordered transaction checks and all-session revocation. Ordinary requests never select/provision arbitrary scopes. |
| `PasswordPolicy`, `PasswordBlocklist`, `PasswordVerifier`, `AuthenticationPasswordService`, `CryptoWorkLimiter`, `AuthenticationAttemptLimiter`, `AuthenticationSecrets`, fixed exceptions/errors, `AuthenticationPolicyConfiguration`, `AuthenticationSecurityEventPersistence`, `AuthenticationBrowserGuard`, `HeaderOnlyCsrfTokenRequestHandler` | Retain/review password strength, encoder/provider glue, bounded work/limits, established secret primitives, origin/headers, CSRF delegation and minimized events. Adapt email/token limits; delete old factor-specific branches rather than adding parallel helpers. |
| `com/hortinis/operator/HortinisOperatorCommand`, `OperatorCommandOptions`, `resources/authentication/operator-logback.xml` | R4/R5: retain isolated host dispatch/fixed diagnostics, replace ticket arguments with a validated email line on protected standard input for invitation/resend/cancel. Resolve only existing pending accounts for resend/cancel; no UUID discovery. Never output email/token or expose HTTP administrative capability. |
| `apps/web/src/app/authentication/browser-webauthn.adapter.ts` | R5: delete. No replacement cryptographic browser adapter. |
| `authentication-browser.service.ts`, `authentication-http-client.ts`, `authentication-http.port.ts`, `app.ts`, `app.html`, `app.scss`, `security/same-origin-csrf.port.ts`, `sync/authentication-cookie-gate.ts` | R5: replace MFA/setup/recovery-code/credential/session-list views and JSON login with email/password, invitation, reset and change forms plus native logout/CSRF lifecycle. Preserve offline binding/outbox/account matching and tab fences; remove remote logout-target replay. Count shell/template/style work separately. |
| `V4__partition_technical_synchronization_scopes.sql`: `authentication_credential`, `authentication_challenge`, `authentication_operator_ticket`, `authentication_recovery_code`, `spring_session_authority_reference` index | R3-R5: delete obsolete tables/index in fresh schema. Adapt account email/status/verifier/verification/version fields and add minimal purpose-bound action-token storage. Retain technical scope tables, `spring_session`/attributes and minimized security events; no compatibility migrations. R1 SQL stays unchanged. |
| `spring-security-webauthn` and its now-unused transitive WebAuthn/COSE dependencies, old proof fixtures and adapter tests | R5: remove dependency and replace old behavioral tests with new contract/native/token/browser tests. Keep Security, Spring Session JDBC, Password4j/Argon2, password corpus/license and synthetic scope fixtures. R3 alone adds Boot Mail under managed versions/locks/verification metadata. No dependency edit in R1. |

## Review outcomes and evidence ownership

- Mailbox reset is sufficient password recovery and may enable takeover after mailbox compromise or
  reassignment. No password-plus-mailbox-loss recovery, active-email transfer or operator bypass.
- Pending accounts never authenticate or reach identity/sync. Cancellation/expiry frees a reservation,
  never claims/deletes garden data. Case-variant uniqueness and concurrent sole-owner issuance need
  database tests in R2/R4; multi-user provisioning remains S10.
- Action-token completion has one atomic winner; invalid password does not consume, rollback restores,
  committed lost-response retry fails, resend replaces even after SMTP failure. No automatic login.
- SMTP occurs after issuance commit with finite I/O/concurrency/quotas, no queue or retry. Generic
  public reset acknowledgement does not establish timing resistance; R3/R6 must measure/equalize it.
- Native logout removes target machinery and changes offline retry behavior. R5/R6 must prove local
  pause, explicit status resolution, tab fencing and no stale replay against a newer cookie.
- Native inactivity has no absolute deadline/cap; background traffic may sustain a stolen session.
  B01 password-only strength, session risk, invitation lifetime interpretation and deployment/privacy
  readiness remain S15 reviews. R1 validation cannot close them.

## Open review questions and command-contract correction

R1 follow-up corrects host targeting: invite/resend/cancel read a validated email line from protected
standard input. Resend/cancel resolve the unique canonical email to an existing pending account,
preserve stored recipient/scope and reject unknown/active/disabled targets without mutation. This
allows a failed initial send to be retried without discovering a generated account UUID. No command
is implemented or changed at runtime here; R4 owns the behavior and synthetic failure-path tests.

Findings 1/2 remain open as [S9-R Q1/Q2](spring-email-authentication-plan.md#open-questions-required-before-s9-r-completion).
Unknown-address public requests can exhaust the shared delivery budget, and process-local counters do
not enforce shared quotas across isolated CLI processes. These questions must have reviewed decisions
and executable evidence by R6/end of S9-R, before completion or S10 handoff. They are not merely S15
operational assumptions, and R1's static checks do not resolve them.

See the R1 completion record in the replacement plan for commands actually run and review fixes.

## R2 boundary and size evidence — 2026-10-17

The [R2 record](spring-email-authentication-plan.md#r2-implementation-and-review--2026-10-17)
records the native login/session replacement, dependent legacy deletions and validation. Relative to
this document's `ce5c59b` baseline, production Java is 108 files / 6,571 lines (+710/-4,978),
production TypeScript 71 / 6,173 (+1/-1), and schema SQL four / 319 (+12/-12). Tests are separate
(+1,184/-7,232); removing obsolete workflow tests does not count as production reduction. These are
R2 intermediate totals, not the final R6 comparison. Remaining WebAuthn/dependency/ticket/table and
browser deletions still belong to R4/R5. Q1/Q2 remain open with unchanged R6 closure requirements.

## R3 intermediate boundary — 2026-10-17

The [R3 record](spring-email-authentication-plan.md#r3-implementation-and-review--2026-10-17)
adds internal JDBC action tokens, token/pending cleanup, Boot SMTP and provisional bounded mail
admission. It exposes no account workflow or action endpoint. No legacy artifact is deleted in R3:
controlled provisioning still needs R4, and dormant ticket/file/WebAuthn/table/browser removal stays
with its coherent R4/R5 replacement. Q1/Q2 and full response timing remain open; new R3 fixtures do
not inherit validation from historical S8/S9 tests. Final additions/deletions and counts belong to the
R3 evidence record and retain this document's baseline/exclusion rules.
