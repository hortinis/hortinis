# ASVS 5.0.0 applicability register

> 2026-10-17 R1: Applicability now targets ADR-0034 email/password and action-token recovery.
> This is a design register, not replacement runtime evidence. Historical S8 counts are retained only
> in the explicitly historical section below. B01 strength remains unresolved.

- Status: Accepted S1 applicability baseline, 2026-10-02; no implementation pass is asserted.
- Target: every applicable Level 1 and Level 2 requirement, plus seven selected Level 3 controls.
- Authority: [ADR-0029](../architecture/decisions/0029-access-and-synchronization-scope.md).
- Control design, deviations and release blockers: [threat model](access-threat-model.md).

## Pinned source and coverage

Source: [OWASP ASVS 5.0.0 flat JSON at tag v5.0.0](https://raw.githubusercontent.com/OWASP/ASVS/v5.0.0/5.0/docs_en/OWASP_Application_Security_Verification_Standard_5.0.0_en.flat.json).
Retrieved on 2026-10-02; SHA-256: `8201b20eec2908c3380ac600c91c8ba746346fbb808859366abb232027532311`.
The source contains 345 unique requirements: 70 Level 1, 183 Level 2, and 92 Level 3.
Each appears exactly once below. Identifiers use OWASP's versioned form; remove the source's leading
`V` from its requirement ID when forming `v5.0.0-<chapter>.<section>.<requirement>`.
No requirement descriptions are reproduced; consult the pinned source for normative wording.
Attribution: OWASP Foundation and ASVS contributors; ASVS is licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Applicability and owner decisions are
Hortinis-authored annotations.

## Dispositions and evidence rule

| Disposition | Meaning | Count |
| --- | --- | --- |
| Applicable | Required within the stated mode/processing boundary; implementation and evidence pending. | 171 |
| Selected L3 | Required stronger control chosen by S1; implementation and evidence pending. | 7 |
| Conditional | Disabled/unselected feature or mechanism; controls become required before activation. Not a pass or blanket exclusion. | 37 |
| Pending decision | Unclassified applicability decision; zero does not mean that B01 or release risks are resolved. | 0 |
| Deviation | Explicit product/privacy departure; documented mitigation and S15 risk review, never a pass. | 3 |
| N/A | Excluded mechanism/role with stated reason; re-evaluate when scope changes. | 42 |
| Outside L2 | Level 3 requirement outside this target and not selected as an additional control. | 85 |

The mode/feature boundary and reason/owner for each row are given by its rationale key. Authentication
and session controls apply to authenticated modes and any privileged workflow that uses them. Their
absence in standalone/no-auth mode is not an exclusion for authenticated deployments. All networked
modes still require origin controls, scope checks, deployment safeguards, and the privacy gate.

S15 must attach reproducible evidence for applicable requirements, resolve conditional branches for
the actual deployment, verify N/A assumptions, and report deviations separately. The Level 2 target
does not imply an unqualified Level 2 verification claim: D01/D02 preserve intentional offline storage,
D03 restricts source-address logging, and B01 now includes the password-only strength gap, not merely pending executable evidence. Existing
development Compose credentials/internal transport and G2e success paths are not production evidence.

## Rationale keys and implementation ownership

| Key | Mode or feature boundary | Reason, control, owner, or review gate |
| --- | --- | --- |
| INPUT | All data paths | Validate/decode once, parameterize queries, render text safely, reject mass assignment and unsafe execution. S2/S4/S8/S15; T01/T05/T10/T15. |
| FLOW | All data paths | Enforce ordered workflows, scoped transaction/lock boundaries, and bounded resources. S3-S7/S9-S14/S15; AC-AS-03/05/08/11. |
| WEB | Browser and deployment | Secure same-origin delivery, headers, origin checks, safe rendering, and controlled navigation. S8/S9/S12/S15 and deployment gates; T04/T05. |
| COOKIE | Auth; any necessary cookie when present | Protected cookie setup; authenticated session cookies are absent in standalone/no-auth mode. S8/S9/S15; T03. |
| HTTP | All network data paths | Closed HTTP contracts, trusted proxy boundaries, message validation, and TLS behavior. S2/S4/S15; T01/T04/T15. |
| AUTH | Authenticated modes | Built-in account/password and secure bootstrap/recovery controls; no application-account login in no-auth/standalone mode. S8-S11/S15; T03/T07. |
| STRENGTH | Authenticated modes | ADR-0034 deliberately selects password-only login; email verification/reset does not add a login factor. B01 is open for the strength gap and S15 risk/mitigation review; no inherited S8 satisfaction or Level 2 claim. |
| SESSION | Authenticated modes | Native opaque sessions, thirty-minute request inactivity, rotation/revocation and current authority checks. No absolute lifetime or concurrent cap; appropriateness/nonconformance remains explicit S15 review. S9-R/S11/S12; AC-AS-06/08. |
| SCOPE | All server scopes; cross-account tests in shared mode | Server-authorized operation/data/field access and isolation; no mutable ownership fields or client authority. Single-user modes still scope queries. S2-S4/S7/S10-S14; T01/T02. |
| CRYPTO | Secrets, integrity, and deployment protection | Framework Argon2id/password handling and standard JDK secret primitives; WebAuthn is deferred. S9-R/S15 verify cost, generated secrets, transport and storage; no custom cryptography. |
| PASSWORD_HASH | Authenticated modes | S8 selects explicit Argon2id through the framework; S9/S15 verify cost, salts and bounded work. T03/T10. |
| TRANSPORT | Networked deployment | B03: verify TLS, certificates, internal/database transport, and trust before real-data use. Development loopback/Compose is not Level 2 production evidence. S15/deployment gates; T09/T10. |
| CONFIG | Server and operator deployment | Fail-closed modes, least privilege, backend identity/secret lifecycle, restricted egress and diagnostics, no production test code. S4/S15/deployment gates; T10/T13. |
| DATA | Personal/secret processing | Inventory, classify, minimize, protect, and purge data; non-cacheable API responses; intentional local persistence is handled by D01/D02 separately. S5/S9/S12/S14/S15; privacy lifecycle. |
| ARCH | Applications and dependencies | Reviewed dependency/update policy, safe bounded workflows, narrow response fields and trusted types. S2-S7/S8-S15 and foundation gates; T01/T15. |
| LOG | All logging layers | Allowlisted operational events and protected security events, access control, UTC, retention/purge and generic errors; no raw credentials/content/identifiers. S4/S9-S12/S15; T11. |
| CONCURRENCY | State-changing workflows | Selected Level 3 control: order revocation with authorized transactions, fence browser state, and avoid unsafe shared state/deadlocks. S3/S5/S7/S9/S11/S12; AC-AS-07/08/11. |
| RETENTION | Personal processing | Selected Level 3 lifecycle/purge control; effective numeric deployment periods remain B04. S12/S15/P0.6; AC-AS-11/12/14. |
| RECENT | Sensitive authenticated actions | Selected L3 remains required. Password change requires current-password proof; S11/S12 must specify recent-password proof for other sensitive transitions before implementation. Historical two-factor freshness is obsolete, not implementation evidence. |
| ENUMERATION | Account-related endpoints | Selected Level 3 generic failure/response/timing protection, including registration/recovery. S8-S10/S15; T07. |
| REVOCATION | Authenticated access | Selected Level 3 immediate access invalidation with explicit transaction ordering; offline notification is not required for denial. S9/S11/S12; AC-AS-08/11. |
| D01 | Intentional offline local data | Product deviation: retained local garden data after logout differs from client-data clearing. Clear transient auth state, pause sync, disclose shared-device risk; S15 review; no unqualified Level 2 claim. |
| D02 | Intentional offline local data | Product deviation: personal garden data persists in IndexedDB. No credentials there; minimize/isolate data, prevent XSS, offer explicit deletion; S15 residual-risk review. |
| D03 | Proxy/security processing | Privacy restriction: trusted source addresses may support transient rate limiting but raw addresses are not logged. Document bounded abuse keys and verification limitation at S8/S15. |
| FILES | File/export/restore feature when introduced | Conditional: S1 adds no file API. P0.6 and each file adapter must accept formats/size bounds, content validation, storage/download defenses and scanning applicability before activation. No current pass or exclusion claim. |
| ACTION_TOKENS | Invitation/reset secrets | Apply one-use, verifier storage, CSPRNG and entropy controls to the 256-bit action-token component even though it never logs in. S9-R R3/R4/R6 provide evidence; historical recovery-code/passkey proofs do not transfer. |
| MFA_FACTORS | Deferred MFA-factor loss | Conditional: no MFA loss/replacement path in ADR-0034. Reassess equivalent proof before adding MFA; mailbox recovery remains subject to AUTH/STRENGTH. |
| ACTION_LIFETIME | Email action-token lifetime | Applicable conservatively: reset expires at ten minutes. Invitation activation is limited to 24 hours under the initial-activation policy, never login; S15 must review that distinction and any nonconformance with this requirement, not declare the whole row passed. |
| OOB | Deferred telephone/SMS mechanism | Conditional: no PSTN/SMS authentication. Email token binding/rate controls are separately Applicable as ACTION_DELIVERY; email is not being excluded from recovery review. |
| ACTION_DELIVERY | Invitation/reset delivery | Apply purpose/account/issuance binding and brute-force limits conservatively to mail action tokens. No resend reuse or ordinary login; S9-R must test token substitution, replacement and source/global throttling. |
| OIDC | Optional OIDC adapter if enabled | Conditional: baseline is built-in accounts; federation is disabled until accepted adapter design verifies issuer/subject identity, signatures, transaction binding, and session coordination. S8/S15. |
| SIGNED | Signed tokens/assertions if introduced | Conditional: baseline browser sessions are opaque reference cookies, not self-contained authorization tokens. Reopen for optional OIDC assertions or signed protocol state before implementation. |
| PREFLIGHT | If CSRF defense relies on CORS preflight | Conditional unselected: S8 uses a session-backed CSRF token and exact Origin, not preflight alone. Reassess before adopting this mechanism; origin/CSRF defense remains mandatory. |
| MESSAGING | If postMessage is introduced | Conditional: current application has no postMessage data interface. Require trusted origin/sender and closed message validation before adding one. |
| MAIL | Authenticated invitation/reset SMTP | Applicable under ADR-0034: fixed sender/templates, validated stored recipient, no header injection, trusted origin, bounded Boot SMTP. S9-R and S15 verify mail transport, failure, privacy and diagnostics. |
| DERIVED_KEYS | If password-derived encryption keys are introduced | Conditional: S1 selects no offline encryption/unlock mechanism. Any later key derivation needs its own design and verified parameters. |
| MTLS | If mutual TLS identifies clients | Conditional: S1 selects no certificate authentication. Validate configured trust and identity mapping before using it for access. |
| NO_LDAP | Excluded mechanism | N/A: no LDAP authentication/query adapter in the accepted scope. Reassess before adding one. |
| NO_XPATH | Excluded mechanism | N/A: no XPath evaluation of user data. Reassess before adding XML processing. |
| NO_LATEX | Excluded mechanism | N/A: no LaTeX processor in the application. |
| NO_RICH_HTML | Excluded mechanism | N/A: no WYSIWYG/untrusted HTML feature; accepted content is rendered as text. Reassess before rich text. |
| NO_SVG | Excluded mechanism | N/A: no user-supplied scriptable SVG feature. Bundled trusted assets do not create such an upload interface. |
| NO_JNDI | Excluded mechanism | N/A: no user-controlled JNDI queries/lookup feature. Dependency/configuration review still prohibits unsafe lookups. |
| NO_MEMCACHE | Excluded mechanism | N/A: no memcache component or user-input memcache protocol. |
| NO_XML | Excluded mechanism | N/A: untrusted application contracts are JSON; no XML parsing or SAML adapter. Build-tool XML is not a user-data endpoint. |
| NO_GRAPHQL | Excluded mechanism | N/A: no GraphQL/data-expression API in the accepted HTTP/JSON application. |
| NO_WEBSOCKET | Excluded mechanism | N/A: synchronization uses HTTP/JSON; no WebSocket endpoint. |
| NO_SAML | Excluded mechanism | N/A: optional federation boundary is OIDC, not SAML assertions. |
| NO_OAUTH_RESOURCE | Excluded role | N/A: no public delegated OAuth resource-server role; built-in access uses the application session. Reassess if delegated access is added. |
| NO_OAUTH_SERVER | Excluded role | N/A: Hortinis is not an OAuth authorization server, OpenID Provider, or delegated-consent service. |
| NO_WEBRTC | Excluded mechanism | N/A: no WebRTC, TURN, media, or signaling service. |
| L3 | Outside selected target | Outside Level 2: not selected as an additional S1 control. This is a level boundary, not an N/A claim or permission to weaken accepted privacy/safety rules. |

## V1: Encoding and Sanitization

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-1.1.1` | 2 | Applicable | INPUT |
| `v5.0.0-1.1.2` | 2 | Applicable | INPUT |
| `v5.0.0-1.2.1` | 1 | Applicable | INPUT |
| `v5.0.0-1.2.2` | 1 | Applicable | INPUT |
| `v5.0.0-1.2.3` | 1 | Applicable | INPUT |
| `v5.0.0-1.2.4` | 1 | Applicable | INPUT |
| `v5.0.0-1.2.5` | 1 | Applicable | INPUT |
| `v5.0.0-1.2.6` | 2 | N/A | NO_LDAP |
| `v5.0.0-1.2.7` | 2 | N/A | NO_XPATH |
| `v5.0.0-1.2.8` | 2 | N/A | NO_LATEX |
| `v5.0.0-1.2.9` | 2 | Applicable | INPUT |
| `v5.0.0-1.2.10` | 3 | Outside L2 | L3 |
| `v5.0.0-1.3.1` | 1 | N/A | NO_RICH_HTML |
| `v5.0.0-1.3.2` | 1 | Applicable | INPUT |
| `v5.0.0-1.3.3` | 2 | Applicable | INPUT |
| `v5.0.0-1.3.4` | 2 | N/A | NO_SVG |
| `v5.0.0-1.3.5` | 2 | Applicable | INPUT |
| `v5.0.0-1.3.6` | 2 | Applicable | INPUT |
| `v5.0.0-1.3.7` | 2 | Applicable | INPUT |
| `v5.0.0-1.3.8` | 2 | N/A | NO_JNDI |
| `v5.0.0-1.3.9` | 2 | N/A | NO_MEMCACHE |
| `v5.0.0-1.3.10` | 2 | Applicable | INPUT |
| `v5.0.0-1.3.11` | 2 | Applicable | MAIL |
| `v5.0.0-1.3.12` | 3 | Outside L2 | L3 |
| `v5.0.0-1.4.1` | 2 | Applicable | INPUT |
| `v5.0.0-1.4.2` | 2 | Applicable | INPUT |
| `v5.0.0-1.4.3` | 2 | Applicable | INPUT |
| `v5.0.0-1.5.1` | 1 | N/A | NO_XML |
| `v5.0.0-1.5.2` | 2 | Applicable | INPUT |
| `v5.0.0-1.5.3` | 3 | Outside L2 | L3 |

## V2: Validation and Business Logic

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-2.1.1` | 1 | Applicable | FLOW |
| `v5.0.0-2.1.2` | 2 | Applicable | FLOW |
| `v5.0.0-2.1.3` | 2 | Applicable | FLOW |
| `v5.0.0-2.2.1` | 1 | Applicable | FLOW |
| `v5.0.0-2.2.2` | 1 | Applicable | FLOW |
| `v5.0.0-2.2.3` | 2 | Applicable | FLOW |
| `v5.0.0-2.3.1` | 1 | Applicable | FLOW |
| `v5.0.0-2.3.2` | 2 | Applicable | FLOW |
| `v5.0.0-2.3.3` | 2 | Applicable | FLOW |
| `v5.0.0-2.3.4` | 2 | Applicable | FLOW |
| `v5.0.0-2.3.5` | 3 | Outside L2 | L3 |
| `v5.0.0-2.4.1` | 2 | Applicable | FLOW |
| `v5.0.0-2.4.2` | 3 | Outside L2 | L3 |

## V3: Web Frontend Security

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-3.1.1` | 3 | Outside L2 | L3 |
| `v5.0.0-3.2.1` | 1 | Applicable | WEB |
| `v5.0.0-3.2.2` | 1 | Applicable | WEB |
| `v5.0.0-3.2.3` | 3 | Outside L2 | L3 |
| `v5.0.0-3.3.1` | 1 | Applicable | COOKIE |
| `v5.0.0-3.3.2` | 2 | Applicable | COOKIE |
| `v5.0.0-3.3.3` | 2 | Applicable | COOKIE |
| `v5.0.0-3.3.4` | 2 | Applicable | COOKIE |
| `v5.0.0-3.3.5` | 3 | Outside L2 | L3 |
| `v5.0.0-3.4.1` | 1 | Applicable | WEB |
| `v5.0.0-3.4.2` | 1 | Applicable | WEB |
| `v5.0.0-3.4.3` | 2 | Applicable | WEB |
| `v5.0.0-3.4.4` | 2 | Applicable | WEB |
| `v5.0.0-3.4.5` | 2 | Applicable | WEB |
| `v5.0.0-3.4.6` | 2 | Applicable | WEB |
| `v5.0.0-3.4.7` | 3 | Outside L2 | L3 |
| `v5.0.0-3.4.8` | 3 | Outside L2 | L3 |
| `v5.0.0-3.5.1` | 1 | Applicable | WEB |
| `v5.0.0-3.5.2` | 1 | Conditional | PREFLIGHT |
| `v5.0.0-3.5.3` | 1 | Applicable | WEB |
| `v5.0.0-3.5.4` | 2 | Applicable | WEB |
| `v5.0.0-3.5.5` | 2 | Conditional | MESSAGING |
| `v5.0.0-3.5.6` | 3 | Outside L2 | L3 |
| `v5.0.0-3.5.7` | 3 | Outside L2 | L3 |
| `v5.0.0-3.5.8` | 3 | Outside L2 | L3 |
| `v5.0.0-3.6.1` | 3 | Outside L2 | L3 |
| `v5.0.0-3.7.1` | 2 | Applicable | WEB |
| `v5.0.0-3.7.2` | 2 | Applicable | WEB |
| `v5.0.0-3.7.3` | 3 | Outside L2 | L3 |
| `v5.0.0-3.7.4` | 3 | Outside L2 | L3 |
| `v5.0.0-3.7.5` | 3 | Outside L2 | L3 |

## V4: API and Web Service

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-4.1.1` | 1 | Applicable | HTTP |
| `v5.0.0-4.1.2` | 2 | Applicable | HTTP |
| `v5.0.0-4.1.3` | 2 | Applicable | HTTP |
| `v5.0.0-4.1.4` | 3 | Outside L2 | L3 |
| `v5.0.0-4.1.5` | 3 | Outside L2 | L3 |
| `v5.0.0-4.2.1` | 2 | Applicable | HTTP |
| `v5.0.0-4.2.2` | 3 | Outside L2 | L3 |
| `v5.0.0-4.2.3` | 3 | Outside L2 | L3 |
| `v5.0.0-4.2.4` | 3 | Outside L2 | L3 |
| `v5.0.0-4.2.5` | 3 | Outside L2 | L3 |
| `v5.0.0-4.3.1` | 2 | N/A | NO_GRAPHQL |
| `v5.0.0-4.3.2` | 2 | N/A | NO_GRAPHQL |
| `v5.0.0-4.4.1` | 1 | N/A | NO_WEBSOCKET |
| `v5.0.0-4.4.2` | 2 | N/A | NO_WEBSOCKET |
| `v5.0.0-4.4.3` | 2 | N/A | NO_WEBSOCKET |
| `v5.0.0-4.4.4` | 2 | N/A | NO_WEBSOCKET |

## V5: File Handling

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-5.1.1` | 2 | Conditional | FILES |
| `v5.0.0-5.2.1` | 1 | Conditional | FILES |
| `v5.0.0-5.2.2` | 1 | Conditional | FILES |
| `v5.0.0-5.2.3` | 2 | Conditional | FILES |
| `v5.0.0-5.2.4` | 3 | Outside L2 | L3 |
| `v5.0.0-5.2.5` | 3 | Outside L2 | L3 |
| `v5.0.0-5.2.6` | 3 | Outside L2 | L3 |
| `v5.0.0-5.3.1` | 1 | Conditional | FILES |
| `v5.0.0-5.3.2` | 1 | Conditional | FILES |
| `v5.0.0-5.3.3` | 3 | Outside L2 | L3 |
| `v5.0.0-5.4.1` | 2 | Conditional | FILES |
| `v5.0.0-5.4.2` | 2 | Conditional | FILES |
| `v5.0.0-5.4.3` | 2 | Conditional | FILES |

## V6: Authentication

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-6.1.1` | 1 | Applicable | AUTH |
| `v5.0.0-6.1.2` | 2 | Applicable | AUTH |
| `v5.0.0-6.1.3` | 2 | Applicable | AUTH |
| `v5.0.0-6.2.1` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.2` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.3` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.4` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.5` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.6` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.7` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.8` | 1 | Applicable | AUTH |
| `v5.0.0-6.2.9` | 2 | Applicable | AUTH |
| `v5.0.0-6.2.10` | 2 | Applicable | AUTH |
| `v5.0.0-6.2.11` | 2 | Applicable | AUTH |
| `v5.0.0-6.2.12` | 2 | Applicable | AUTH |
| `v5.0.0-6.3.1` | 1 | Applicable | AUTH |
| `v5.0.0-6.3.2` | 1 | Applicable | AUTH |
| `v5.0.0-6.3.3` | 2 | Applicable | STRENGTH |
| `v5.0.0-6.3.4` | 2 | Applicable | AUTH |
| `v5.0.0-6.3.5` | 3 | Outside L2 | L3 |
| `v5.0.0-6.3.6` | 3 | Outside L2 | L3 |
| `v5.0.0-6.3.7` | 3 | Outside L2 | L3 |
| `v5.0.0-6.3.8` | 3 | Selected L3 | ENUMERATION |
| `v5.0.0-6.4.1` | 1 | Applicable | AUTH |
| `v5.0.0-6.4.2` | 1 | Applicable | AUTH |
| `v5.0.0-6.4.3` | 2 | Applicable | AUTH |
| `v5.0.0-6.4.4` | 2 | Conditional | MFA_FACTORS |
| `v5.0.0-6.4.5` | 3 | Outside L2 | L3 |
| `v5.0.0-6.4.6` | 3 | Outside L2 | L3 |
| `v5.0.0-6.5.1` | 2 | Applicable | ACTION_TOKENS |
| `v5.0.0-6.5.2` | 2 | Applicable | ACTION_TOKENS |
| `v5.0.0-6.5.3` | 2 | Applicable | ACTION_TOKENS |
| `v5.0.0-6.5.4` | 2 | Applicable | ACTION_TOKENS |
| `v5.0.0-6.5.5` | 2 | Applicable | ACTION_LIFETIME |
| `v5.0.0-6.5.6` | 3 | Outside L2 | L3 |
| `v5.0.0-6.5.7` | 3 | Outside L2 | L3 |
| `v5.0.0-6.5.8` | 3 | Outside L2 | L3 |
| `v5.0.0-6.6.1` | 2 | Conditional | OOB |
| `v5.0.0-6.6.2` | 2 | Applicable | ACTION_DELIVERY |
| `v5.0.0-6.6.3` | 2 | Applicable | ACTION_DELIVERY |
| `v5.0.0-6.6.4` | 3 | Outside L2 | L3 |
| `v5.0.0-6.7.1` | 3 | Outside L2 | L3 |
| `v5.0.0-6.7.2` | 3 | Outside L2 | L3 |
| `v5.0.0-6.8.1` | 2 | Conditional | OIDC |
| `v5.0.0-6.8.2` | 2 | Conditional | OIDC |
| `v5.0.0-6.8.3` | 2 | N/A | NO_SAML |
| `v5.0.0-6.8.4` | 2 | Conditional | OIDC |

## V7: Session Management

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-7.1.1` | 2 | Applicable | SESSION |
| `v5.0.0-7.1.2` | 2 | Applicable | SESSION |
| `v5.0.0-7.1.3` | 2 | Conditional | OIDC |
| `v5.0.0-7.2.1` | 1 | Applicable | SESSION |
| `v5.0.0-7.2.2` | 1 | Applicable | SESSION |
| `v5.0.0-7.2.3` | 1 | Applicable | SESSION |
| `v5.0.0-7.2.4` | 1 | Applicable | SESSION |
| `v5.0.0-7.3.1` | 2 | Applicable | SESSION |
| `v5.0.0-7.3.2` | 2 | Applicable | SESSION |
| `v5.0.0-7.4.1` | 1 | Applicable | SESSION |
| `v5.0.0-7.4.2` | 1 | Applicable | SESSION |
| `v5.0.0-7.4.3` | 2 | Applicable | SESSION |
| `v5.0.0-7.4.4` | 2 | Applicable | SESSION |
| `v5.0.0-7.4.5` | 2 | Applicable | SESSION |
| `v5.0.0-7.5.1` | 2 | Applicable | SESSION |
| `v5.0.0-7.5.2` | 2 | Applicable | SESSION |
| `v5.0.0-7.5.3` | 3 | Selected L3 | RECENT |
| `v5.0.0-7.6.1` | 2 | Conditional | OIDC |
| `v5.0.0-7.6.2` | 2 | Conditional | OIDC |

## V8: Authorization

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-8.1.1` | 1 | Applicable | SCOPE |
| `v5.0.0-8.1.2` | 2 | Applicable | SCOPE |
| `v5.0.0-8.1.3` | 3 | Outside L2 | L3 |
| `v5.0.0-8.1.4` | 3 | Outside L2 | L3 |
| `v5.0.0-8.2.1` | 1 | Applicable | SCOPE |
| `v5.0.0-8.2.2` | 1 | Applicable | SCOPE |
| `v5.0.0-8.2.3` | 2 | Applicable | SCOPE |
| `v5.0.0-8.2.4` | 3 | Outside L2 | L3 |
| `v5.0.0-8.3.1` | 1 | Applicable | SCOPE |
| `v5.0.0-8.3.2` | 3 | Selected L3 | REVOCATION |
| `v5.0.0-8.3.3` | 3 | Outside L2 | L3 |
| `v5.0.0-8.4.1` | 2 | Applicable | SCOPE |
| `v5.0.0-8.4.2` | 3 | Outside L2 | L3 |

## V9: Self-contained Tokens

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-9.1.1` | 1 | Conditional | SIGNED |
| `v5.0.0-9.1.2` | 1 | Conditional | SIGNED |
| `v5.0.0-9.1.3` | 1 | Conditional | SIGNED |
| `v5.0.0-9.2.1` | 1 | Conditional | SIGNED |
| `v5.0.0-9.2.2` | 2 | Conditional | SIGNED |
| `v5.0.0-9.2.3` | 2 | Conditional | SIGNED |
| `v5.0.0-9.2.4` | 2 | Conditional | SIGNED |

## V10: OAuth and OIDC

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-10.1.1` | 2 | Conditional | OIDC |
| `v5.0.0-10.1.2` | 2 | Conditional | OIDC |
| `v5.0.0-10.2.1` | 2 | Conditional | OIDC |
| `v5.0.0-10.2.2` | 2 | Conditional | OIDC |
| `v5.0.0-10.2.3` | 3 | Outside L2 | L3 |
| `v5.0.0-10.3.1` | 2 | N/A | NO_OAUTH_RESOURCE |
| `v5.0.0-10.3.2` | 2 | N/A | NO_OAUTH_RESOURCE |
| `v5.0.0-10.3.3` | 2 | N/A | NO_OAUTH_RESOURCE |
| `v5.0.0-10.3.4` | 2 | N/A | NO_OAUTH_RESOURCE |
| `v5.0.0-10.3.5` | 3 | Outside L2 | L3 |
| `v5.0.0-10.4.1` | 1 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.2` | 1 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.3` | 1 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.4` | 1 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.5` | 1 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.6` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.7` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.8` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.9` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.10` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.11` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.4.12` | 3 | Outside L2 | L3 |
| `v5.0.0-10.4.13` | 3 | Outside L2 | L3 |
| `v5.0.0-10.4.14` | 3 | Outside L2 | L3 |
| `v5.0.0-10.4.15` | 3 | Outside L2 | L3 |
| `v5.0.0-10.4.16` | 3 | Outside L2 | L3 |
| `v5.0.0-10.5.1` | 2 | Conditional | OIDC |
| `v5.0.0-10.5.2` | 2 | Conditional | OIDC |
| `v5.0.0-10.5.3` | 2 | Conditional | OIDC |
| `v5.0.0-10.5.4` | 2 | Conditional | OIDC |
| `v5.0.0-10.5.5` | 2 | Conditional | OIDC |
| `v5.0.0-10.6.1` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.6.2` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.7.1` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.7.2` | 2 | N/A | NO_OAUTH_SERVER |
| `v5.0.0-10.7.3` | 2 | N/A | NO_OAUTH_SERVER |

## V11: Cryptography

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-11.1.1` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.1.2` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.1.3` | 3 | Outside L2 | L3 |
| `v5.0.0-11.1.4` | 3 | Outside L2 | L3 |
| `v5.0.0-11.2.1` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.2.2` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.2.3` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.2.4` | 3 | Outside L2 | L3 |
| `v5.0.0-11.2.5` | 3 | Outside L2 | L3 |
| `v5.0.0-11.3.1` | 1 | Applicable | CRYPTO |
| `v5.0.0-11.3.2` | 1 | Applicable | CRYPTO |
| `v5.0.0-11.3.3` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.3.4` | 3 | Outside L2 | L3 |
| `v5.0.0-11.3.5` | 3 | Outside L2 | L3 |
| `v5.0.0-11.4.1` | 1 | Applicable | CRYPTO |
| `v5.0.0-11.4.2` | 2 | Applicable | PASSWORD_HASH |
| `v5.0.0-11.4.3` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.4.4` | 2 | Conditional | DERIVED_KEYS |
| `v5.0.0-11.5.1` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.5.2` | 3 | Outside L2 | L3 |
| `v5.0.0-11.6.1` | 2 | Applicable | CRYPTO |
| `v5.0.0-11.6.2` | 3 | Outside L2 | L3 |
| `v5.0.0-11.7.1` | 3 | Outside L2 | L3 |
| `v5.0.0-11.7.2` | 3 | Outside L2 | L3 |

## V12: Secure Communication

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-12.1.1` | 1 | Applicable | TRANSPORT |
| `v5.0.0-12.1.2` | 2 | Applicable | TRANSPORT |
| `v5.0.0-12.1.3` | 2 | Conditional | MTLS |
| `v5.0.0-12.1.4` | 3 | Outside L2 | L3 |
| `v5.0.0-12.1.5` | 3 | Outside L2 | L3 |
| `v5.0.0-12.2.1` | 1 | Applicable | TRANSPORT |
| `v5.0.0-12.2.2` | 1 | Applicable | TRANSPORT |
| `v5.0.0-12.3.1` | 2 | Applicable | TRANSPORT |
| `v5.0.0-12.3.2` | 2 | Applicable | TRANSPORT |
| `v5.0.0-12.3.3` | 2 | Applicable | TRANSPORT |
| `v5.0.0-12.3.4` | 2 | Applicable | TRANSPORT |
| `v5.0.0-12.3.5` | 3 | Outside L2 | L3 |

## V13: Configuration

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-13.1.1` | 2 | Applicable | CONFIG |
| `v5.0.0-13.1.2` | 3 | Outside L2 | L3 |
| `v5.0.0-13.1.3` | 3 | Outside L2 | L3 |
| `v5.0.0-13.1.4` | 3 | Outside L2 | L3 |
| `v5.0.0-13.2.1` | 2 | Applicable | CONFIG |
| `v5.0.0-13.2.2` | 2 | Applicable | CONFIG |
| `v5.0.0-13.2.3` | 2 | Applicable | CONFIG |
| `v5.0.0-13.2.4` | 2 | Applicable | CONFIG |
| `v5.0.0-13.2.5` | 2 | Applicable | CONFIG |
| `v5.0.0-13.2.6` | 3 | Outside L2 | L3 |
| `v5.0.0-13.3.1` | 2 | Applicable | CONFIG |
| `v5.0.0-13.3.2` | 2 | Applicable | CONFIG |
| `v5.0.0-13.3.3` | 3 | Outside L2 | L3 |
| `v5.0.0-13.3.4` | 3 | Outside L2 | L3 |
| `v5.0.0-13.4.1` | 1 | Applicable | CONFIG |
| `v5.0.0-13.4.2` | 2 | Applicable | CONFIG |
| `v5.0.0-13.4.3` | 2 | Applicable | CONFIG |
| `v5.0.0-13.4.4` | 2 | Applicable | CONFIG |
| `v5.0.0-13.4.5` | 2 | Applicable | CONFIG |
| `v5.0.0-13.4.6` | 3 | Outside L2 | L3 |
| `v5.0.0-13.4.7` | 3 | Outside L2 | L3 |

## V14: Data Protection

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-14.1.1` | 2 | Applicable | DATA |
| `v5.0.0-14.1.2` | 2 | Applicable | DATA |
| `v5.0.0-14.2.1` | 1 | Applicable | DATA |
| `v5.0.0-14.2.2` | 2 | Applicable | DATA |
| `v5.0.0-14.2.3` | 2 | Applicable | DATA |
| `v5.0.0-14.2.4` | 2 | Applicable | DATA |
| `v5.0.0-14.2.5` | 3 | Outside L2 | L3 |
| `v5.0.0-14.2.6` | 3 | Outside L2 | L3 |
| `v5.0.0-14.2.7` | 3 | Selected L3 | RETENTION |
| `v5.0.0-14.2.8` | 3 | Outside L2 | L3 |
| `v5.0.0-14.3.1` | 1 | Deviation | D01 |
| `v5.0.0-14.3.2` | 2 | Applicable | DATA |
| `v5.0.0-14.3.3` | 2 | Deviation | D02 |

## V15: Secure Coding and Architecture

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-15.1.1` | 1 | Applicable | ARCH |
| `v5.0.0-15.1.2` | 2 | Applicable | ARCH |
| `v5.0.0-15.1.3` | 2 | Applicable | ARCH |
| `v5.0.0-15.1.4` | 3 | Outside L2 | L3 |
| `v5.0.0-15.1.5` | 3 | Outside L2 | L3 |
| `v5.0.0-15.2.1` | 1 | Applicable | ARCH |
| `v5.0.0-15.2.2` | 2 | Applicable | ARCH |
| `v5.0.0-15.2.3` | 2 | Applicable | ARCH |
| `v5.0.0-15.2.4` | 3 | Outside L2 | L3 |
| `v5.0.0-15.2.5` | 3 | Outside L2 | L3 |
| `v5.0.0-15.3.1` | 1 | Applicable | ARCH |
| `v5.0.0-15.3.2` | 2 | Applicable | ARCH |
| `v5.0.0-15.3.3` | 2 | Applicable | ARCH |
| `v5.0.0-15.3.4` | 2 | Deviation | D03 |
| `v5.0.0-15.3.5` | 2 | Applicable | ARCH |
| `v5.0.0-15.3.6` | 2 | Applicable | ARCH |
| `v5.0.0-15.3.7` | 2 | Applicable | ARCH |
| `v5.0.0-15.4.1` | 3 | Selected L3 | CONCURRENCY |
| `v5.0.0-15.4.2` | 3 | Selected L3 | CONCURRENCY |
| `v5.0.0-15.4.3` | 3 | Selected L3 | CONCURRENCY |
| `v5.0.0-15.4.4` | 3 | Outside L2 | L3 |

## V16: Security Logging and Error Handling

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-16.1.1` | 2 | Applicable | LOG |
| `v5.0.0-16.2.1` | 2 | Applicable | LOG |
| `v5.0.0-16.2.2` | 2 | Applicable | LOG |
| `v5.0.0-16.2.3` | 2 | Applicable | LOG |
| `v5.0.0-16.2.4` | 2 | Applicable | LOG |
| `v5.0.0-16.2.5` | 2 | Applicable | LOG |
| `v5.0.0-16.3.1` | 2 | Applicable | LOG |
| `v5.0.0-16.3.2` | 2 | Applicable | LOG |
| `v5.0.0-16.3.3` | 2 | Applicable | LOG |
| `v5.0.0-16.3.4` | 2 | Applicable | LOG |
| `v5.0.0-16.4.1` | 2 | Applicable | LOG |
| `v5.0.0-16.4.2` | 2 | Applicable | LOG |
| `v5.0.0-16.4.3` | 2 | Applicable | LOG |
| `v5.0.0-16.5.1` | 2 | Applicable | LOG |
| `v5.0.0-16.5.2` | 2 | Applicable | LOG |
| `v5.0.0-16.5.3` | 2 | Applicable | LOG |
| `v5.0.0-16.5.4` | 3 | Outside L2 | L3 |

## V17: WebRTC

| Requirement | Level | Disposition | Rationale key |
| --- | --- | --- | --- |
| `v5.0.0-17.1.1` | 2 | N/A | NO_WEBRTC |
| `v5.0.0-17.1.2` | 3 | Outside L2 | L3 |
| `v5.0.0-17.2.1` | 2 | N/A | NO_WEBRTC |
| `v5.0.0-17.2.2` | 2 | N/A | NO_WEBRTC |
| `v5.0.0-17.2.3` | 2 | N/A | NO_WEBRTC |
| `v5.0.0-17.2.4` | 2 | N/A | NO_WEBRTC |
| `v5.0.0-17.2.5` | 3 | Outside L2 | L3 |
| `v5.0.0-17.2.6` | 3 | Outside L2 | L3 |
| `v5.0.0-17.2.7` | 3 | Outside L2 | L3 |
| `v5.0.0-17.2.8` | 3 | Outside L2 | L3 |
| `v5.0.0-17.3.1` | 2 | N/A | NO_WEBRTC |
| `v5.0.0-17.3.2` | 2 | N/A | NO_WEBRTC |

## S4 partial implementation evidence

The [S4 plan](../development/s4-implementation-plan.md) and
[threat-model handoff](access-threat-model.md#s4-implemented-control-boundary) record reproducible
configuration, resolver, controller/service signature, scoped persistence, Origin/Fetch Metadata,
restart/rollback, and diagnostic checks. They address the no-auth portions of SCOPE, CONFIG, HTTP,
FLOW, and LOG only. Classification counts, verification target, D01-D03 deviations, B01-B05 blockers,
and all authenticated/operational obligations remain unchanged; this is no full Level 2 claim.

## S5 partial implementation evidence

The [S5 plan](../development/s5-implementation-plan.md) records the browser FLOW/SCOPE boundary:
offline durable identity, physical data-set partitioning, identity-bearing leases and response/retry
fencing, atomic creation/commits, deterministic schema rejection without ownership inference, and
closed non-credential binding storage. The [threat-model handoff](access-threat-model.md#s5-implemented-local-boundary)
records scenario limits. Same-origin/shared-profile access, D01-D03 deviations, B01-B05 blockers,
authentication, supported binding transitions, and operator/privacy readiness remain separate gates;
classification counts and the Level 2 verification target remain unchanged.


## Historical S8 applicability refinement (superseded target)

[ADR-0033](../architecture/decisions/0033-built-in-authentication-and-sessions.md) selects mandatory
password plus user-verified WebAuthn and restricted two-proof lookup-secret recovery.
`v5.0.0-6.3.3`, `v5.0.0-6.4.4` and `v5.0.0-6.5.1` through `v5.0.0-6.5.4` are Applicable, with
implementation/verification pending. Unselected TOTP/out-of-band lifetime mechanisms remain Conditional.
The complete 345 unique-row register now has 168 Applicable, seven Selected L3, 40 Conditional, zero
Pending decision, three Deviations, 42 N/A and 85 Outside L2 requirements. This changes applicability,
not verification status. B01/B02 design is resolved; their executable evidence and B03-B05, D01-D03,
production deployment/privacy and full S15 review remain gates.

## R1 applicability reassessment (2026-10-17)

The authoritative register above contains 345 unique requirements: 171 Applicable, seven Selected L3,
37 Conditional, zero Pending decision, three Deviations, 42 N/A and 85 Outside L2. Compared with S8,
mail injection prevention (1.3.11), reset-token lifetime (6.5.5), and conservative mail-token binding/
abuse controls (6.6.2/6.6.3) become Applicable; lost-MFA-factor proof (6.4.4) becomes Conditional.
Token one-use/storage/randomness/entropy (6.5.1-6.5.4) stay Applicable for action secrets. MFA strength
(6.3.3) stays Applicable and unmet by the selected password-only design; it is never N/A. This is an
explicit product-strength gap requiring B01/S15 review, not a compliance pass. Email-as-login (6.3.6)
remains Outside L2; invitation/reset does not create a session or a second login factor.

Session documentation/expiry/concurrency rows (7.1.1, 7.1.2, 7.3.1, 7.3.2) remain Applicable. The target
has native thirty-minute request inactivity, no absolute lifetime or session cap; background traffic
can extend sessions indefinitely. Their appropriateness and any nonconformance need explicit S15
review. ACTION_LIFETIME also keeps the 24-hour activation-versus-ten-minute-reset distinction visible.
S15 must not infer a full row pass from the shorter reset bound alone.

R1 validates register coverage/count consistency and contract shapes only. New native login/session/
CSRF/logout, pending denial, atomic consumption, revocation, SMTP, timing and browser evidence remain
R2-R6; deployment, lawful basis, deletion/restore, D01-D03 and B01-B05 remain open S15 gates. Historical
S8/S9 evidence exercises the retired design and cannot satisfy these new requirements.

### R3 internal action-token and mail evidence — 2026-10-17

The [R3 implementation/review record](../development/spring-email-authentication-plan.md#r3-implementation-and-review--2026-10-17)
provides fresh bounded evidence for the ACTION_TOKENS/MAIL assumptions: verifier-only purpose-bound
JDBC storage, expiry/replacement/replay/concurrency/rollback, post-commit SMTP, fixed-origin plain-text
links, I/O/concurrency bounds and redacted diagnostics. No action endpoint, account mutation, host
workflow or browser replacement is enabled. Q1/Q2 and full response timing remain unresolved R6
closure requirements; local transport timing is not enumeration resistance. No register disposition,
requirement-level pass, ASVS-level result or S15 production finding changes through this increment.

## R6 evidence handoff (2026-10-19)

[R6](../development/spring-email-authentication-r6-evidence.md) owns fresh shared-delivery enforcement
and full replacement review evidence for AUTH/ACTION_DELIVERY/FLOW/CONCURRENCY/RETENTION/LOG. Unknown
addresses do not spend delivery quota; restricted pseudonymous PostgreSQL reservations survive host
process boundaries and cancellation. Native Spring login/session/CSRF/logout and scope/version
revocation remain authoritative. Exact requirement evidence and limitations must be assessed at S15;
this does not change any of the 345 dispositions/counts or imply a requirement/Level 2 pass.

ENUMERATION remains unverified: fixed 202 and a one-second response floor do not conceal slow SMTP
and all contention/network tails. STRENGTH/B01, ACTION_LIFETIME's invitation distinction, SESSION's
idle-only/no-cap policy, D01-D03 and B03-B05 retain their documented release/activation gates.
