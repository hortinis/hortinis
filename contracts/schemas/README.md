# Generated JSON Schemas

These JSON Schema Draft 2020-12 files are generated from the TypeSpec technical synchronization models.
They are committed for cross-runtime consumers and validated without remote reference resolution.

Do not edit these files or add hand-maintained schema copies here. Run `pnpm contracts:generate` after
changing TypeSpec. Emitter compatibility probes and validator fixtures belong under
`tooling/contracts/fixtures/`; cross-runtime protocol examples belong under `contracts/sync/fixtures/`.

The production synchronization-policy schemas are generated before their runtime adapters. ADR-0023
through ADR-0026 define their invariants; G2 through G4 activate them across the browser and service.
