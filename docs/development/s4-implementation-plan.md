# S4 implementation plan

## Analysis

S3 partitions PostgreSQL synchronization state and requires immutable access contexts in scoped
persistence, but its technical adapter and contextless service overloads still select the sole scope
implicitly. No explicit mode configuration currently gates startup. S4 must move trusted resolution to
the HTTP boundary, preserve the existing per-scope transaction boundaries, and fail closed without
introducing authentication or browser-selected authority.

This session assumes fresh browser and server databases. Extend the fresh installation schema directly;
no upgrade migration or ownership inference is required. Use the already accepted Spring configuration,
JDBC, JUnit, and Mockito capabilities; no new technology decision is needed.

## Implementation sequence

1. Require an explicit closed `hortinis.access.mode` while synchronization persistence is enabled.
   Reject absent/unknown values and authenticated modes until their actual implementation exists.
   Persist the accepted installation mode atomically, validate the designated sole scope, and reject
   mismatched committed configuration. Emit one content-free no-auth configuration warning.
2. Replace the technical compatibility adapter with a narrow access resolver. Controllers resolve
   before parsing or workflow execution. Remove contextless service overloads. Retain explicit scoped
   transaction/persistence checks and reject unsupported authority at the database boundary.
3. Enforce the S4/T04 same-origin mutation boundary with Origin/Fetch Metadata checks and no trust in
   Host/forwarded headers; require one explicit valid `hortinis.access.public-origin` at startup; preserve non-browser no-auth access and the existing contract denial shape.
   Provide explicit test-only scope selection through a resolver in integration-test sources, with no
   production header/query/body impersonation input. Test controller ordering, startup rejection,
   persistence denial, cross-scope HTTP behavior, restart/mode mismatch, and minimized diagnostics.
   Add representative controller/service/context dependency and signature rules.
4. Configure development Compose and synthetic tests explicitly. Update development, operator,
   threat/control, and privacy handoffs with implemented evidence and remaining gates.
5. Run the documented full validation and repository checks. Review the final diff for authorization,
   SQL/transaction ordering, production artifact isolation, English consistency, and privacy; fix
   findings, record actual results, and commit before starting S5.

## Acceptance boundaries

No-auth access authorizes every reachable client to the designated sole scope and provides no account
isolation. Only synthetic technical data is authorized at this stage. Test-created additional scopes
exercise isolation without claiming authentication. Account/session/device processing, transitions,
recovery, protected deployment, and real-user readiness remain separate S8-S15 gates.

## Review and validation outcome

Completed on 2026-10-11. Review removed contextless service access and found two origin-boundary risks:
raw servlet paths could disagree with routed paths, and request Host could authorize a foreign origin
or fail behind an edge. The guard now covers mutations regardless of raw path and requires one explicit
validated public browser origin, independent of Host/forwarded claims. Adversarial path, forged Host,
malformed configuration, edge, and simple-content tests cover these corrections.

`pnpm validate` passed: contracts/docs, frontend quality/builds, 278 web unit tests, nine Chromium
smoke/offline tests, 109 Java unit tests, 57 PostgreSQL integration tests, backend quality/builds,
four real-topology tests, and repository checks. PMD reports no violations; new sources have no
Checkstyle warnings, with warning-only baseline debt retained. Manual review checked mode/setup
rollback, per-request resolution, immutable authority, scoped SQL and transaction/lock ordering,
public contract compatibility, diagnostic minimization, and English consistency. Both application
JARs include the production resolver and exclude synthetic/test/legacy context classes.

S4 is validated. No-auth remains synthetic-only and does not provide account isolation. S5 can begin;
authentication, supported mode transitions, trusted deployment, restoration suppression, and complete
security/privacy readiness remain later gates.
