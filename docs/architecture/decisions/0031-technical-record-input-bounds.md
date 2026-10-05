# ADR-0031: Technical record input bounds

- Status: Accepted
- Date: 2026-10-04

## Context

H2 hardens the technical walking-skeleton record before additional synchronization work. Unbounded
values can exhaust parser resources. PostgreSQL cannot store NUL in text, and the pinned JDBC driver
silently converts lone UTF-16 surrogates to question marks. Both violate an unchanged value round trip.
A permanently rejected operation currently blocks later outbox work; quarantine belongs to H6.

## Decision

- Limit technical record values to 4,096 Unicode code points, counted after JSON decoding. Empty strings,
  whitespace, newlines, and supplementary characters remain valid. Combining marks count separately.
- Reject U+0000 and unpaired UTF-16 surrogates. Never trim, normalize, replace, or truncate a value.
- Express this rule once as a TypeSpec scalar used by operations and returned records. Keep equivalent
  pure rules in Java and TypeScript, checked against shared fixtures and both generated schema formats.
- Validate values at the HTTP parser and browser local commit boundary before persistence. Retain the
  existing framework-independent service validation. HTTP rejection uses the fixed
  `400 INVALID_REQUEST` response; local rejection uses a fixed validation error without the value.
- Cap the operation endpoint's complete request body at 65,536 bytes before JSON parsing, including
  whitespace and requests without Content-Length. Use a bounded servlet filter with no new dependency,
  rather than treating a parser's approximate document or string limit as an exact byte ceiling.
  A maximally escaped 4,096-code-point supplementary value needs 49,152 bytes plus operation metadata.
- Keep Jackson nesting and token constraints as defense in depth using Spring Boot 4.1.1's
  `spring.jackson.factory.constraints.read` properties. These limits are not the decoded value limit.
- Keep permanent rejections non-retryable under ADR-0027. Preserve existing pending operations and
  submitted identities. H2 does not add quarantine, skip rejected operations, or migrate Dexie storage.

## Compatibility and rollout

This is a tightening of the technical contract, including returned records. Before applying it to an
existing dataset, audit all technical record, journal, and receipt value columns for lengths over 4,096
code points. Audit browser outboxes and local accepted records as well; report counts only, never values.
Do not alter submitted bodies or accepted receipts to make them fit, and do not clear data automatically.

The validated baseline uses bounded synthetic fixtures and fresh isolated test databases. An operator
may explicitly discard a disposable walking-skeleton dataset. A dataset containing retained intent or
oversized accepted history requires an explicit compatibility/reconciliation decision before rollout;
H2 is not authorization to discard it. Legacy invalid pending work remains retained and may block
synchronization until H6 introduces quarantine and recovery.

## Consequences

- A value accepted by all boundaries round-trips unchanged, including valid emoji surrogate pairs.
- Invalid local commits leave the projection and outbox unchanged and do not start background recovery.
- The byte ceiling protects parsing even for whitespace, unknown properties, and chunked bodies.
- Input-caused persistence failures become controlled rejections. Unexpected infrastructure failures
  remain `500`; recognized temporary unavailability remains `503` under H1.
- No database schema change, new dependency, or production business feature is introduced.
