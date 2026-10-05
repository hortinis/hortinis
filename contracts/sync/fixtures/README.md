# Synchronization conformance fixtures

This directory contains the language-neutral examples consumed by protocol and contract tests. The
fixtures exercise the technical synchronization contract defined by D2 and the production policy wire
shapes selected by G1; they do not define garden resources or persistence behavior.

Operation fixtures use this scenario shape:

```json
{
  "id": "create-accepted",
  "description": "...",
  "request": { "...": "..." },
  "state": { "records": [] },
  "expected": {
    "valid": true,
    "canonicalRequest": "{...}",
    "response": { "...": "..." }
  }
}
```

`state.records` is the small declarative server state required to interpret the operation. It is not
a database fixture. Invalid cases set `expected.valid` to `false` and identify the expected protocol
error with `expected.errorCode`.

Identifiers are canonical UUID strings; UUID version is not part of protocol validity. The browser
currently generates UUIDv7 identifiers, but consumers must accept other valid UUID versions as well.

For operation-id retries, consumers validate both requests and compare their typed operation fields.
Clients may submit ordinary JSON: whitespace and object-property order remain insignificant. A reused
operation ID with a different field value is a distinct request and must be rejected.

Fixtures are intentionally ASCII-only where canonical bytes are asserted so the TypeScript and Java
consumers can focus on the protocol boundary. Generated TypeSpec artifacts and hand-maintained schema
copies do not belong here.

`synchronization-unavailable.json` is a schema-oriented value fixture with HTTP status and header
expectations. Contract tests validate its generated schema and the shared `503` union; the Java HTTP
mapping tests and TypeScript transport tests consume its response and retry guidance. It describes
temporary persistence unavailability separately from the access-contract fixture.

`capabilities.json` is the fixture inventory and consumer contract. Every JSON fixture in this directory
must appear exactly once. Implemented behavioral fixtures name both `typescript` and `java` consumers;
schema-oriented `value` fixtures name the contract consumer, and future capabilities remain explicitly
`planned`. Contract validation fails on inventory drift, and runtime conformance suites derive their
fixture lists from the manifest instead of maintaining separate hard-coded lists.

G1 and G2a policy fixtures use a smaller schema-oriented shape:

```json
{
  "id": "snapshot-final-page",
  "description": "...",
  "schema": "SnapshotPage.json",
  "value": { "...": "..." }
}
```

A fixture may use `values` when several variants must satisfy the same generated schema. Contract tests
consume these examples now; G3 through G5 update the capability status and cross-runtime consumers as
the corresponding behavior is implemented.

The shared S2 fixture `access-contracts.json` uses `suite: "access"` and capability
`access-contract-parsing`. Its `groups` each contain a generated `schema` name and `cases` with a
unique case ID, boolean `valid`, and JSON `value`. Schema tests and
dedicated TypeScript/Java parsers consume every case, including rejection examples. The manifest's
`implemented` status refers only to parsing; discovery, access resolution, and binding adapters remain
planned. HTTP models are checked against both emitters; local-only metadata is checked against its
standalone schema and both parsers. Legacy operation suites explicitly exclude other fixture suites.

H2 adds `technical-value-bounds` operation fixtures for NUL, excessive length, lone high/low surrogates,
and 4,096 supplementary code points. Unicode is escaped in these JSON files; no canonical byte
assertions are made for them. Java and TypeScript consume the same cases, and contract tests check
both create and replacement shapes against standalone and OpenAPI schemas. Value constraints also
apply to returned records. See ADR-0031 for code-point counting and unchanged-value requirements.
