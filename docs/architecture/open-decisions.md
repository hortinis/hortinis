# Open technical decisions

The foundation technologies are selected in ADR-0007 through ADR-0016. The following narrower choices remain deliberately unresolved because no business workflow requires them yet:

- concrete API resources and domain-specific validation rules;
- authorization roles, permissions, and the authentication implementation library;
- offline unlock, credential recovery, session expiry, and device revocation behavior;
- domain-specific conflict presentation and resolution;
- tombstone retention, replica expiry, compaction, and full-resynchronization policy;
- production reverse proxy and TLS examples;
- backup, restore, rollback, and upgrade procedures and their automated verification;
- the optional S3-compatible storage adapter implementation;
- plant catalog chunk sizing, signing, key distribution, and update cadence;
- default retention periods for each operational and security log category;
- optional OpenTelemetry exporter selection.

Do not resolve these implicitly while implementing unrelated work. Each selection must be captured in a decision record with context, alternatives, consequences, migration impact, and validation criteria.
