# Spring email authentication R6 evidence

- Date: 2026-10-19.
- Status: R6/S9-R complete within the validated replacement boundary; production activation closed.
- Start: clean `d46e219`, following R5 `f4d8fa4` and its review fixes.
- Authority: [replacement plan](spring-email-authentication-plan.md),
  [ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md),
  [numeric policy](../architecture/authentication-and-sessions.md#numeric-policy).

## Approved decisions and scope

The user approved the analysis-first R6 plan on 2026-10-19. Q1 changes delivery accounting from
all syntactically valid requests to committed eligible issuance. Unknown/pending/disabled requests
consume transient request admission but no durable delivery quota or SMTP permit. Email cooldown
remains 60 seconds, three/rolling hour and ten/rolling 24 hours across purposes. Reset allocation is
20/hour and 80/day; invitation/resend allocation ten/hour and 20/day, without borrowing. The total
ceiling remains 30/hour and 100/day. Reserving operator capacity trades unused capacity for recovery
availability under reset floods; request exhaustion can still deny callers within that request window.

Q2 moves email/global delivery reservations to PostgreSQL with one durable installation HMAC key.
HTTP and independent host JVMs use the same singleton row lock and issuance transaction. Key creation
uses atomic insert-on-conflict, and each caller reads the committed key under lock. There is no
process-local fallback or account-row cascade. Cancellation/recreation, token consumption and ordinary
restart cannot reset recent accounting. Reservation rollback accompanies ineligible/no-send issuance,
transport-capacity denial and transaction failure. SMTP failure/uncertainty or crash after commit
remains charged. No transaction spans SMTP, no delivery queue/retry or new dependency is introduced. Shared
pre-eligibility storage/clock failures retain fixed 503. Recipient-specific issuance or commit
failures acknowledge 202 after transaction handling, with no delivery or retry; uncertain commit
outcomes may still retain a charged reservation/current unsent token, just like a crash after commit.

These are shared rolling delivery limits, not shared request windows or a distributed two-send
transport semaphore. SMTP remains two calls per process; isolated commands are synchronous. S15
must size operator-induced bursts and retain the single-HTTP-service request-limiter assumption.

Quota lock waits are bounded to one second. A persisted UTC admission high-water mark denies
admission timestamps behind the last committed observation; forward jumps remain a clock-trust risk. Reservation windows exclude timestamps at or
before the exact cutoff. Startup/pre-issuance/minute cleanup removes at most 500 expired rows, without
extending logical restrictions. Idle host-only cleanup runs on the next invocation. An old restored
backup can omit recent sends, so it must remain inaccessible under the existing restore gate and
wait out the 24-hour mail window; ordinary restart is distinct from backup rollback.

The restricted ledger contains only reservation UUID, email HMAC, purpose and issuance time; its
singleton contains the secret HMAC key, installation binding and high-water timestamp. Unknown
recipients leave no committed reservation. Rolled-back database writes, WAL, database pages and
backups remain within the existing restricted-storage/physical-erasure limitations; absence of a
committed row is not a secure-erasure claim. HMAC values are linkable pseudonyms, not anonymous data or a
defense against an administrator holding the key/database. Key retention follows installation life;
reservations logically expire after 24 hours, with bounded physical purge. Logs, browser responses
and business exports contain none of these fields. Backups and complete deletion remain subject to
the privacy inventory and S12/P0.6/S15 boundaries.

## Native ownership and necessary application code

Spring retains UsernamePasswordAuthenticationFilter/formLogin, DaoAuthenticationProvider,
DelegatingPasswordEncoder/Argon2, native context/fixation, HttpSessionCsrfTokenRepository/BREACH,
LogoutFilter, Boot JdbcIndexedSessionRepository and transaction-participating indexed revocation.
Boot Mail/JavaMailSender/SimpleMailMessage remain the only production delivery path. Spring MVC,
TransactionTemplate/JdbcClient, scheduling and database row locks supply infrastructure.

MailIssuancePersistence implements Hortinis rolling accounting, privacy and cancellation semantics;
Spring does not supply this cross-process application policy. ActionTokenDelivery coordinates its
reservation with existing account/token mutations and post-commit delivery. ActionMailAdmission now
contains only the two-send transport permit; its email/global maps, process HMAC key and cleanup are
removed. AuthenticationAdmissionCapacity, previously sharing a key bound between two memory
limiters, is now redundant and removed; the request limiter directly bounds its own map at 4,096.
Existing account mutation, current-authority/scope/version, credential-free JSON codec,
closed input guard, header-only CSRF adapter, fixed errors, browser revision/lease fences and host-only
composition remain application policy glue, not alternative login/session authorities.

The origin guard runs before native session allocation and supplies fixed headers even on early
responses that never reach Spring's header writers. The input guard enforces closed bounds before
Spring trims usernames, without authenticating. Header-only CSRF delegates masking/validation to
Spring. The JSON codec limits persisted native attributes; native-session row locks and REQUIRED
transaction customization preserve protected-operation/revocation ordering and rollback. Audit maps
native events into the minimized vocabulary and thirty-day security store, without subject tracking.

TypeSpec remains authoritative. Public reset 202 now explicitly includes installation delivery quota
and transport-capacity denial, as well as email denial/ineligibility/SMTP failure. Request limits retain
429; account-independent storage/lock failure retains fixed 503. A one-second acknowledgement floor
uses native DeferredResult and Spring scheduling after synchronous account/mail work. Maximum intended
extra padding is one second; scheduler/CPU delay can add latency. Slow SMTP/lock/network tails are not
concealed. No timing-resistance or ASVS ENUMERATION pass is claimed.

## Fresh evidence boundaries

- MailQuotaIntegrationTest: real PostgreSQL rolling windows across purposes/case/independent stores,
  final-slot concurrency, transaction rollback/failure, restart-equivalent reopening, backward clock,
  bounded cleanup and key lifecycle. Direct reservations are synthetic primitive evidence, not SMTP
  attempts or public HTTP measurements.
- R4AccountActionsIntegrationTest R6 cases: complete certificate-verified HTTPS responses, real trusted
  source addresses on loopback, unknown/known floods, exhausted reset allocation, actual isolated host
  JVMs interleaved with HTTP and lifecycle changes, and synthetic local SMTP. SQL aging is explicit
  controlled time simulation, not elapsed host wall time. Primitive seeded global-quota rows are
  identified separately from emitted messages; no production multi-user capability is added.
- R5BrowserIntegrationTest and the production Angular bundle: real shared quota store and native
  HTTP/backend, generic UI including exhausted global allocation, unknown reset requests followed by
  successful local SMTP, plus retained R5 account/offline/tab/fencing regressions. Source-only controls
  reset synthetic rows between independent scenarios; they do not replace shared accounting with
  fresh process counters. No historical S8/S9 tests count as replacement evidence.

Public samples below measure the complete certificate-verified loopback HTTPS request/response with
native CSRF/session/filter/controller/JDBC/mail work. Each branch is an independent parameterized
scenario, with five repetitions (two for SMTP timeout). Synthetic database reservation state is reset
between timing samples so each measures its named branch; request admission remains real. These are
local measurements, not performance bounds, a load study or statistical proof of equal distributions.

| Public reset branch | Samples | Minimum / median / maximum, milliseconds |
| --- | --- | --- |
| SMTP accepted | 5 | 1018.450 / 1020.861 / 1025.504 |
| SMTP refused | 5 | 1011.954 / 1020.801 / 1023.014 |
| Unknown address | 5 | 1013.224 / 1020.148 / 1026.010 |
| Email quota denied | 5 | 1008.381 / 1015.991 / 1023.150 |
| Installation reset allocation exhausted | 5 | 1011.072 / 1017.139 / 1021.315 |
| SMTP greeting/read timeout | 2 | 5024.218 / 5029.784 / 5035.350 |

All returned identical no-store 202 acknowledgement bodies without Retry-After. These final frozen-code backend
samples are distinct from R3's internal primitive/SMTP measurements and its historical unequal timings.
Slow SMTP remains visibly distinguishable: minimum-duration padding does not provide timing resistance.
Known-mailbox attackers can also temporarily consume that email's bounded allowance; no permanent
account lockout is introduced, but recovery availability is not guaranteed under such targeted abuse.
Request/source/global request limits and shared infrastructure load can independently deny callers.
These residuals, scheduler/network tails and operator SMTP sizing remain activation/S15 review items.

The real HTTPS unknown flood admits 40 unknown addresses from two distinct loopback source addresses;
it leaves zero committed delivery reservations, then a legitimate reset reaches local SMTP and a
separate controlled invitation reaches STARTTLS SMTP. The known-address flood sends three reset
messages with explicit synthetic ledger aging, rejects further replacement generically, and preserves
the current token. Global exhaustion is seeded through the real quota component, then observed through
HTTP denial and successful host invitation in its reserved allocation; this is not a claim that 20
public reset emails were delivered. Repeated/concurrent host invocations interleave with HTTP, retain
canonical-email history through cancellation/recreation and activation, and deny active-account host
resend/cancel while public reset remains eligible. No multi-user runtime is enabled.

## Review findings and corrections

- Reviewed quota -> installation/account -> token issuance ordering against cancellation,
  account-before-session revocation and protected scope transactions. Quota cleanup commits before
  account/token cleanup; no account-to-quota lock path or transaction across SMTP is introduced.
- Retained current-version/verified-active/owned-scope transaction checks, one-use/replacement/replay
  and lost-response semantics, credential preparation outside mutation transactions and native session
  revocation rollback. Mailbox possession remains recovery authority, with no active-email retarget,
  operator password/reset bypass, token login or device authority.
- Corrected post-eligibility token-store/commit failure responses so known and unknown recipients
  both acknowledge 202; fixed minimized diagnostics expose no subject/provider detail. Fresh immediate
  and deferred PostgreSQL trigger failures preserve the prior token, roll back reservations and send
  no mail. Shared pre-eligibility storage failures remain 503 for either recipient class.
- Corrected backward-clock failure classification to account-independent 503 rather than request
  throttling; known/unknown HTTP cases verify denial before eligibility. Delivery quota/capacity
  denial remains generic 202. Unknown/rolled-back issuance leaves no committed mail reservation.
- Removed the now-redundant aggregate memory-capacity class and bean; bounded request-map behavior
  remains directly enforced. Updated production admission's fixed diagnostic to require separate
  activation approval, without opening startup or adding any switch.
- Reviewed browser revisions across CSRF bootstrap/connectivity waits, exact-revision completion,
  tab pauses, native delayed cookies, retained outbox/binding provenance, account mismatch and no-auth
  isolation. No production browser behavior is changed in R6.
- Reviewed secret/recipient/token/verifier/key diagnostics, trusted origin/stored mailbox, finite
  SMTP settings, plaintext fixed templates and test-only assembly. All authored content is English;
  no dependency, real SMTP credential/recipient, telemetry or production feature is added.

Early focused runs failed on byte-array map identity and host expectations predating shared cooldowns;
those assertions were corrected. Initial public samples accidentally exhausted actual request limits,
so independent parameterized timing scenarios now retain real gates; the invitation availability
case uses an independent mailbox instead of violating the previous reset's email cooldown. Static
findings were corrected, not suppressed. One early browser run failed at the second tab's initial
sign-out observation; a diagnostic rerun passed, and a readiness wait is scoped to the affected initial-tab setup. A trial fixture-wide
response wait timed out and was removed; minimized phase diagnostics preserve investigation context.
The final focused and full-suite browser runs pass all 25 scenarios. Failed/superseded runs are not counted as final passes.

## Review, validation, counts and handoff

Final frozen-code `pnpm validate` passed: eight documentation checks, 21 contract tests and exact
TypeSpec regeneration/schema/OpenAPI checks; ten web architecture tests, 381 frontend unit tests,
11 standard Chromium tests, development/production builds; wrapper/toolchain and backend quality/build
checks, 246 Java unit tests and 268 PostgreSQL integration tests; the 25-scenario real
Angular/native Spring/PostgreSQL browser fixture; seven topology-runner tests and all six real offline
Docker topology scenarios; whitespace/tracked-ignore/text/EOL checks. The build verifies both backend
archives/runtime and the frontend bundle exclude retired code and synthetic assemblies, including R6.
Focused strict dependency-verification privacy/store/HTTP/host and Java static checks passed.
`pnpm compose:validate` passed with transient synthetic no-auth configuration. Changed Java sources
have no Checkstyle warnings and PMD has zero violations; existing baseline warnings remain.

The first full run passed all then-current 264 integration cases but stopped on late-formatting/
duplicate-literal findings. A subsequent run was deliberately interrupted for the recipient-specific
storage-failure correction. Both are superseded by the successful frozen-code run, not counted as
passes. Shared admission failures return fixed 503; fresh known/unknown immediate/deferred token-store
failures now preserve generic 202 without delivery or retry. The final review found no remaining R6
acceptance blocker. Q1/Q2 close with actual enforcement/evidence; ASVS verification and production
readiness are not claimed.

Normal authenticated production startup stays closed with no configurable bypass. Retired ticket/
recovery commands remain unavailable. No S10, device enrollment, email change or sensitive-transition
feature is authorized. Password-only/mailbox recovery strength, session lifetime/cap appropriateness,
SMTP transport/provider/privacy operations, deletion/restore, B01-B05, D01-D03 and applicable foundation
release work remain separate gates. Activation requires a separate explicit approval after concrete
evidence is reviewable. Only synthetic local SMTP/TLS/databases are used; no real mail, credentials,
push or deployment.

## Change counts

Physical lines include blanks/comments under the [R1 rules](spring-email-authentication-r1-inventory.md).
Production totals exclude generated contracts, Java test/integration/topology sources, TypeScript
spec/testing helpers and test setup. Git additions/deletions are reported independently; no tests
moved into production and deleting/replacing tests is not production reduction.

| Category | R6 additions | R6 deletions |
| --- | --- | --- |
| Production Java | 278 | 193 |
| Production TypeScript | 0 | 0 |
| Fresh-schema SQL | 16 | 0 |
| HTML | 0 | 0 |
| SCSS | 0 | 0 |
| Dependencies, locks and verification metadata | 0 | 0 |
| Build/isolation metadata | 2 | 0 |
| Tests and fixtures | 888 | 265 |
| TypeSpec and generated contracts | 2 | 2 |
| Documentation | 506 | 113 |

Final production totals: 116 Java files / 7,046 lines, 72 TypeScript / 6,127 and four SQL / 297.
Against R1 `ce5c59b`: Java +2,288/-6,081 (net -3,793), TypeScript +650/-696
(net -46), SQL +43/-65 (net -22). R6 introduces necessary shared policy/persistence; its production
change need not be a net reduction. The cumulative replacement removes the retired engine; no
reduction is attributed to test deletion or movement.
