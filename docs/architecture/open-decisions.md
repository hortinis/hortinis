# Open technical decisions

The foundation technologies are selected in ADR-0007 through ADR-0016. The following narrower choices remain deliberately unresolved because no business workflow requires them yet:

- concrete API resources and domain-specific validation rules;
- authorization roles, permissions, and the authentication implementation library;
- offline unlock, credential recovery, session expiry, and device revocation behavior;
- domain-specific conflict presentation and resolution;
- incremental-history and idempotency retention periods, synchronization-generation rollover, tombstone retention, compaction thresholds, the point-in-time snapshot and continuation mechanism, the indeterminate-operation outcome representation, and the full-reconciliation wire format;
- production reverse proxy and TLS examples;
- backup, restore, rollback, and upgrade procedures and their automated verification;
- the optional S3-compatible storage adapter implementation;
- plant catalog chunk sizing, signing, key distribution, and update cadence;
- default retention periods for each operational and security log category;
- optional OpenTelemetry exporter selection;
- analytics aggregate schemas, browser-side buckets, dimension combinations, retention periods, bot-verification adapters, trusted proxy boundaries, contribution windows and cadence, sparse-bucket rules, differencing defenses, and any contribution noise or privacy budget required by ADR-0017 before analytics activation.

Do not resolve these implicitly while implementing unrelated work. Each selection must be captured in a decision record with context, alternatives, consequences, migration impact, and validation criteria.
