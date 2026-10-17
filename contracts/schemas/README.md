# Generated JSON Schemas

These JSON Schema Draft 2020-12 files are generated from the TypeSpec synchronization, access and authentication models.
They are committed for cross-runtime consumers and validated without remote reference resolution.

Do not edit these files or add hand-maintained schema copies here. Run `pnpm contracts:generate` after
changing TypeSpec. Emitter compatibility probes and validator fixtures belong under
`tooling/contracts/fixtures/`; cross-runtime protocol examples belong under `contracts/sync/fixtures/`.

The production synchronization-policy schemas are generated before their runtime adapters. ADR-0023
through ADR-0027 define their invariants; G2a activates deletion wire shapes and G2b through G4 activate
the corresponding browser and service behavior.

S9-R R1 authentication schemas describe verified-email/password and purpose-bound invitation/reset
actions before runtime replacement. Historical MFA/ticket schemas are removed from this target;
passing structural validation does not validate native login, SMTP or token transactions. See the
[authentication handoff](../../docs/api/authentication-contracts.md).
