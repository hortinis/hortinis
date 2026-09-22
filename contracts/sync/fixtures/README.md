# Synchronization conformance fixtures

This directory contains the language-neutral examples consumed by protocol and contract tests. The
fixtures exercise the technical synchronization contract defined by D2 and the production policy wire
shapes selected by G1; they do not define garden resources or persistence behavior.

Each JSON file is one scenario with this shape:

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
consume these examples now; G2b through G5 extend cross-runtime consumers as the corresponding behavior
is implemented.
