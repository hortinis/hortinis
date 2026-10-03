# Access and binding contracts

- Owner: S2, extending ADR-0029. Adapters remain owned by S4/S6/S8-S14.
- Baseline: `origin/feat/g2e-validation`, commit `db003092d24460015522dcf794d4de86ead0a62f`.

## Identities and application boundary

`LocalDataSetId`, `ServerInstanceId`, and `SynchronizationScopeId` are distinct application types
using the existing canonical lowercase UUID wire representation. UUID version is not protocol
validity. Account identity uses the same UUID syntax but is never interchangeable with scope identity.
These identifiers are non-secret metadata, never credentials. No new library is selected.

The server-only immutable `SynchronizationAccessContext` has closed configured and authenticated
authority variants. Its factories are package-private to the access capability: public DTO parsers
cannot create it. The configured variant represents explicit `single-user-no-auth` setup; authenticated
variants require account identity and an authenticated mode. S4 implements trusted resolution and
passes the context explicitly through services/persistence. Device authority is not declared until S11
defines enrollment. The context has no credential fields, wire schema, or content-bearing diagnostic
representation. A parsed bootstrap identity is an observation, not an access context.

## Discovery, bootstrap, and confirmation

| Operation | Information and authority | Runtime owner |
| --- | --- | --- |
| `GET /api/v1/server/capabilities` | Public instance ID, configured mode, contract version, supported boundaries and snapshot capability. No scope/account identity, content, credentials, setup state, or software fingerprint. | S6 |
| `GET /api/v1/sync/bootstrap` | Resolve current access first, then return authorized identity, observed population, supported boundary, and an opaque exchange expectation. Never accept account/scope selectors. | S6; authenticated resolution S9/S10 |
| `POST /api/v1/sync/bindings/confirm` | Confirm an observed empty destination using only its expectation. Revalidate current access and population in a transaction; return the same bootstrap shape or a generic precondition error. | S6 |

Confirmation is idempotent for the same still-valid expectation and destination. It does not create a
scope, account, device, server-side ownership relationship, or client enrollment. Controlled setup
owns provisioning. It establishes permission to complete a local binding, not a distributed transaction
or durable exemption from future authorization. Unsupported paths remain paused.

Population is `empty` only when the scope has no accepted synchronization history, live records,
tombstones, retired identifiers, or acceptance evidence. Otherwise it is `populated`. A partial/empty
pull cannot establish emptiness. Bootstrap is observational: a racing writer invalidates an empty
expectation. Confirmation and the initial transfer must recheck that observation at their acceptance
boundary. S6 must serialize empty-path admission with publication; it may not leave an unprotected
gap between confirmation and first transfer. An expired/stale admission requires recovery, preserving
operation identity and local intent.

The closed boundary variants are `generationless` (no generation claim) and `generation` (a non-empty opaque
generation). Bootstrap metadata does not advance the local cursor. G3 still obtains/persists its initial
generation and cursor together through a complete-history initial pull. Neither bootstrap nor
confirmation implements G4 snapshots. Capabilities describe implemented server paths, not all paths
present in OpenAPI; an unsupported snapshot capability is explicitly false.

## Exchange expectation and fencing

`ExchangeExpectation` is a bounded ASCII opaque value, non-secret and non-authorizing. S6 owns its
issuance, expiry, storage/validation, and safe admission lifecycle; S2 selects no cryptography or session
mechanism. It refers to the resolved instance, scope, account when applicable, committed mode, and
observed binding/population boundary. The server always resolves current access independently before
checking this expectation. Replays can never grant access to another destination or extend a session.

Normal sync operations carry it in `Sync-Expectation`, never a URL or client-selected scope. The header
is optional in the G2 contract while adapters remain unchanged; S6 makes it mandatory for its advertised
binding-aware exchange. Successful version-one discovery/bootstrap identifies that S6 capability;
the existing G2 runtime has no discovery route and cannot advertise it. Failed or missing discovery
never permits fallback upload. Missing expectations then fail closed. A changed instance, scope, account,
mode, or initial-admission boundary rejects the request before any data lookup or acceptance. It is
not added to canonical operation identity: refreshing an expectation for the same authorized destination
must preserve operation IDs and submitted generation evidence. An expectation must not be refreshed
automatically for a different destination.

Locally, version-one binding metadata keeps origin, authorized identity, and supported boundary with its
data-set ID. ASCII origin syntax excludes userinfo, paths, queries, fragments, and whitespace;
international hostnames use normalized punycode. The S6 adapter additionally normalizes/validates the
URL authority using browser URL semantics before persistence. No credentials or expectation
are persisted in the binding model. Interrupted handshake metadata belongs to S6 and must have a bounded
cleanup policy before persistence. Binding state and exchange state are separate. Positive decimal
string fences advance on binding changes, logout, revocation, or account changes. S5/S6 implement atomic
storage and fencing; S2 defines representations only. Unknown binding versions fail closed.

Exchange states are `offline`, `binding_pending`, `bound`, `auth_required`, `binding_mismatch`, `revoked`,
`reconciliation_required`, `scope_deleted`, and `disconnected`. `bound` is current readiness, not a
permanent authorization claim. Offline pending logout is recorded separately from confirmed revocation;
only logout may retry until it completes before any sign-in/synchronization. No session value is stored.
Specific revoked/deleted states require authorized evidence; generic denial cannot establish them.

A future multi-scope selector must be checked against server-authorized membership before constructing
the access context or looking up data. The feature is absent from current paths, headers, query
parameters, and request models. Knowledge of an identifier never proves membership.

## Errors, HTTP caching, and privacy

| Status | Closed code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Invalid wire shape or syntax. |
| 401 | `AUTHENTICATION_REQUIRED` | Current authentication is required. |
| 403 | `ACCESS_DENIED` | Generic denied access, including wrong owner or unavailable authorization. |
| 409 | `BINDING_PRECONDITION_FAILED` | An authorized destination/admission expectation no longer matches. |
| 503 | `ACCESS_UNAVAILABLE` | Required access service cannot safely resolve the request. |

The new access errors have fixed generic messages and no identifiers, echoed input, content, or detailed
denial reasons. Authorization precedes resource-existence/conflict errors: existing synchronization
errors may reveal only the currently authorized scope's state. G3's existing reconciliation-required
error remains separate; no generation/record details may be returned before access checks.

Every declared response, including public discovery and all sync errors, requires
`Cache-Control: no-store`. This prevents stale identity discovery and unintended HTTP caching of
protected responses. Intentional IndexedDB storage remains governed by the accepted offline policy;
it is not HTTP caching. The service worker must keep API responses outside its asset/data caches.
S2 tests declarations; later adapters and topology tests prove actual headers and cache behavior.

Session cookies and authentication/CSRF policy remain S8 decisions. This contract adds no login,
Bearer authentication, credentials in URLs, or script-storage credentials. Existing opaque cursor and
snapshot query values remain non-secret protocol state. Identifiers, expectations, bindings, and
bodies must not enter operational or security logs. All fixtures use synthetic data.

## Evidence and handoff

S2 checks generated drift, both emitters' schema acceptance/rejection, HTTP paths/headers/statuses,
strict TypeScript/Java parsing, distinct identities, and internal-context construction constraints.
The fixture manifest separates the implemented `access-contract-parsing` suite from planned runtime
access/binding behavior. Every declared S2 fixture is consumed by all three validators; unsupported
dispatch fails the suite.

This exercises contract portions of AC-AS-02/04 and T01/T02/T09/T15. Relevant pinned ASVS controls are
`v5.0.0-2.2.1`, `v5.0.0-8.2.1`, `v5.0.0-8.3.1`, and `v5.0.0-15.3.3` as mapped in the
accepted threat model. Parsing does not verify runtime authorization, isolation, revocation, or
production privacy controls. S3/S4/S5 can consume the types; S6 implements the handshake and race
boundary; S7 demonstrates real isolation; S8-S15 retain their security/privacy release gates.
