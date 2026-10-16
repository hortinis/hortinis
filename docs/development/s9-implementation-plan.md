# S9 implementation plan

Later framework delegation decisions in the [Spring authentication plan](spring-authentication-delegation-plan.md)
supersede the historical next-access cleanup and controlled-cookie implementation below. Native
session expiry/deletion uses Spring Session; application records retain scheduled cleanup.

## Analysis and boundaries

S8 is committed and validated as design in ADR-0033 and the authentication/session specification.
S9 must implement the complete selected single-user authentication boundary, rather than activating a
password-only subset. Preserve existing synthetic no-auth behavior and its S7 topology fixture. The
accepted application context already distinguishes authenticated authority/account identity, and wire
bootstrap/local binding can represent it. Runtime mode initialization, scope persistence and HTTP
security currently reject authenticated operation; extend those trusted boundaries without changing
record ownership, outbox identity or scope partitioning.

Use fresh frontend/backend databases. Extend the fresh-install SQL schema and, where required, fresh
Dexie schemas; do not backfill ownership or upgrade populated state. Fresh authenticated setup owns the
explicit empty sole scope only. Claiming an existing no-auth scope, multi-user provisioning, device
enrollment and mode/account/server transitions remain S10-S12; their unimplemented paths deny.

Implement within the existing Boot application by authentication feature and supporting persistence/
HTTP components. Framework objects, credential parsing and provider calls stay in adapters. Current
account/session authority must be checked inside the protected service transaction, before scoped
resource lookup; a login result or saved session principal is insufficient. Reuse trusted context
construction and explicit account-to-scope ownership; no request-selected account/scope grants access.

## Implementation sequence

1. **Contract first.** Add closed TypeSpec/OpenAPI authentication, CSRF/session status, setup, password/
   passkey login, step-up, credential changes, restricted recovery and protected logout contracts.
   Separate public compatibility from authorized identity. Define bounded WebAuthn options and binary
   envelopes, error/no-store responses, cookie security and required CSRF headers. Add contract fixtures
   and regenerate schemas/OpenAPI before HTTP/browser adapters. Keep session secrets out of JSON/local
   metadata; non-secret logout targets remain distinct, non-authorizing references.
2. **Dependencies and reference data.** Add Boot-managed Security/Session JDBC and framework WebAuthn/
   Password4j dependencies; inspect actual APIs/transitives, pin otherwise unmanaged libraries and lock
   the graph. Obtain a reviewed licensed, version-pinned local common/breached-password corpus, retain
   provenance/hash/license and at least 3,000 policy-compatible common entries. Use hashed reference
   entries where practical; never commit active credentials or fetch a password/prefix at runtime.
   Verify complete corpus integrity, count, policy coverage and offline availability before auth startup.
3. **Fresh persistence and policies.** Add explicit account/sole-scope ownership, verifier/public-key,
   limited ceremony/challenge/recovery/ticket state, authoritative session epoch/target policy and
   established Spring Session JDBC tables. Store only allowlisted session principal fields, not a
   credential verifier or arbitrary object graph. Implement trusted-clock policy, independent CSPRNG
   session/secret generation, one-use secret comparison/consumption, bounded limiter/crypto work and
   immediate invalidation plus scheduled/startup cleanup. Add a protected minimal security-event store
   with the accepted 30-day purge. Test exact expiry, rollback/restart and capacity boundaries.
4. **Controlled setup and security adapter.** Add a host-only setup/recovery command that exclusively
   writes target-bound tickets into the protected managed secret directory, never logs or takes secrets
   as arguments, and cleans consumed/expired files safely. Fresh auth starts denied/pending setup until
   the intended owner supplies the ticket, selects a compliant password and proves a user-verified
   passkey. No public first-user claim/default password. Use established password/WebAuthn authentication
   and factor-aware authorization; partial/setup/recovery sessions grant no data authority. Implement
   fresh login, step-up, password/passkey changes and both selected two-proof recovery paths with
   real verification, same-account/session binding, single-use challenges/codes and session rotation.
5. **Current access and HTTP defense.** Extend explicit mode initialization/resolution for only
   single-user-auth, rejecting multi-user and direct mode changes. Resolve current full session and
   account ownership into internal contexts; scope persistence rechecks current epoch/session expiry/
   account status with account/session/scope locks inside every protected transaction. Serialize logout,
   disablement and credential changes against acceptance. Preserve S4 Origin controls; add framework
   session-backed CSRF to every unsafe authenticated ceremony/data route, JSON filter errors, no-store,
   strict Secure/HttpOnly/host cookie, independent secret IDs and controlled cookie-setting workflow.
   Logout/stale data must emit no session Set-Cookie. Enforce HTTPS RP/public-origin, bounded route-specific
   parsing and security headers without relaxing technical sync input bounds or trusting raw forwarding.
   Suppress generated framework users, secret logs and unused bearer/remember-me/OIDC paths.
6. **Offline browser workflow.** Add a small explicit account/setup/login/recovery UI and a replaceable
   browser WebAuthn adapter; keep private keys/PINs/verifiers/session secrets outside script persistence.
   CSRF stays in memory and refreshes only through the coordinated explicit workflow. Authentication
   pauses/resumes existing binding/outbox only after the authorized retained destination matches.
   Online/logout failure and expiry fence responses, preserve offline edits/frozen bodies and stop new
   exchange. Implement the minimum durable pending-logout/target and all-tab cookie workflow necessary
   for safe S9 operation; S12 still owns wider transitions and interruption coverage. Background sync
   never extends qualifying user inactivity. Initial standalone startup makes no login/server requirement.
7. **Executable review and topology.** Test actual hashing and WebAuthn verification, generic failure/
   timing work, CSRF/Origin/simple/preflight, fixation/rotation/concurrency/expiry/cleanup, wrong RP/account/
   challenge/recovery target, replay, interrupted setup and concurrent secret consumption. Use real JDBC
   ordering tests and capture diagnostic canaries. Add disposable loopback HTTPS browser topology using
   actual virtual authenticators and production authority: controlled setup, both-factor login, explicit
   empty binding, sync, logout/expiry, offline edits, same-account reauthentication/replay and restart.
   Keep the no-auth A1/A2/B fixture absent from production and run all earlier scenarios unchanged.
8. **Finish S9.** Review application/framework/persistence/cookie boundaries and English/contract/privacy
   consistency, fix all findings, update reproducible operator/development/threat/lifecycle evidence,
   run pnpm validate and commit the completed step. The user requested handback after S9; do not begin
   S10 in this task.

## Review and release limits

S9 supplies authenticated single-user technical behavior with synthetic tests. No production personal-
data trial, ASVS certification, external OIDC activation, device enrollment, mode claim/downgrade,
account/server switching, complete erasure/restore or populated-server reconciliation is implied.
Unknown/configuration-inconsistent authority fails closed; no fallback or fixture selector is added.
S10-S15, P0.6 and applicable foundation/deployment gates retain their accepted work. Record failures
and actual residual limitations in the step evidence, not as implicit relaxations of S8 policy.


## Implementation evidence in progress

- Authentication TypeSpec/OpenAPI/schema checks pass (20 tests). Contract handoff explicitly records
  runtime activation as pending; no public account/scope selector or password-only authority exists.
- Authentication dependencies and lock/verification metadata are resolved. All 54 newly added Maven
  artifact/metadata hashes were cross-checked against primary Maven checksums. Boot-managed Security
  7.1.1/Session 4.1.1 use WebAuthn4j 0.31.9.RELEASE; Password4j is explicitly pinned to 1.8.4.
- The bundled MIT-licensed SecLists/Xato-derived corpus pins revision/source/license/artifact digests,
  contains 10,000 eligible exact-text reference hashes, and performs no runtime network lookup.
  Complete artifact integrity and missing/corrupt-data denial are tested.
- Framework-independent password/scalar/secret/lifetime/admission policies and actual Spring Argon2id
  verification are implemented and tested. Context comparison lowercases text then retains only ASCII letters and digits
  for identifying login-name/`hortinis` derivatives. Login comparisons with fewer than three compact
  characters reject only the entire digit-decorated derivative, avoiding arbitrary short-substring
  rejection. Password verification and reference digest comparison preserve exact received Unicode text. Explicit salt is 32 random bytes.
- Default generated users and automatic JDBC session activation are excluded. The servlet chain preserves
  explicit no-auth behavior; authenticated startup remains closed. Unit/quality checks and all 68 existing
  PostgreSQL integration tests passed with the preliminary fresh-install authentication/session tables.
- Passkey source review found that preferred UV must be overridden and that the stock relying-party
  adapter reconstructs authentication state from stored attestation. The S9 adapter must use required UV,
  current persisted counters/backup state and a minimized public-key record; retain no attestation or
  authenticator model. Verification must cover both zero counters and non-increasing nonzero counters.

These intermediate results do not complete S9. Protected persistence services, host tickets, complete
factor/recovery workflows, authoritative session checks, CSRF/cookie coordination, browser UI and the
actual HTTPS authenticated topology remain to be implemented and reviewed before activation/commit.

- Closed JDBC attribute encoding and the controlled strict cookie resolver are tested, including real
  Spring JDBC rotation. Only CSRF and a non-authorizing row reference are serializable; default Java
  object deserialization/principal graphs and stable principal-name storage are excluded. Cookie writes
  require trusted explicit workflow authorization. Logout/expiry/data routes cannot mutate the cookie.
- Protected host-ticket files require an existing managed 0700 directory, secure relative POSIX
  traversal, exclusive 0600 creation and matching directory/file ownership. Filesystem tests cover
  missing/open directories, ancestor/final symlinks, unrelated files, cleanup and concurrent issuance.
  The host command and transactional ticket lifecycle are still pending. File cleanup must share the
  exclusive installation lock with issuance, so a snapshot cannot erase an uncommitted new ticket.
- Internal authenticated contexts now carry a non-secret session reference and observed authority
  epoch. Scope persistence rechecks current account, session, lifetime, ownership and committed mode
  before scope reads/publication, with ordered account/session locks held through the caller transaction.
  Synchronization reads use a writable transaction because PostgreSQL rejects locking reads in a
  read-only transaction. All 138 backend unit tests and 82 PostgreSQL tests passed; a test observes the
  actual revocation lock wait and denies the retained observation after revocation commits. The only
  static-review finding in that run was a duplicate fixture instant, corrected afterward.


## Challenge lifecycle implementation analysis

The cryptographic adapter intentionally cannot consume server challenges. Add a dedicated JDBC
component that derives account ownership from the current session row, never from a public account
selector. Issuance requires a caller transaction, locks account before session, binds a closed purpose
to the permitted current ceremony, and serializes the eight-live-challenge account limit. Replace the
one existing challenge for that ceremony; its expiry is the earlier of five minutes and the parent
session deadline. Credential enrollment additionally requires recent complete factors.

Consumption commits its deletion in a separate transaction before invoking proof verification.
Reject calling consumption from an existing transaction: suspending a transaction that already locks
the account/session could deadlock and later rollback must never restore a spent challenge. Lock the
account before session, delete even a wrong-purpose/expired challenge, and report generic failure only
after that transaction commits. The proof service must recheck current authority and captured epoch
in its later publication transaction; a consumed challenge is not a garden-authorized identity.

Verify with fresh PostgreSQL tests for actual concurrent consumers and issuance at capacity, expiry
boundaries, purpose/state mismatch, replacement, current revocation, and proof/publication rollback
that cannot resurrect the challenge. Full HTTP/runtime activation remains pending.


## Recovery-code lifecycle implementation analysis

Implement replacement only behind current full authority and recent complete factors. Lock the
account exclusively before checking the current session and scope; use current persisted factor
time rather than a retained session observation. Replace all ten verifiers in the same caller
transaction and return independent random codes once through a redacted immutable internal result.
An account/session observation must never authorize replacement after disablement, epoch change,
revocation, expiry or lost recent-authentication status.

Recovery consumption is a persistence operation in the final recovery publication transaction,
after the service verifies the independent password or user-verified passkey. It checks the current
active account epoch under its exclusive lock, removes expired verifiers on access, compares the
fixed bounded verifier set through the JDK constant-time primitive, and deletes the selected code
atomically with the factor replacement. A code alone never creates a full session. Test real
concurrent consumers, rollback semantics, replacement invalidation, expiry equality and stale
replacement authority. Initial enrollment code creation will belong to the controlled atomic account
activation component, so this replacement API cannot become an unguarded initialization bypass.

The reviewed challenge implementation passed focused unit/HTTP-default tests, all 27 fresh
PostgreSQL authentication integration cases and PMD for main/unit/integration sources. Checkstyle
has only its 57 existing scaffold warnings. The updated contract suite has 20 passing cases.


## Anonymous-session admission implementation analysis

Before installing the public CSRF adapter, implement a bounded non-authorizing authority-row allocator.
Only an explicit caller transaction in committed `single-user-auth` mode may admit a fresh framework-
generated 32-byte session ID. Retain its verifier, not the cookie secret. Serialize admission with the
installation lock and count every unexpired non-full session, regardless of its partial state; the
1,001st ceremony denies without evicting an existing ceremony. Anonymous allocation binds no account,
scope or factor and expires exactly five minutes after the trusted allocation time.

Remove expired unowned anonymous authority rows and their corresponding closed Spring JDBC transport
rows on this access path. Scoped partial/full cleanup and periodic/startup cleanup remain the separate
lifecycle adapter; never delete account-bound sessions without ordered account/session locks. Test the
production 1,000 limit with actual concurrent requests at the last slot, all partial states contributing
to capacity, expiry equality, duplicate ID denial without replacement, transaction rollback, mode
mismatch and deletion of matching transport attributes without cookie mutation.

Recovery-code verification now passes all 42 fresh PostgreSQL authentication cases and the main/unit/
integration static checks, with only the existing 57 scaffold Checkstyle warnings.


## Reviewed progress validation

The complete documented `pnpm validate` passed for this intermediate S9 implementation: 20 contract
cases, 345 frontend unit tests, 11 standard Chromium scenarios, 163 backend unit tests, 115 fresh
PostgreSQL tests, six real synchronization topology scenarios, nine frontend architecture tests and
seven topology-runner tests. The first attempt reached the backend launcher but encountered a reused
Gradle daemon checksum-cache lock error. A fresh normal-cache process passed; the successful full run
used the documented `org.gradle.daemon=false` JVM property through `GRADLE_OPTS`, without deleting
caches or changing the repository validation runner.

Passkey signed-proof tests cover all selected algorithms, current counters and backup behavior,
minimized public-only key storage, cross-origin/duplicate/wrong-type client data, unsupported key
profiles and canonical HTTPS default-port configuration. The 47 authentication PostgreSQL cases cover
schema/native JDBC session encoding, current protected authority, committed challenge consumption,
recovery replacement/consumption and real 1,000-session anonymous admission limits. New test formatting
warnings found by the full build are corrected without suppressions and rechecked separately.

This run validates the existing no-auth/synthetic topology and implemented private components. It does
not demonstrate an authenticated HTTP/browser flow or complete S9. Controlled account activation and
host commands, complete login/step-up/recovery/session workflows, active session/CSRF/request adapters,
physical scheduled/startup cleanup, protected security events, browser coordination/UI and actual
HTTPS authentication topology remain required before authenticated activation or the S9 commit.


## JDBC session transaction implementation analysis

Configure the established Spring JDBC repository with the same datasource transaction manager and
`PROPAGATION_REQUIRED`, immediate attribute writes, independent 32-byte IDs, the closed attribute
codec and an empty principal index. Its transport timeout is twelve hours; partial workflows set
five minutes inside their admission transaction. Authoritative rows enforce explicit-interaction
idle expiry and the original full-login deadline, independent of framework request-access timestamps.
Disable the repository's independent cleanup scheduler so the ordered authority lifecycle adapter
will own cleanup. Keep this factory inactive until the complete authentication HTTP boundary exists.

Source review confirms immediate creation/attribute writes, but `changeSessionId()` only marks the
transport dirty. Rotation must explicitly save before its authority publication transaction commits.
Test real PostgreSQL creation, attribute changes, rotation and deletion with their corresponding
authority rows, including rollback. A rolled-back in-memory session may still contain a new ID or
reference; it must not authorize a cookie write, and later framework saves must not recreate deleted
transport or authority. Test that stale save boundary and rejected arbitrary attributes. Cookie
release authorization remains the later HTTP workflow's responsibility after successful commit.


## Ordered physical lifecycle implementation analysis

Add a private JDBC lifecycle adapter before startup/scheduling activation. Each sweep operates
without an enclosing transaction: unowned ceremonies and setup tickets take the installation lock;
each account takes its exclusive account lock before locking session rows in reference order.
Delete unusable parents and their matching native transports together, with FK cascades removing
challenges and claimed tickets. Valid parents retain their original interaction and factor times.
Purge expired/future challenges, target-inconsistent or expired tickets, expired/future recovery-code
verifiers and orphan native transports. Current account epoch/status, committed mode/instance and
exact full idle/absolute or partial deadline determine parent usability, not framework access time.

Use small independent transactions per account so no sweep holds every account lock. Orphan transport
removal uses both its closed authority-reference encoding and the digest of its cookie ID; a live
reference attached to the wrong ID cannot retain unrelated transport. Atomic admission/rotation
prevents a sweep from seeing half-published live transport. Security events expire at the exact
30-day boundary using fixed trusted-clock duration. Startup-before-traffic, the maximum 60-second
scheduler and host-file reconciliation remain the later runtime adapter; this private sweep alone
does not satisfy those release requirements. Test all exact boundaries, preservation of current
authority, cascades, repeated/restart-style sweeps, mode denial, rollback and protection-lock waits.


## Controlled host-ticket issuance implementation analysis

Implement the private host capability and its dedicated JDBC ticket adapter before adding a command
entry point. It receives the already validated protected ticket directory, generates a secret in
memory and writes it exclusively; it never returns a secret, accepts one as a command argument or
records one in diagnostics. Database publication stores only its digest and exact purpose, target,
installation and committed single-user-auth mode. Setup targets the current installation and denies
any account, additional scope, published sequence or retained technical synchronization state.
Operator recovery targets the sole active account with explicit sole-scope ownership. It does not
choose a password, change factors, invalidate a current session or grant garden access.

Take the installation lock before account/scope locks for host issuance. Serialize replacement of
the one ticket per target and reset any old claim. Create the protected file before database commit;
a file failure rolls the ticket transaction back. A database failure/crash can leave a file, but
subsequent reconciliation removes every file without a current matching committed ticket. Reconcile
under the same installation lock only after issuance finishes, so it cannot delete an uncommitted
new file. Remove expired/future or instance/mode-inconsistent ticket rows and their files; preserve
unrelated files by failing the protected directory validation rather than traversing arbitrary names.
Test concurrent issue/replacement, verifier-only storage, exact expiry, hostile directory/file state,
publication rollback and orphan reconciliation. Browser ticket claiming and final factor activation
remain separate controlled workflows; the command/runtime adapters remain pending.


## Protected security-event implementation analysis

Add a dedicated writer accepting only the seven selected event kinds and closed outcome/reason
codes. It generates a fresh unrelated request reference and reads trusted UTC internally; no
account/session/login/address, exception, body or free-text parameter is accepted. Require a writable
caller transaction so successful events commit or roll back with authority publication. Denied
workflows must append in their own committed failure-reporting transaction after proof consumption;
throwing a failure from an enclosing publication transaction must not be mistaken for retained audit
evidence. Validate outcome/reason compatibility before writing. Existing ordered lifecycle tests
cover exact 30-day retention; verify actual persisted closed fields, rollback, invalid combinations
and transaction boundaries. HTTP/service workflow integration and startup/daily purge activation
remain required before authentication activation.


## Authority rotation review requirement

Every cookie/CSRF rotation must also replace its non-secret authoritative session reference. A
request may have resolved the former cookie before rotation and wait before entering its protected
transaction. Reusing that row reference could let its retained account/epoch observation become
current again after step-up completes. A fresh reference makes the protected transaction reject
the predecessor even when account and authority epoch are unchanged. Real JDBC rotation tests now
cover cookie, CSRF, row-reference and verifier commit/rollback together; an explicit retained-request
test checks the same-account/epoch rejection. Production factor workflows must create the replacement
row and retire the predecessor atomically, transferring any controlled ticket claim before FK cleanup
where needed; these transport tests do not activate a factor workflow.


## Servlet session transport implementation analysis

Review of Spring Session 4.1.1 confirms immediate transport invalidation and a final response-time
save. Request/session wrappers delegate attribute and inactivity changes to the configured immediate
JDBC repository, whereas ID rotation itself only marks it dirty. Add a request-scoped concrete HTTP
adapter: create a transport only in the explicit CSRF-bootstrap publication transaction; attach a
non-secret authority reference and independent CSRF value; rotate ID, reference and CSRF together
inside a writable transaction; invalidate immediately inside that transaction. A changed attribute
flushes the rotated ID before authority publication commits. Reuse the established session-backed
CSRF repository's selected header/parameter, with independent 32-byte random token generation.

Register cookie release with Spring's after-commit synchronization, never before transaction success.
The callback checks the current request still holds that exact transport ID/reference; rollback,
invalidation or a later rotation cannot release an obsolete cookie. Require a fresh authority
reference on every rotation, admit only the selected five-minute/twelve-hour transport intervals,
and expose only verifier/reference observations to application services. No servlet filter/runtime
mode is activated by this adapter. Test the actual Spring Session servlet wrapper backed by PostgreSQL:
creation and rotation inside publication, rollback and stale response saves, exact strict cookie
attributes, CSRF replacement, immediate invalidation and absence of cookie writes on logout/data.


Servlet adapter review tightened cookie release further: authorization records the exact committed
transport identifier instead of a Boolean. If a later mutation in the same request rolls back, an
earlier commit callback cannot authorize its changed, uncommitted in-memory ID. The resolver compares
the final identifier to that committed snapshot before emitting a header; a unit regression covers
the mismatch. This request-only secret snapshot is neither serialized nor retained in local metadata.


## Reviewed lifecycle and transport progress

A complete backend regression passed with 163 unit cases and 157 PostgreSQL cases after the ordered
lifecycle sweep, protected host-ticket issuance/reconciliation and closed security-event writer.
That run covers all earlier no-auth/synthetic backend behavior; newly introduced formatting warnings
were corrected separately. The additional fresh-reference regression and reviewed JDBC rotation
cases pass all 54 authentication-schema cases and their quality checks.

Ten further PostgreSQL cases now exercise the actual Spring servlet session wrappers, including
commit-only strict cookie release, rolled-back bootstrap, reference/ID/CSRF rotation and rollback,
a later rollback after an earlier commit in the same request, immediate logout invalidation without
cookie deletion, CSRF refresh without authority renewal, forbidden data-route mutation, no
unbounded deferred-CSRF allocation, and writable/fresh-reference/transport-lifetime guards. An
initial test probe wrote a cookie to a separate response, triggering Spring's duplicate-write
suppression; the corrected test probes denial before commit and verifies the actual final response.
Test-only duplicate-method literals found by PMD are corrected without suppressions.

These components still install no authenticated runtime mode or factor workflow. Host command entry
points, ticket claiming/account activation, login/step-up/recovery/credential/session services, active
request/CSRF/security adapters and lifecycle scheduling, browser UI/coordination and authenticated
HTTPS topology remain required before the S9 completion review and commit.


The complete backend suite now passes 164 unit tests and 168 PostgreSQL cases with the reviewed
servlet transport. Main/unit/integration static checks contain no new authentication warnings; only
the pre-existing scaffold warnings remain. Documentation validation and whitespace checks pass.

## Verified passkey publication implementation analysis

Application workflows will verify cryptographic proofs outside their publication transaction, then
recheck current account/session/epoch and challenge expiry under ordered locks before updating
credential counters or granting authority. Add non-constructible verified registration/assertion
results inside the existing relying-party adapter. Bind each result to the verified server challenge,
configured origin, same-account user handle, minimized public-only credential and selected algorithm.
Keep registration distinct from assertion: creating a credential never substitutes for normal login.
Copy all byte-bearing result values and redact diagnostics. Existing framework methods retain their
behavior; the assertion callback has no automatic transaction guarantee and the new workflow path
returns proof without invoking a publication callback.

Persistence must compare the proof challenge to the consumed receipt, its handle/key/credential to
current locked ownership, and its epoch/lifetime to current state. Non-backup counters are rechecked
after locking to reject two proofs verified concurrently against an older snapshot; backup-capable
counters retain the maximum verified/current value. A verified proof or consumed receipt alone is
not a full session. Test actual signed proofs for both result kinds, defensive copies, no callback
publication on the new verification path and generic failure without a result. These results enable
the upcoming complete account activation/login/recovery services; they install no HTTP endpoint.


## Account creation and login publication analysis

Complete the first usable factor workflows behind the existing inactive boundary. Controlled setup
claims a current installation-bound host ticket exactly once, stages only a compliant password hash
and independent user handle in a rotated five-minute ceremony, then activates the sole empty scope's
account only after verified resident user-verified registration. Recheck empty ownership, ticket
claim/expiry, consumed nonce, configured origin, parent deadline and staged handle in the activation
transaction. Create the first credential and ten recovery codes atomically, consume the ticket and
invalidate the setup transport. Setup never grants full authority: both-factor normal login follows.

Password verification uses a minimized account snapshot outside the transaction, with equivalent
dummy hashing for unknown users. Publication locks the active account exclusively before its parent
session, rechecks epoch/password/handle/ownership and rotates into password-only authority. A normal
login begins from anonymous authority; step-up begins from current full authority and retains its
original login deadline. Final assertion publication rechecks the consumed purpose/nonce/expiry,
current credential ownership/key/backup eligibility and counter under the same ordered locks.
Serialize the five-current-full-session ceiling there; concurrent sixth logins deny without eviction.
Rotate cookie, CSRF and authoritative reference together on every transition, retaining the original
twelve-hour deadline for step-up. Crypto never runs while publication locks are held.

Use fresh PostgreSQL tests with real ephemeral signed passkeys and the established password verifier,
including setup replay/wrong ticket, incomplete proof, changed epoch/password/credential, concurrent
old counter snapshots, cap races, rollback and original-deadline step-up. Success security events
commit with publication; failure events commit independently after challenge consumption. These
services will then be wired to the contract-first HTTP adapter; authentication stays startup-denied
until the remaining recovery/credential/session and browser/HTTPS boundaries are ready.

The reviewed typed passkey results pass 23 actual signed-proof tests. The last field-naming PMD
finding is corrected without suppressions, and main/unit PMD passes.


## Reviewed account workflow progress

Controlled setup, normal password-plus-passkey login and recent-authentication step-up now have
concrete service entry points backed by real Argon2id, verified WebAuthn and the JDBC servlet transport.
Setup consumes its exact claimed host ticket, creates sole-scope ownership, its first public credential
and ten verifier-only recovery codes atomically, then invalidates setup without granting garden
authority. A same-cookie ticket owner can replace interrupted setup options with a fresh nonce, cookie,
CSRF and row reference while retaining the original five-minute creation/deadline and WebAuthn handle.
Another ceremony cannot take the claim. This supports reload/restart inside the original bounded
ceremony; expiry still requires a newly controlled ticket.

Password verification occurs before publication locks. Installation admission serializes partial
capacity, followed by current account and parent locks; a retained password snapshot is compared again
before publishing partial authority. Verified assertion publication checks the spent nonce/purpose,
origin, handle, current key/backup eligibility and counter. Two assertions verified concurrently
against an older counter cannot both publish for a non-backup credential. The sixth concurrent full
login denies without eviction. Step-up retains the original full-login deadline and can neither
outlive that deadline nor allocate the 1,001st partial ceremony.

The first complete backend regression passed 167 unit cases and 186 PostgreSQL cases. Subsequent
review added deadline checks immediately before session publication and rejected future authority
creation/factor timestamps in both observation and the protected transaction. The reviewed 56
authentication-schema cases pass, and the expanded 22 real password/signed-passkey account-workflow
cases pass. They include committed denied events, deferred activation failure, one-use challenges,
wrong/replaced tickets, setup interruption, revoked/expired/current-factor state, cap concurrency,
step-up deadlines and publication expiry after an earlier lock. Review also corrected SQL line length
and test declaration-distance warnings without suppressions.

These entry points remain private to the unfinished application boundary. No authenticated HTTP
controller, selected runtime mode, host command, browser UI or HTTPS authentication topology has been
activated. Remaining credential/session/recovery services, header-only CSRF/Origin/parsing/rate adapters,
startup/scheduled/on-access lifecycle, browser cookie coordination and the HTTPS topology still belong
to S9 and must pass before its complete review, validation and commit.


The final reviewed backend regression passes all 167 unit tests and 192 fresh PostgreSQL cases,
with zero failures/errors/skips. Main/unit/integration PMD passes; authentication sources add no
Checkstyle warnings. The existing 57 scaffold main warnings and one existing unit fixture-package
warning remain. Documentation validation (six tests plus links/anchors/ADR index) and whitespace
checks pass. This is backend service evidence, not an authenticated HTTP/browser topology claim.

## Next credential and recovery workflow analysis

Use the same current account-before-parent publication locks for protected password/passkey and code
changes. Verify a supplied current password outside the transaction, then compare the exact snapshot
and require current full authority with recent normal factors before changing it. Additional
registration consumes its challenge before parsing/crypto, checks recent authority again at final
publication and enforces the eight-key bound there. Credential removal must target the same account
and preserve at least one verified usable key. Changed factors invalidate old full/partial authority
and incompatible tickets atomically; preserve synchronization records, receipts and binding identity.

Recovery needs an explicit distinction between a pending proof and a two-proof restricted replacement
ceremony. Forgotten-password options retain only the candidate code digest in the server challenge;
actual same-account UV assertion and unused-code deletion commit together before reset-only authority.
Lost-passkey password/code proof consumes the code before issuing replacement options; interrupted
replacement never restores it. Verify and insert the new key before removing the selected owned key.
Operator replacement claims the exact current host ticket and requires both a compliant new password
and verified registration before changing factors; no path upgrades recovery into data authority.
Keep every restricted deadline fixed and recheck consumed receipts, current epoch/key ownership and
expiry at publication. Add actual concurrency/rollback/restart tests alongside these service paths.

Session listing/revocation and explicit activity must derive ownership from current full authority.
Activity alone can refresh qualifying inactivity, preserving absolute expiry. Targeted logout must
compare the original non-secret target under current locks, complete without a full target, and
reject a different current full target without touching it or emitting a cookie. Wire these completed
services only after header-only CSRF, exact Origin/Fetch Metadata, closed bounded parsing, admission
limits and physical lifecycle adapters are ready; browser coordination and HTTPS proof remain gates.


## Reviewed credential and session controls

The expanded account workflow suite passes 44 PostgreSQL cases, including a real protected
transaction holding account authority while password revocation waits, final registration after
recent factors expire, and target-bound logout without cookie deletion. Credential changes increment
the account epoch and remove old full/limited/native sessions and incompatible tickets atomically.
Additional registration enforces the final eight-key ceiling; owned removal preserves the last key.
Recovery-code regeneration also invalidates already authorized restricted ceremonies. Session listing
is minimized; explicit activity changes only idle interaction time. Targeted logout rejects a different
current full target and remains usable for expired, disabled or missing authority. Review fixed the
activity clock check and test static-check findings; complete backend regression will follow recovery
implementation. These are private service tests, with HTTP/browser activation still unfinished.

## Restricted recovery implementation plan

Forgotten-password options will create the same account-independent pending recovery ceremony for
every validly shaped login/code request, without looking up the account or checking its codes. Retain
only the canonical requested login and a code digest in server-owned pending state/challenge. The
assertion step resolves the account, verifies its current user-verified passkey outside publication
locks, then locks installation, account and parent and checks nonce/purpose/origin/ownership/epoch.
Atomic code consumption and a fresh restricted reset reference commit together. Reuse the original
five-minute creation/deadline; failed reset or reload cannot restore the consumed code. Accountless
recovery is permitted only for this unproved password-recovery state, never protected authority.

Lost-passkey recovery verifies the current password outside locks, rechecks its exact current
snapshot, consumes an unused code and rotates into a replacement-only ceremony before showing owned
credential choices. Selecting an owned lost key issues a fresh registration challenge within the
original deadline. Verify a new key before deleting the selected old key, allowing a transient ninth
row inside the atomic replacement transaction but at most eight committed keys. Completion invalidates
all old authority and requires normal login. Password-only, code-only, pending, expired, changed-epoch
and wrong-purpose parents cannot authorize either replacement. Use a trusted consumed-receipt API for
the retained code digest; no wire parameter may act as a verifier. Actual signed proofs, replay,
concurrent code consumption, fixed deadlines, rollback and protected-context denial form the tests.

Operator recovery will separately claim its exact installation/account/purpose-bound host ticket,
retain a policy-checked password hash in a bounded ceremony and publish replacement factors only
after verified registration. It must preserve the same ownership/data boundaries and never publish
full authority. Host entry points and HTTP/browser/lifecycle activation remain subsequent S9 work.


## Reviewed restricted password and passkey recovery

Forgotten-password options now create the same account-independent pending recovery state without
looking up the account or its codes. Only canonical login and the candidate code digest are retained
in short-lived server state. The assertion step verifies the current registered user-verified key,
rechecks current ownership/epoch/nonce/purpose/origin under publication locks, then atomically consumes
the code and rotates into password-reset-only authority. A pending ceremony cannot reset a password.
Lost-passkey recovery requires the current password plus a code before returning owned credential
choices. Completing either path increments the account epoch, invalidates old full/limited/native
authority and incompatible tickets, and requires normal login. No recovery state resolves to a full
synchronization context.

Review identified a selection race: an options request could change the lost-key target while an
earlier consumed proof was awaiting publication. Selecting/reselecting now rotates the limited cookie,
CSRF and authority reference before issuing the target's challenge. Earlier receipts cannot bind to
the new parent. These rotations preserve original creation, expiry and two-proof timestamps. Verified
replacement inserts the new key before deleting the old last key, including at the eight-key ceiling;
the temporary ninth row is confined to the atomic transaction and cannot remain committed.

All 64 account-workflow cases pass, using real Argon2id, actual signed ephemeral WebAuthn proofs, native
JDBC session transport and PostgreSQL. Recovery additions cover unknown/disabled accounts and invalid/
expired/used codes without option/status disclosure, pending-state denial, one concurrent code winner,
fixed deadlines, normal login after replacement, interrupted/invalid replacement, selection rotation,
eight-key replacement, changed epochs/purposes and deferred commit failure. Failure before restricted
proof publication rolls back code/counter changes while the separately consumed nonce stays spent.
Failure after restricted proof publication leaves the earlier consumed code spent. Final static
findings concern shared SQL/test literals, immutable fixture declarations and line length; fixes are
applied and the complete backend regression is running. This remains private service evidence.

## Next operator-recovery implementation analysis

Resolve only the exact host-issued operator ticket's active sole account, with the selected
installation/mode/target/purpose and fixed ticket deadline. Validate and hash the owner's chosen new
password outside locks, then recheck the ticket and account snapshot under installation/account/parent
locks. Claim the ticket by transferring its reference before retiring the anonymous predecessor.
Permit interrupted retry only by that same current claimed ceremony, preserving its original
five-minute deadline and stable account user handle. Other parents cannot take the claim.

Final verified registration must match the consumed operator nonce, configured origin, existing
account handle, current epoch and exact claimed ticket. Insert the new verified key before removing
old keys, replace the staged compliant password, delete old recovery verifiers and invalidate all old
authority/tickets atomically. Preserve the account, scope and synchronization history. Completion
returns no full authority or replacement code set; after normal login the owner can issue a new code
set with recent factors. Add real wrong/replaced/claimed/expired-ticket, replay, rollback and normal
login tests. Host command/ownership runbook, physical lifecycle, protected HTTP adapters, browser
coordination and the HTTPS topology remain required before the complete S9 review and commit.


The reviewed recovery regression passes all 167 unit tests and 234 fresh PostgreSQL cases, with
zero failures/errors/skips. Main/unit/integration PMD and formatting pass. Authentication sources
add no Checkstyle warnings; the 57 existing scaffold main warnings and one existing unit fixture
warning remain. Documentation validation (six tests and links/anchors/ADR index) and whitespace
checks pass. Operator recovery and runtime HTTP/browser/HTTPS evidence remain unfinished.


## Reviewed operator-recovery service progress

Exact host-ticket recovery now has a private service entry point. The ticket identifies only the
sole active owned account; the owner's compliant new password is hashed outside publication locks.
Claim and retry recheck installation/account/parent/ticket under ordered locks, transfer the ticket
claim before retiring the predecessor, rotate cookie/CSRF/reference and preserve the original bounded
deadline and account handle. Actual verified user-verified registration precedes inserting the new
key and deleting obsolete keys. The same transaction publishes the staged password, removes all old
recovery verifiers and tickets, advances epoch and removes all old native/full/limited authority.
Account/scope/handle and synchronization history remain unchanged. Normal both-factor login follows;
only then can the owner generate a new recovery-code set.

The expanded 75 workflow cases pass. Operator cases prove successful normal login with the replacement
factors, recovery-code reissue under recent normal factors, no data authority from a ticket/limited
parent, wrong/expired/replaced/already-claimed ticket and weak-password denial, same-owner bounded
retry, final claim/deadline recheck, rejected user verification, spent nonce and deferred commit
rollback restoring original password/key/codes/ticket/current full authority. Main PMD passes and
operator code adds no Checkstyle warnings. Final shared test-literal findings are fixed; complete
backend regression will verify this checkpoint. Host command and runtime integration are still pending.

## Next host-command and lifecycle integration analysis

Keep the controlled command in the existing backend application project. It must run without an
HTTP listener or ordinary operator session, expose only setup/recovery ticket issuance and safe file
reconciliation, and take no secret in arguments. Reuse datasource configuration, existing transactions,
exact ticket issuance and validated existing protected-directory handling. Validate closed command
options and target shape before work, emit fixed redacted success/failure diagnostics and no supplied
argument, credential or account/ticket identifier. Avoid loading default users or the regular web
authority graph for this host capability. Fresh installation initialization may select only the
explicit matching authentication mode; it cannot convert a committed no-auth installation.

Startup cleanup must finish before HTTP traffic can acquire authority, then scheduled and on-access
cleanup must enforce the accepted 60-second physical-removal bound and reconcile ticket files. Preserve
ordered installation/account/parent locks and independent sweep transactions. Document proportionate
host ownership verification, protected file delivery, expiration/replacement and exceptional access
in the operator runbook. Verify actual command parsing/no-listener behavior, secure files, rollback,
restart cleanup and sanitized diagnostics with synthetic data. Runtime auth mode, parser/Origin/CSRF
and rate/header adapters, browser coordination and authenticated HTTPS topology remain the following
S9 boundaries; no complete-step commit or production readiness claim is justified yet.


The final reviewed operator-recovery regression passes all 167 unit tests and 245 fresh PostgreSQL
cases, with zero failures/errors/skips. Main/unit/integration PMD and formatting pass; authentication
and integration sources add no Checkstyle warnings. The existing 57 scaffold main warnings and one
existing unit fixture-package warning remain. Documentation validation and whitespace checks pass.
No host command, authenticated HTTP runtime, browser authentication UI or HTTPS authentication
topology has been installed by this checkpoint. S9 remains in progress and uncommitted.


### Concrete host-command implementation

Use the existing executable's `operator` dispatch and a dedicated Spring non-web context outside
the normal component scan. Reuse Boot datasource/Flyway/JDBC configuration and the existing host
ticket/file components. Accept only `setup-ticket`, `recovery-ticket` and `reconcile`, an absolute
protected directory and, for recovery only, a canonical account UUID. Parse before context startup;
reject unknown/duplicate/misplaced options and secret-bearing arguments. Force non-web startup and
a dedicated logging configuration that discards events through internal trusted properties, so database
and framework failures cannot print supplied configuration. Print only fixed outcome text and return
closed exit codes. Test the actual executable dispatch in child JVMs with synthetic PostgreSQL and
configuration/error canaries.

Setup may atomically select `single-user-auth` only from an empty unconfigured installation in the
same ticket/file publication transaction. Recovery requires the already matching committed mode.
Rollback must restore unconfigured mode and reconcile an orphan file without selecting a mode.
The ordinary application's authenticated mode remains denied until remaining S9 runtime boundaries
are installed and verified. The isolated command creates no web listener, context resolver or default
user, and exposes no command property through the ordinary application graph.


## Reviewed host-command progress

The existing executable now dispatches a closed `operator` command before web startup. Its separate
non-web Spring graph reuses Boot datasource/Flyway and existing transaction/ticket/file components;
the ordinary application cannot component-scan it. Setup atomically selects authentication only from
a genuinely empty unconfigured installation. Existing committed modes cannot be converted, recovery
targets only the sole active owned account and reconciliation can delete a rolled-back orphan file
without selecting a mode. CLI output contains only fixed outcomes; original arguments are never
forwarded as arbitrary Spring properties.

Real child-JVM tests found a deferred transaction failure escaping with a cause/trace and a Logback
no-appender warning under overridden logger levels. Review added explicit transaction failure handling,
a fixed top-level uncaught-error boundary, a discard appender and success output after context close.
All 18 parser cases and 31 PostgreSQL/file cases pass (13 command cases plus 18 existing ticket cases),
including occupied HTTP port, restart/replacement/expiry, exact recovery target, unsafe directory,
rollback restoring an unconfigured mode/removing its file, wrong configuration, argument/diagnostic
canaries and unexpected errors. Final static findings are test constants and long SQL lines and are
fixed before lifecycle implementation. The operator runbook documents command usage and protected
ownership verification/delivery, retaining unfinished runtime and S15 gates.

### Concrete lifecycle adapter plan

Add a selected authentication adapter that performs independent database sweeps, protected ticket-file
reconciliation and transient limiter cleanup. Run its initial sweep through Spring's singleton startup
boundary before the web server lifecycle starts accepting requests, not a late application runner.
Use a 30-second scheduled sweep within the accepted 60-second normal-operation physical-removal bound.
Before a sensitive request, remove expired/current-invalid native/parent/challenge/ticket/code state
and reconcile files before resolving authority; no cleanup operation grants or caches account authority.

The adapter is constructed privately first and will be wired only with the authenticated runtime
configuration after HTTP defenses are installed. Scheduling catches known controlled storage/database/
transaction failures with fixed minimized diagnostics and retries at the next tick; startup/on-access
fail closed. A failed initial sweep must prevent startup. Avoid overlapping independent sweeps in one
instance and prohibit an enclosing transaction that can violate lock ordering. Tests must use real
PostgreSQL/files and controlled time, including startup before a real listening boundary, next-access
expiry, scheduled database/file purge, consumed-file removal, overdue security-event deletion, limiter
expiry, nonoverlap and failure/retry without exception/cause/configuration content.

### HTTP envelope and proof boundary implementation plan

Read authentication envelopes through a dedicated Jackson adapter with strict duplicate detection,
bounded document/string/depth/token limits, a closed field set per contract and no trailing JSON.
Validate canonical base64url and decoded byte bounds before constructing established WebAuthn types;
require `id`/`rawId` equality and the fixed credential type. Validate Unicode scalar labels/passwords
without normalization. The adapter keeps request material out of diagnostics and never authorizes a
session. The eventual HTTP guard consumes proof challenges after Origin/CSRF/admission checks but
before reading the body; malformed envelopes cannot preserve an admitted nonce. Test actual parser
bounds, duplicate/unknown/null/wrong-type/trailing fields, canonical encodings and redaction, then wire
this adapter into the complete runtime boundary. Keep authenticated startup closed until integration.

### Concrete HTTP workflow mapping plan

Map the exact contract routes to the completed feature services through bounded request readers.
Keep GET bootstrap/status non-renewing and project only closed minimized response fields; serialize
WebAuthn options explicitly so provider metadata and `allowCredentials` cannot leak into the wire.
Consume and fully parse admitted proofs through the reviewed pre-body boundary before invoking the
cryptographic service. Scalar requests use the tighter 16 KiB bound; bodyless POSTs reject content.
All outcomes are no-store with fixed generic errors and no implicit framework login/session fallback.
Credential listing after restricted lost-key proof may show only the same account's owned choices,
as already allowed by the contract; other limited states still deny. Construct these HTTP entry points
privately before authenticated runtime activation and verify them using the actual servlet session
repository, established guard/CSRF filters, PostgreSQL and signed synthetic passkeys.

### Recovery availability contract analysis

The account UI must warn about codes expiring within thirty days after a reload, without retaining
codes or CSRF state. Add a protected non-renewing GET at the existing recovery-code path, before its
adapter. The response is a closed available/unavailable union: zero available codes has no expiry and
no expiry warning; one to ten codes exposes the common expiry and a trusted-server-clock warning.
Never project code/verifier material. Recheck current full account authority under the existing locks;
query only unused, non-future, unexpired rows. Verify empty/consumed/expired states, the exact thirty-day
boundary, limited/foreign authority denial and no activity renewal.

### Trusted data resolver analysis

Map only the actual established servlet session's typed reference and canonical opaque-ID digest to
current full database authority. Construct the existing internal owned context in its trusted package;
ignore client account/scope/device headers. Missing/untyped/limited/expired/revoked/mismatched-mode
observations deny, with no configured no-auth fallback or session allocation. Re-resolve on every
request and preserve the existing account/parent/scope transaction recheck, which remains the final
data-acceptance authority. Add focused adapter denial and identity-mapping tests before runtime wiring.

### Next selected-runtime integration plan

Construct the selected authentication graph from explicit trusted HTTPS origin, an existing protected
operator directory, the shared clock/JDBC transaction manager and the accepted providers. Keep raw
forwarding interpretation disabled. Wire the reviewed singleton startup/30-second lifecycle and
next-access filter before native-session loading. Use the established native JDBC repository and
controlled ID resolver; integrate the established CSRF filter with the non-allocating repository and
plain-header-only request handler. Put the exact browser guard before CSRF, disable implicit framework
login/logout/context persistence, and retain the existing no-auth branch.

The selected resolver must initialize/validate only an empty fresh sole-scope authenticated installation
or matching committed setup, never convert no-auth or admit multi-user configuration. Current native
transport maps through the new data resolver and the existing protected transaction recheck. Verify
actual Boot filter ordering, startup refusal on cleanup/configuration failure, HTTP error/cookie/header
semantics and trusted-peer limits through loopback HTTPS with disposable external test material.
Complete browser UI/WebAuthn and shared cookie coordination, then the real authenticated topology,
full repository validation and final S9 review before enabling the selected mode and committing S9.
The user has limited this goal to S9; after that commit, hand control back without starting S10.

## Reviewed private HTTP checkpoint

The bounded Jackson adapter rejects duplicates, trailing JSON, unknown/null/wrong-type properties,
noncanonical encodings, mismatched identifiers and oversized decoded proofs without retaining provider
causes. Normal credential envelopes use 16 KiB; WebAuthn proofs use 64 KiB. Explicit response projections
omit provider extensions, transport/attestation metadata and assertion `allowCredentials`.

The established CSRF filter uses only the current plain header. Missing deferred tokens supply an
unstored generated placeholder, allowing fixed 403 denial without implicit session creation or a
transactional save. Exact Origin/Fetch Metadata guards precede this boundary. Admitted proof attempts
commit nonce consumption before JSON and typed-proof parsing; malformed proofs commit a minimized
closed denied event, and replay cannot create full authority. Trusted peer/source-only attempts share
existing source/installation windows without retaining a fabricated login identity.

All 22 POST routes and five GET routes now have private bounded HTTP adapters. Real PostgreSQL/JDBC
servlet tests cover fresh setup, actual signed two-factor login, limited-state denial, minimized reads,
non-renewing observation, cookie-free logout, next-access cleanup and guard ordering. Owned lost-key
choices survive restricted-ceremony reload; full-account recovery availability remains denied there.
The contract-first availability response passes empty/consumed/expired and exact thirty-day warning
checks, exposing no codes/verifiers and never renewing interaction. The trusted data resolver ignores
wire identity and rechecks current native/database authority for every resolution; protected service
transactions retain their independent final account/session/scope checks.

The complete backend run passed 262 unit cases and 275 of 276 PostgreSQL cases. The one remaining
assertion expected the earlier throwing deferred-CSRF behavior. Review updated it to exercise the
actual established filter and prove fixed 403, no native/authority row and no cookie; all ten servlet
session cases then passed. Main/unit/integration PMD and formatting pass, with no new Checkstyle
warnings (57 existing scaffold main warnings and one existing unit fixture warning remain).
Contract validation passes all 21 cases and documentation validation passes all six cases plus links,
anchors and ADR index. This checkpoint is private adapter evidence, not complete S9 validation.

That checkpoint was superseded by the completion evidence below.

## S9 completion checkpoint — 2026-10-12

The selected `single-user-auth` runtime is now wired behind an explicit HTTPS origin and protected
operator-ticket directory. Fresh installations may claim only one empty sole scope; committed auth
installations restart with their authenticated owner, while no-auth installations cannot be converted
and multi-user mode remains rejected. The authenticated resolver reads server-owned session authority
and synchronization retains its independent transaction recheck. The auth-only Spring security chain
is selected exclusively, avoiding a conflicting fallback chain.

The Angular account panel includes operator setup, password-plus-passkey login, account management,
session and credential revocation, recovery workflows and explicit logout. WebAuthn and HTTP are
dedicated adapters. CSRF values stay in memory; authentication actions use an exclusive Web Lock,
logout persists only a non-authorizing target and origin, and the origin-wide cookie fence pauses
exchange until logout succeeds. Local edits and the outbox remain available.

The integration tests cover fresh PostgreSQL mode claim, selected resolver and filter behavior,
non-allocating status, session cookie flags, missing-CSRF denial, no-auth fallback denial, and an actual
embedded loopback HTTPS listener trusted only by its disposable generated certificate. They verify
HSTS, exact origin and secure cookies. PostgreSQL S9 authentication/account/lifecycle/operator suites
and the runtime HTTPS suite passed; frontend unit tests pass with the password/passkey CSRF workflow,
the full existing browser e2e suite remains green, and contract generation/lint checks pass. Full
`pnpm validate` reached backend integration checks but was stopped after the aggregate Testcontainers
run exceeded the useful feedback window; all S9 authentication integration classes were then run
separately and passed. The prior no-auth integration evidence remains unchanged.

This completes S9 implementation only. The default development Compose example stays no-auth; its
TLS boundary and S15/foundation operational, backup, restore, privacy and release gates are not part
of this step. Multi-user authentication remains S10. Hand control back without beginning S10.
