# Generated OpenAPI contracts

The OpenAPI documents in this directory are generated from the TypeSpec sources in
[`../typespec`](../typespec/README.md). Do not edit generated documents by hand. Change the TypeSpec
source, run `pnpm contracts:generate`, and review both the source and generated diff.

The versioned API contains the implemented technical push and pull operations required by the
synchronization walking skeleton, G2a's contract-first deletion variants, and the anchored reconciliation
endpoints selected by G1. G2b through G4 implement the production policy adapters. Garden resources and
unresolved authentication contracts remain outside this contract.
