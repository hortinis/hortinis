# Spring authentication delegation

## Scope

Preserve S9 user flows, public JSON contracts, offline behavior and security invariants on fresh
databases. Prefer existing Spring Security 7.1.1 and Spring Session JDBC 4.1.1 mechanisms.

## Implementation steps

1. Use Spring Security request contexts and factor authorization for synchronization access. Load
   current authority through a read-only context repository; never cache revocation-sensitive
   authority in the HTTP session. Keep transaction-time ownership and revocation checks. Review
   and test missing, partial, expired, revoked and valid authority before committing.
2. Delegate browser security headers, session fixation and local logout to Spring components while
   preserving explicit transaction boundaries, delayed-cookie protection and closed JSON errors.
   Review servlet and HTTPS tests before committing.
3. Audit password, WebAuthn, CSRF and persistence extension points against the exact installed
   framework source. Replace compatible custom mechanisms; document specific incompatibilities
   rather than inventing wrappers that reproduce the old machinery. Run the documented validation
   suite, review the final diff and commit the remaining changes.

## Constraints requiring application checks

- Scope ownership and revocation are rechecked while holding transaction locks.
- Authentication challenges are consumed in a separate committed transaction before proof parsing.
- Recovery grants restricted enrollment authority, never normal data access.
- Background synchronization does not extend user-interaction inactivity deadlines.
- Cookie changes are released after commit; delayed logout must not clear a newer cookie.
- Public-key persistence excludes original attestation and optional authenticator metadata.

## Framework assessment

Spring's Webauthn4JRelyingPartyOperations 7.1.1 expects retained registration attestation during
assertion verification and saves credential updates directly through its repository. Its internal
object converter is not publicly configurable. Replacing the minimized WebAuthn4j adapter with this
implementation would require additional reconstruction and publication adapters, or changes to the
privacy/concurrency guarantees. Keep library verification and supported counter handling unless a
smaller compatible integration is demonstrated.

## Step 1 implementation boundary and verification

The proposed production change affects `AuthenticationRuntimeConfiguration`,
`AuthenticatedSynchronizationAccessResolver`, `SynchronizationAccessConfiguration`, and a new
read-only Spring `SecurityContextRepository` adapter. Spring loads the current database authority
once per request and creates password and WebAuthn factor authorities only for a completed, current
normal login. The synchronization resolver maps that Spring principal into the existing immutable
application context. Database locks and current ownership/epoch checks remain in synchronization
transactions; a request-scoped principal cannot authorize a transaction after revocation.

Explicit route policy:

- `/api/v1/server/capabilities` remains public discovery.
- `/api/v1/auth/**` keeps its existing workflow-specific checks, CSRF and proof admission.
- Health probes remain available without account authentication.
- `/api/v1/sync/bootstrap`, `/api/v1/sync/bindings/confirm`, `/api/v1/sync/operations` and
  `/api/v1/sync/changes` require both Spring factor authorities.
- Unsupported routes fail closed. The separate no-auth configuration remains unchanged.

Review checks before accepting this implementation:

- Missing, malformed, partial, recovery-only, expired, wrong-mode and revoked authority must deny.
- A valid two-factor session must reach all four synchronization endpoints.
- Supplied account headers and arbitrary saved session principals must never grant identity.
- Revocation between request-context loading and transaction admission must still deny.
- Public discovery, anonymous account status and health probes must remain usable.
- Context lookup failure must produce the existing minimized unavailable response, including when
  Spring resolves its deferred context before `ExceptionTranslationFilter` runs.
- Request completion must clear the Spring context; no identity may leak to a reused worker thread.
- No request-context loading may allocate a session or renew user-interaction inactivity.

Run the resolver/context tests and real authenticated runtime tests first, then the complete backend
build, documentation and contract checks and browser/topology validation. Review the resulting diff
and commit only after those checks pass.

## Execution status

The user explicitly approved the request-authorization migration on 2026-10-14. Step 1 implements
Spring request contexts and factor authorization. The complete backend checks must pass before its
commit; subsequent steps remain subject to the implementation and review sequence above.
