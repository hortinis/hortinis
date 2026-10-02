# Open technical decisions

Accepted foundation technologies and policies are recorded in the architecture decision directory. The following narrower choices remain unresolved pending detailed workflow and technical specifications:

- concrete API resources and domain-specific validation rules;
- the authentication implementation library, factor strength (including ASVS `v5.0.0-6.3.3`), recovery
  mechanisms, CSRF mechanism, exact session limits, and concurrent-session policy under S8;
- any future roles, collaboration, or multi-scope selection beyond ADR-0029's one-account/one-scope
  ownership and separate operator powers;
- an optional offline encryption/unlock design if later required; ADR-0029 retains offline access after
  sign-out and documents its shared-device/XSS limits;
- detailed device-enrollment mechanisms, metadata cleanup limits, and recovery evidence under S8/S11;
- domain-specific synchronization merge semantics, conflict presentation, and conflict resolution beyond
  the generic record-level reconciliation outcomes accepted by ADR-0026;
- production reverse proxy and TLS examples;
- backup, restore, rollback, and upgrade procedures and their automated verification;
- the optional S3-compatible storage adapter implementation;
- the exact upstream catalog version pinned under `contracts/catalog/` and the consumer conformance-fixture update process; schema ownership, chunk sizing, first-release signing policy and publication cadence are resolved by ADR-0020;
- production details of the web catalog-artifact acquisition source boundary, including authenticated HTTPS, update discovery, rollback, quota failure and interrupted-import recovery; V0 uses explicitly selected local files and the common verification/activation pipeline;
- effective retention/cleanup periods for operational and security logs, account/session/device data,
  backups, and restore-suppression evidence; the initial proposals in the access data lifecycle require
  S8/S11/S15 and operator assessment before production activation;
- production internal/database transport, backend service-credential lifecycle, protected secret
  management and logically separate security-log collection, with ASVS evidence under S15;
- deployment-specific legal-purpose, rights, processor/transfer, incident and DPIA records, and S15
  review of the accepted offline-storage and source-address logging deviations;
- optional OpenTelemetry exporter selection;
- analytics aggregate schemas, browser-side buckets, dimension combinations, retention periods, bot-verification adapters, trusted proxy boundaries, contribution windows and cadence, sparse-bucket rules, differencing defenses, and any contribution noise or privacy budget required by ADR-0017 before analytics activation;
- web component-library and design-system selection, including whether to adopt Angular Material and how to integrate its theming model.

Do not resolve these implicitly while implementing unrelated work. New selections should capture context, alternatives, consequences, migration impact, and validation criteria where applicable.

See the [functional decisions and MVP scope](../product/functional-decisions.md) for accepted product
requirements and the [functional open questions](../product/open-questions.md) for remaining workflow
design. ADR-0029 and the [access specification](access-and-synchronization-scope.md) resolve the
optional-account modes, explicit configuration with no runtime fallback, scope ownership, binding,
sign-out, revocation outcomes, supported mode changes, and complete deletion lifecycle. Detailed
mechanisms and production evidence remain with the implementing tasks above.
