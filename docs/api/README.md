# API documentation

Hortinis API contracts are authored in TypeSpec under
[`contracts/typespec`](../../contracts/typespec/README.md). The pinned TypeSpec emitters generate the
committed OpenAPI 3.1 document under `contracts/openapi/` and standalone JSON Schemas under
`contracts/schemas/`. Generated files must not be edited by hand.

Contracts are defined before their Spring MVC adapters. If generated clients or boundary types are
introduced, they must be reproducible and must not be edited by hand.

## Technical synchronization API

The version-one walking-skeleton contract exposes only these operations:

- `POST /api/v1/sync/operations` accepts or identically replays one technical-record operation;
- `GET /api/v1/sync/changes` pulls accepted changes after an optional opaque `cursor` query parameter.

Every top-level persisted envelope carries the numeric literal `protocolVersion: 1`. The versioned path
and envelope version are separate from record revisions and server sequences. All contract objects are
closed: unknown properties are invalid.

The walking skeleton uses one deliberately technical record with a stable `recordId`, a server
`revision`, and a string `value`. It is not a garden resource and does not imply a future garden model.
The accepted operations are:

- `create`, which has no `expectedRevision` and fails when the record already exists;
- `replace`, which requires `expectedRevision` and fails when the record is absent or the current
  revision differs.

Record and operation identifiers are canonical lowercase UUIDv7 strings. The `operationId` is also the
idempotency identifier. Retrying the same complete validated operation returns the stable result without
another state change, revision, or sequence. Reusing that identifier with a different validated operation
returns `OPERATION_ID_REUSED`. JSON whitespace and object-property order are not part of operation
identity. E1 and E9 provide the shared examples and cross-runtime canonicalization evidence used by the
adapters.

Revisions and sequences are positive integers encoded as decimal strings so browser and server runtimes
do not lose integer precision. A successful creation produces the first record revision; each successful
replacement produces its successor. Each newly accepted operation receives a monotonically increasing
server sequence in the synchronization scope. Identical retries retain the original revision and
sequence.

Pull results list changes in ascending server-sequence order and return a non-empty opaque `nextCursor`
for the complete returned page. Clients must store the page changes and its cursor atomically and must
not inspect or construct cursor values. `hasMore` indicates that another page is currently available.

The protocol defines the following explicit errors:

| HTTP status | Code | Meaning |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | The body, cursor, or protocol version is invalid. |
| `404` | `RECORD_NOT_FOUND` | A replacement targets an absent technical record. |
| `409` | `OPERATION_ID_REUSED` | An operation identifier was used with a different request. |
| `409` | `RECORD_ALREADY_EXISTS` | A creation targets an existing technical record. |
| `409` | `REVISION_CONFLICT` | The expected revision differs from the current revision. |

Record-existence and revision conflicts include the current accepted technical record where it exists.
This preserves enough information for the walking skeleton to represent server state and pending local
intent independently without selecting domain conflict resolution.

## Deliberate limitations

The contract does not define authentication, authorization, a production synchronization-scope
identifier, batching, deletion, tombstones, history or idempotency-receipt compaction, synchronization
generations, indeterminate outcomes, snapshots, or full reconciliation. The V0 validation topology uses
one test-only synchronization scope, retains all incremental history and idempotency receipts, and must
not expose an unauthenticated production service. Those production policies remain governed by Track G
and the open architecture decisions.
