# Access and synchronization threat model

- Status: Accepted S1 design baseline, 2026-10-02; implementation and verification remain pending.
- Policy: [ADR-0029](../architecture/decisions/0029-access-and-synchronization-scope.md).
- Functional rules and scenarios: [access specification](../architecture/access-and-synchronization-scope.md).
- Data classification and retention: [privacy lifecycle](../privacy/access-data-lifecycle.md).
- Complete catalog: [ASVS applicability register](asvs-5.0.0-applicability.md).

## Assets, actors, and trust boundaries

Protect garden content and history, local pending intent, account credentials, sessions, scope ownership,
accepted-operation evidence, synchronization ordering, deletion instructions, backups, and availability.
The client includes an untrusted browser and request sender. A legitimate account holder may also try
to access another account, reuse IDs, exhaust quotas, or submit arbitrary protocol messages.

Other threats include malicious pages/extensions, a person sharing a browser profile, stolen devices
or cookies, a replaced server, compromised application/database credentials, vulnerable dependencies,
and operator errors. Operator/root control can read or change an installation's data; application
authorization does not protect against a hostile administrator with host/database access. Separate
operator powers from ordinary account authorization and constrain support access operationally.

| Boundary | Required enforcement |
| --- | --- |
| Browser UI to local persistence | Data-set partitioning and atomic projection/outbox writes; fence tabs and binding changes. Local IDs are not credentials. |
| Browser/network to server | TLS, origin/CSRF checks, closed contracts, bounded input, rate limits, validated sessions where required. |
| Request to application workflow | Trusted context resolution before resource lookup; deny unknown authority and configuration. |
| Workflow to persistence | Explicit scope in all operations, constraints, locks, receipts, sequences, cursors, generations, and snapshots. |
| Application to operator setup | Separate privileged authorization, recent authentication or controlled offline bootstrap, atomic mode/ownership transitions. |
| Live storage to backups/logs | Minimized fields, restricted identities/permissions, protected transfer/storage, bounded retention, deletion-aware restoration. |
| Installation to optional provider | Disabled unless the adapter is explicitly selected and its data flow/privacy review accepted; never inherit account authority. |

No-auth removes application login, not the browser/network trust boundary. It offers no per-person
isolation and is unsuitable for mutually untrusted callers. The operator must authenticate access at
an accepted compensating boundary or restrict it to a single-user local device before personal-data
use; public discovery and status-only probes do not authorize scope access.

## Threats, controls, and evidence

Identifiers below refer to the pinned ASVS 5.0.0 catalog. References to Level 3 requirements are selected
stronger controls, not a claim of Level 3 verification. Scenario IDs refer to the access specification.

| Threat | Accepted control | ASVS identifiers | Required owner/evidence |
| --- | --- | --- | --- |
| T01: Untrusted browser supplies an account/scope/device ID or forges a context | Resolve authority server-side before workflow execution; no production impersonation inputs; explicitly scoped persistence. | `v5.0.0-8.2.1`, `v5.0.0-8.3.1`, `v5.0.0-15.3.3` | S2/S4/S9/S10; AC-AS-02; invalid/extra-property and resolver-denial cases. |
| T02: Cross-account IDOR using records, receipts, cursors, generations, snapshots, or colliding IDs | Look up only inside the authorized scope; bind protocol state to instance/scope; independently partition locks and browser work. | `v5.0.0-8.2.2`, `v5.0.0-8.4.1` | S3/S5/S7/S13/S14; AC-AS-03/04 with deliberate substitutions and identical IDs. |
| T03: Credential/session theft or fixation | Established authentication implementation; protected opaque cookies; independent CSPRNG session secrets; rotation, expiry, revocation, recent authentication for sensitive actions. | `v5.0.0-3.3.1`, `v5.0.0-3.3.2`, `v5.0.0-3.3.3`, `v5.0.0-3.3.4`, `v5.0.0-7.2.3`, `v5.0.0-7.2.4`, `v5.0.0-7.3.1`, `v5.0.0-7.3.2`, `v5.0.0-7.5.3` | S8/S9/S11/S12; AC-AS-06/08 and stolen/old-cookie tests. Numeric policies are S8 blockers. |
| T04: CSRF, login/logout forgery, or drive-by mutation of a no-auth server | Explicit origin/CSRF defense for sensitive actions, safe HTTP methods, no wildcard credentialed CORS, no permissive private-network assumption. | `v5.0.0-3.5.1`, `v5.0.0-3.5.2`, `v5.0.0-3.5.3`, `v5.0.0-3.4.2` | S4/S8/S9/S12/S15; malicious-origin, simple-request, preflight-bypass, and setup/logout cases. |
| T05: XSS-assisted requests or local data exfiltration | Render untrusted content as text; contextual encoding; restrictive CSP; no third-party runtime assets; no script-readable credentials. Treat same-origin compromise as able to read offline data and issue authorized requests. | `v5.0.0-1.2.1`, `v5.0.0-3.2.2`, `v5.0.0-3.4.3`, `v5.0.0-14.2.3` | S8/S15 plus each content adapter; malicious-content/header review; offline-storage deviations remain visible. |
| T06: Replay after lost acknowledgement or across bindings | Stable operation identity/request fields; per-scope receipts; fenced response application; expired generations become explicit reconciliation/indeterminate work. | `v5.0.0-2.3.1`, `v5.0.0-2.3.3`, `v5.0.0-15.4.2` | S6/S7/S12/S13/S14; AC-AS-04/05/07; ADR-0010/0023/0026 replay corpus. |
| T07: Brute force, account enumeration, or abusive recovery | Bounded attempts and resource limits without permanent attacker-induced lockout; generic account-related errors; consistent strength on every recovery/bootstrap path. | `v5.0.0-6.1.1`, `v5.0.0-6.3.1`, `v5.0.0-6.3.3`, `v5.0.0-6.4.3`, `v5.0.0-6.3.8` | S8-S11/S15; throttling, timing, expiry, single-use, and recovery-bypass evidence. Factor design remains blocked at S8. |
| T08: Revocation/deletion races and delayed responses | Commit access invalidation with ordered acceptance; revalidate on every request; serialize local binding changes; discard responses captured under stale binding fences. | `v5.0.0-7.4.1`, `v5.0.0-7.4.2`, `v5.0.0-8.3.2`, `v5.0.0-15.4.1`, `v5.0.0-15.4.2`, `v5.0.0-15.4.3` | S9/S11/S12; AC-AS-07/08/11 under controlled concurrency and restart. |
| T09: Malicious replacement server or restored older state | Validate origin/TLS and retained installation/account binding; new instance identity on rollback; explicit recovery; restore deletion instructions and prevent auth downgrade. Instance ID detects mismatch, not a hostile server that clones it. | `v5.0.0-12.2.1`, `v5.0.0-12.3.2`, `v5.0.0-2.3.1`, `v5.0.0-16.5.3` | S6/S12/S14/S15/P0.6; AC-AS-07/10/12; a cloned identity remains a server-compromise residual risk. |
| T10: Database compromise, injection, or overprivileged service account | Parameterized queries; least privilege; encrypted protected transport/storage per deployment risk; established password hashing; scoped constraints; protect and rotate backend secrets. | `v5.0.0-1.2.4`, `v5.0.0-11.4.2`, `v5.0.0-12.3.1`, `v5.0.0-13.2.1`, `v5.0.0-13.2.2`, `v5.0.0-13.3.1`, `v5.0.0-14.2.4` | S3/S8/S15/deployment gate; privilege, secret, TLS, and restore review. Current development DB credentials/transport are not production evidence. |
| T11: Logs expose content, credentials, raw identifiers, source addresses, or concrete routes | Separate operational/security purposes and allowlists; encode fields; deny bodies/credentials; transient trusted-address rate limiting; protected security collection and bounded purge. | `v5.0.0-16.1.1`, `v5.0.0-16.2.5`, `v5.0.0-16.3.1`, `v5.0.0-16.3.2`, `v5.0.0-16.4.1`, `v5.0.0-16.4.2`, `v5.0.0-16.4.3` | S4/S9-S12/S15; AC-AS-14 at proxy, server, DB, backup tooling, and browser diagnostic layers. |
| T12: Backups or exports leak deleted data or resurrect access | Restricted encrypted storage/transfer; finite expiry; separate minimal deletion instructions; restore suppression before serving; never export credentials in a business backup. | `v5.0.0-14.1.1`, `v5.0.0-14.1.2`, `v5.0.0-14.2.4`, `v5.0.0-14.2.7` | P0.6/S12/S15; AC-AS-11/12, backup-age and restore-with-erasure evidence. |
| T13: Operator selects an unsafe mode, exposes setup, or restores permissive configuration | Explicit modes, persisted committed mode, no automatic claim or fallback; reject downgrades; production test code absent; privileged setup separate from health/discovery. | `v5.0.0-6.3.2`, `v5.0.0-13.4.2`, `v5.0.0-13.4.5`, `v5.0.0-15.2.3`, `v5.0.0-16.5.3` | S4/S9/S12/S15; AC-AS-02/09/10; direct config/edit/restart/concurrent-operator tests. |
| T14: Shared/lost device exposes retained garden data after logout | Explicitly disclose local retention; separate account bindings; offer local deletion/export; rely on protected device/browser profile for local confidentiality. No claim of remote wipe or offline unlock. | `v5.0.0-14.3.1`, `v5.0.0-14.3.3` (deviations) | S5/S12/S15; AC-AS-06/07/13 and user information; local encryption/unlock needs a later accepted design if required. |
| T15: Malformed data, costly snapshots, enrollment abuse, or dependencies exhaust resources | Closed contracts, bounded request/page/work sizes, quotas/concurrency limits, secure dependencies and update policies, per-scope locks without global blocking. | `v5.0.0-2.2.1`, `v5.0.0-2.4.1`, `v5.0.0-15.1.1`, `v5.0.0-15.1.2`, `v5.0.0-15.2.2`, `v5.0.0-15.4.3` | S2/S3/S7/S8/S14/S15; negative input, capacity/concurrency, dependency, and restore tests. |

## Verification target, deviations, and blockers

Target all applicable Level 1 and Level 2 controls in ASVS 5.0.0. The catalog register distinguishes
applicability from implemented/verified state. Conditional features cannot be enabled without resolving
their controls. No Level 2 certification/completion claim follows from this design.

| Item | Disposition | Mitigations and gate |
| --- | --- | --- |
| D01: `v5.0.0-14.3.1` expects authenticated client data to clear at session end | Accepted product deviation: logout preserves the intentional offline data set. | Clear transient credential/account UI state and pause sync; do not send `Clear-Site-Data` that destroys pending intent. Device/profile protection, explicit local deletion, disclosure, and S15 review remain required. |
| D02: `v5.0.0-14.3.3` excludes sensitive browser persistence | Accepted product deviation: local garden data and necessary synchronization metadata persist in IndexedDB. | Minimize local data; prevent XSS; no credentials in browser script storage; isolate data sets and bindings. No encryption-at-rest or local secrecy claim; S15 must review residual risk before real-user use. |
| D03: `v5.0.0-15.3.4` includes source addresses in logging/security decisions | Privacy restriction: do not persist raw source addresses in application or proxy logs. | Validate proxy authority and use addresses transiently for necessary rate limiting; document any short-lived abuse keys and purge bound. Security logs use approved event/subject references. Record the logging limitation during S15 verification. |
| B01: `v5.0.0-6.3.3` requires multiple authentication factors or combined mechanisms | Applicable unresolved authentication-strength decision, not N/A. | S8 must select adequate mechanisms or document a justified exception and comprehensive controls for review. No password-only Level 2 assumption; block authenticated implementation approval until resolved. |
| B02: session/recovery/enrollment/CSRF policy details | Applicable unresolved design in S8/S11. | Record numeric lifetimes, factor/recovery strength, replay defense, and concurrency before their adapters. Never ship permissive placeholder values. |
| B03: production transport, service identity, secrets, and security-log collection | Applicable deployment/evidence work in S15 and foundation deployment gates. | Verify TLS including database/internal traffic, service credential lifecycle (including `v5.0.0-13.2.1`), protected secret handling, and logically separate security collection. Development Compose is not proof of these controls. |
| B04: operational/security/backup retention periods and deletion execution | Explicit privacy/release work in S12/S15/P0.6. | Accept deployment-specific periods, implement purge/restore suppression, and test every category before real-data use. |
| B05: legal basis, information, rights, processors, breach readiness, DPIA screening | Operator/controller decisions plus S15 evidence. | Use the privacy lifecycle template; unresolved entries block real personal-data deployment rather than weakening controls. |

## Review and change triggers

Revisit this model when adding an endpoint, authentication pathway, provider, field category, export,
storage technology, role, collaboration, log field, retention policy, or operator workflow. Reassess N/A
catalog entries before activating an excluded feature. Review discovered threats and residual risks
at each implementing task; S15 consolidates reproducible automated evidence, manual review, a scoped
penetration-test plan, and operator deployment evidence. G2f remains limited to synthetic technical data.
