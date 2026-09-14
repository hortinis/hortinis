# API documentation

Hortinis API contracts are authored in TypeSpec under [`contracts/typespec`](../../contracts/typespec/README.md). The pinned TypeSpec emitters generate the committed OpenAPI 3.1 documents under `contracts/openapi/` and standalone JSON Schemas under `contracts/schemas/`. Generated files must not be edited by hand.

Contracts are defined before their Spring MVC adapters. This section documents boundaries, versioning, authentication, authorization, validation, errors, idempotency, pagination where relevant, and compatibility expectations. If generated clients or boundary types are introduced, they must be reproducible and must not be edited by hand.

The initial D1 contract defines service metadata only. D2 adds the technical synchronization operations and envelopes. Garden resources and unresolved authentication contracts remain outside the technical walking skeleton.
