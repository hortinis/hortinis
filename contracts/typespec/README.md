# TypeSpec contract sources

TypeSpec is the source of truth for Hortinis HTTP and language-neutral JSON contracts. Its pinned
emitters produce committed OpenAPI 3.1 and JSON Schema Draft 2020-12 artifacts. Generated files are
reviewed with their source changes and must not be edited directly.

Run `pnpm contracts:generate` after editing `.tsp` files. Run `pnpm contracts:validate` to check
formatting, generated-file consistency, OpenAPI and schema validity, and the positive and negative
contract fixtures.

Keep authentication, authorization, resource behavior, and protocol choices within their approved
implementation increments. The D1 source intentionally defines service metadata but no operations or
payload models.
