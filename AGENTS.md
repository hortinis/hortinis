# Repository guidance

## Scope

Keep this repository sufficient to build, run, synchronize, document, and self-host Hortisys as an autonomous application.

## Architecture

- Preserve the dependency direction `Presentation -> Application -> Domain`.
- Keep infrastructure behind interfaces defined by inner layers.
- Keep domain code independent of frameworks, transports, databases, storage engines, and vendors.
- Treat offline operation as the default application state.
- Make synchronization explicit, deterministic, observable, and recoverable.
- Access external providers only through replaceable adapters.
- Treat the external plant catalog as versioned reference data and persist its stable identifiers.

## Changes

- Do not select a technology without recording the decision and its rationale in `docs/architecture/decisions/`.
- Keep placeholders when a decision has not yet been made.
- Add tests at the appropriate level for each behavior introduced.
- Update documentation when changing a public contract or architectural boundary.
- Never commit secrets, generated credentials, local data, or environment-specific configuration.

## Validation

Before submitting a change, run the checks documented for the selected toolchain. Until a toolchain is chosen, verify the directory structure, internal links, and architectural consistency manually.
