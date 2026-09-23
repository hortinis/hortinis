# API documentation

Hortinis API contracts are authored in TypeSpec under
[`contracts/typespec`](../../contracts/typespec/README.md). The pinned TypeSpec emitters generate the
committed OpenAPI 3.1 document under `contracts/openapi/` and standalone JSON Schemas under
`contracts/schemas/`. Generated files must not be edited by hand.

Contracts are defined before their Spring MVC adapters. If generated clients or boundary types are
introduced, they must be reproducible and must not be edited by hand.

## Implemented V0 technical synchronization API

The V0 walking-skeleton adapters implement these operations:

- `POST /api/v1/sync/operations` accepts or identically replays one technical-record operation;
- `GET /api/v1/sync/changes` pulls accepted changes after an optional opaque `cursor` query parameter.

The versioned path is the synchronization compatibility boundary. All contract objects are closed:
unknown properties are invalid.

The walking skeleton uses one deliberately technical record with a stable `recordId`, a server
`revision`, and a string `value`. It is not a garden resource and does not imply a future garden model.
G2a added deletion to the contract, and G2b implements it in the Spring and PostgreSQL adapters. Browser
application of tombstones remains in G2c. The operations are:

- `create`, which has no `expectedRevision` and fails when the record already exists;
- `replace`, which requires `expectedRevision` and fails when the record is absent or the current
  revision differs;
- `delete`, which requires `expectedRevision`, advances that revision by one, and produces a technical
  tombstone rather than a live record.

Record and operation identifiers are canonical lowercase UUID strings. The current browser generates
UUIDv7 values, but UUID version is not part of protocol validity. The `operationId` is also the
idempotency identifier. Retrying the same complete validated operation returns the stable result without
another state change, revision, or sequence. Reusing that identifier with a different validated operation
returns `OPERATION_ID_REUSED`. JSON whitespace and object-property order are not part of operation
identity. Valid operation requests are compared structurally by their typed fields, so whitespace and
object-property order remain insignificant. E1 and E9 provide the shared examples and cross-runtime
comparison evidence used by the adapters.

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
| `404` | `RECORD_NOT_FOUND` | A replacement or deletion targets an absent technical record. |
| `409` | `OPERATION_ID_REUSED` | An operation identifier was used with a different request. |
| `409` | `RECORD_ALREADY_EXISTS` | A creation targets an existing technical record. |
| `409` | `RECORD_IDENTIFIER_RETIRED` | A creation targets an identifier reserved by an accepted deletion. |
| `409` | `REVISION_CONFLICT` | The expected revision differs from the current revision. |

Record-existence and revision conflicts include the current accepted technical record where it exists.
This preserves enough information for the walking skeleton to represent server state and pending local
intent independently without selecting domain conflict resolution.

Creation with a stable identifier reserved by an accepted deletion returns
`RECORD_IDENTIFIER_RETIRED`. Accepted deletion results and incremental deletion changes contain the same
tombstone revision and deletion sequence. Result and change contracts are closed unions distinguished by
the presence of `record` or `tombstone`; existing live-record wire shapes remain unchanged.

## Selected production synchronization contract

ADR-0023 through ADR-0027 select the production retention, reconciliation, and retry behavior. The
TypeSpec source and generated artifacts define its wire shapes before G2 through G4 activate their
Spring and browser adapters.

The production operation envelope binds the original operation immutably to an opaque synchronization
generation. G2 first activates unbound expected-revision deletion; G3 then makes the generation envelope
mandatory for create, replacement, and deletion. An old-generation operation is never accepted as a new
operation in the current generation: a retained receipt returns its stable result, unavailable
incremental history returns `RECONCILIATION_REQUIRED`, and a missing receipt whose result cannot be
proved returns an `indeterminate` outcome.

The selected reconciliation API adds:

- `POST /api/v1/sync/reconciliations`, which captures one active generation, anchor sequence, expiry,
  and first opaque snapshot cursor;
- `GET /api/v1/sync/reconciliations/{reconciliationId}/snapshot`, which reads immutable pages from that
  anchor.

A non-final snapshot page has only `nextCursor`. The final page has only `incrementalCursor`, positioned
strictly after the anchor. Snapshot entries are a closed union of live records and retained tombstones.
`SNAPSHOT_EXPIRED` requires a new session without deleting the client's old base or pending work.

The generic reconciliation result is record-level. It can adopt server state, preserve a pending causal
chain, retain a conflict, or keep an operation indeterminate. Merging individual fields or deciding that
two domain operations are semantically equivalent remains a future domain-specific decision.

## Deliberate limitations

The contract does not define authentication, authorization, a production synchronization-scope
identifier, batching, garden resources, or domain-specific conflict resolution. The V0 validation
topology uses one test-only synchronization scope, retains all incremental history and idempotency
receipts, and must not expose an unauthenticated production service. G2b activates deletion on the server;
browser tombstone application remains in G2c. The production reconciliation endpoints remain unavailable
at runtime until G3 and G4 implement their adapters.
