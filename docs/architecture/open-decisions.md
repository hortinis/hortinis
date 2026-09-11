# Open technical decisions

Accepted foundation technologies and policies are recorded in the architecture decision directory. The following narrower choices remain unresolved pending detailed workflow and technical specifications:

- concrete API resources and domain-specific validation rules;
- authorization roles, permissions, and the authentication implementation library;
- the access design for an individually operated server without a mandatory account, its configuration default, and its relationship to the built-in-account baseline in ADR-0013;
- offline unlock, credential recovery, session expiry, and device revocation behavior;
- domain-specific conflict presentation and resolution;
- incremental-history and idempotency retention periods, synchronization-generation rollover, tombstone retention, compaction thresholds, the point-in-time snapshot and continuation mechanism, the indeterminate-operation outcome representation, and the full-reconciliation wire format;
- production reverse proxy and TLS examples;
- backup, restore, rollback, and upgrade procedures and their automated verification;
- the optional S3-compatible storage adapter implementation;
- the exact upstream catalog version pinned under `contracts/catalog/` and the consumer conformance-fixture update process; schema ownership, chunk sizing, first-release signing policy and publication cadence are resolved by ADR-0020;
- production details of the web catalog-artifact acquisition source boundary, including authenticated HTTPS, update discovery, rollback, quota failure and interrupted-import recovery; V0 uses explicitly selected local files and the common verification/activation pipeline;
- default retention periods for each operational and security log category;
- optional OpenTelemetry exporter selection;
- analytics aggregate schemas, browser-side buckets, dimension combinations, retention periods, bot-verification adapters, trusted proxy boundaries, contribution windows and cadence, sparse-bucket rules, differencing defenses, and any contribution noise or privacy budget required by ADR-0017 before analytics activation;
- web component-library and design-system selection, including whether to adopt Angular Material and how to integrate its theming model.

Do not resolve these implicitly while implementing unrelated work. New selections should capture context, alternatives, consequences, migration impact, and validation criteria where applicable.

See the [functional decisions and MVP scope](../product/functional-decisions.md) for accepted product requirements and the [functional open questions](../product/open-questions.md) for the remaining workflow design. The configurable account requirement is an accepted product decision; its access mechanism still needs an architecture decision before implementation.
