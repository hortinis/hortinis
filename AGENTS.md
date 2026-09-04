# Repository guidance

## Scope

Keep this repository sufficient to build, run, synchronize, document, and self-host Hortinis as an autonomous application.

## Architecture

- Preserve the dependency direction `Presentation -> Application -> Domain`.
- Keep infrastructure behind interfaces defined by inner layers.
- Keep domain code independent of frameworks, transports, databases, storage engines, and vendors.
- Treat offline operation as the default application state.
- Make synchronization explicit, deterministic, observable, and recoverable.
- Access external providers only through replaceable adapters.
- Treat the external plant catalog as versioned reference data and persist its stable identifiers.

## Validated foundation

- Build the PWA with Angular and strict TypeScript, using Node.js only for frontend tooling.
- Build the backend with Java 25 LTS, Spring Boot 4, Spring MVC, and Gradle using the Kotlin DSL.
- Use pnpm workspaces for JavaScript and TypeScript dependencies.
- Define HTTP/JSON contracts with OpenAPI 3.1 before implementing adapters.
- Use IndexedDB through Dexie for browser persistence.
- Use PostgreSQL through Spring JDBC and version its schema with Flyway SQL migrations.
- Use Docker Compose for local orchestration and keep the Dev Container optional.
- Do not add business features until the technical foundation is implemented and validated.

## Privacy

- Ship without telemetry, analytics, optional trackers, or third-party runtime assets by default.
- Never log credentials, tokens, request or response bodies, garden content, precise locations, or other user-provided content.
- Keep logs structured, minimized, redacted, access-controlled, and subject to documented retention and purge rules.
- External providers are opt-in adapters selected by the operator.

## Changes

- Do not select a technology without recording the decision and its rationale in `docs/architecture/decisions/`.
- Keep placeholders when a decision has not yet been made.
- Add tests at the appropriate level for each behavior introduced.
- Update documentation when changing a public contract or architectural boundary.
- Never commit secrets, generated credentials, local data, or environment-specific configuration.

## Validation

Before submitting a change, run the checks documented for the selected toolchain. Until a toolchain is chosen, verify the directory structure, internal links, and architectural consistency manually.
