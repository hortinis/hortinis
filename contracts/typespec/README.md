# TypeSpec contract sources

TypeSpec is the source of truth for Hortinis HTTP and language-neutral JSON contracts. Its pinned
emitters produce committed OpenAPI 3.1 and JSON Schema Draft 2020-12 artifacts. Generated files are
reviewed with their source changes and must not be edited directly.

Run `pnpm contracts:generate` after editing `.tsp` files. Run `pnpm contracts:validate` to check
formatting, generated-file consistency, OpenAPI and schema validity, and the positive and negative
contract fixtures.

The version-one source contains the implemented walking-skeleton operations and the selected production
reconciliation wire contract. ADR-0023 through ADR-0026 define the production policy. Their schemas and
reconciliation endpoints are contract-first inputs to G2 through G4; the current Spring and browser
adapters do not implement them yet. Keep authentication, authorization, garden resources, and
domain-specific conflict resolution within their approved implementation increments.
