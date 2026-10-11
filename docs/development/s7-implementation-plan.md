# S7 implementation plan

## Analysis and scope

S3 partitions PostgreSQL, S4 resolves trusted no-auth authority, S5 partitions browser ownership,
and S6 binds and fences real exchange. Existing topology exercises one configured scope. S7 must
prove the complete browser/HTTP/service/PostgreSQL path with A1 and A2 sharing scope A and device B
using scope B, without enabling authentication or a production scope selector.

Reuse the existing Spring application and replaceable access resolver only in a separate test source
set. Add a test-only launcher, controlled idempotent scope-A/B setup, and a closed synthetic-device
mapping. Its HTTP test marker is not a credential, account selector, or production authorization
feature. Main bootRun and executable jars must exclude the launcher, resolver and provisioning code;
a profile/property cannot activate them. Keep startup mode and Origin protections unchanged. Missing
markers retain the designated sole technical scope only for the existing synthetic regressions; unknown markers deny generically.

The designated sole technical scope remains separate for the S6/G2 regressions. Explicit A1/A2/B
markers select two additional controlled fixture scopes, so test ordering cannot consume their empty
first-binding boundary.

Use fresh database volumes and browser contexts. Bind empty scopes explicitly before content is
published. A2 confirms while A is still empty and completes an initial empty pull before A1 publishes;
this represents a supported established device, not a new populated-state admission bypass. Device B
binds its independently empty scope. No production binding override or client ownership inference is
introduced. S13 owns generations; this step proves generationless G2 isolation and preserves that
explicit limitation.

## Implementation sequence

1. Add a dedicated topology-test source set and JavaExec launcher, inheriting existing runtime
   dependencies. Provide controlled test-only A/B provisioning and a trusted closed A1/A2/B mapping.
   Extend the disposable Compose test override to run this task, preserving production bootRun.
   Update dependency locks for the new configurations without changing library choices.
2. Add artifact/architecture and runner/Compose evidence that production builds cannot load or activate
   the test selector or provisioning code. Deny unknown, malformed, duplicate markers in the fixture;
   retain current independent no-auth resolver tests and stable public/wire models.
3. Add browser contexts for A1/A2/B and explicit empty-first binding. Use the actual outbox, HTTP
   transport, retries, leases, journal pages and PostgreSQL. Exercise colliding record/operation IDs,
   dependent edits, ambiguous acknowledgement replay, tombstone/deletion, reload and committed cursors.
   A2 sees A's latest state; B sees only its independent state. Include identical operation IDs with
   distinct values and assert independent receipts/journal counts rather than just UI absence.
4. Substitute A's expectation/cursor into B and vice versa; verify generic closed no-store errors and
   no foreign content/existence evidence. Check that arbitrary query/header IDs do not select scopes.
   Capture browser/server diagnostics and assert exclusion of content, stable IDs, expectations,
   credential canaries and raw device markers. Keep the fixture's diagnostics fixed and minimized.
5. Restart the real service with its persistent database. Verify installation/scope identity, retained
   expectations within TTL, replay outcomes and browser cursor recovery remain unchanged. Assert
   independent local owners and lease/retry state across device contexts, including delayed replies
   where covered by S5/S6, and explicitly retain their complementary transactional evidence.
6. Review the implementation and test-only artifact boundary, fix findings, update access/security/
   privacy/development handoffs and G2f evidence, run pnpm validate, commit S7, then analyze S8.

## Acceptance limits

The fixture simulates trusted access contexts; it proves scope isolation, not authenticated user or
device authorization. No enrollment, session, collaboration, production scope selection, generation
claims, G4 reconciliation, migration, or security/privacy release readiness is added. S8-S15 retain
those gates. Test-only scope setup is separate from ordinary requests and absent from production.
