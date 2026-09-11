# Repository guidance

## Scope

Keep this repository sufficient to build, run, synchronize, document, and self-host Hortinis as an autonomous application.

## Repository language

- Write all repository content in English, including documentation, specifications, code identifiers, comments, tests, configuration descriptions, examples, prompts, and repository-authored interface and diagnostic text.
- Use English for descriptive file and directory names.
- Translate material from non-English discussions before adding it to the repository; preserve accepted decisions and do not maintain parallel non-English copies.
- Follow ADR-0018 in `docs/architecture/decisions/0018-repository-language.md` and include language consistency in change review.

## Architecture

- Start with one Angular application and one Spring Boot application project, organized internally by feature and supporting capability; extract build packages only for demonstrated reuse or independent enforcement needs.
- Keep business invariants and synchronization decisions independent of frameworks, transports, databases, storage engines, and vendors.
- Components and controllers delegate workflows to services; services may use framework dependency injection and concrete persistence components.
- Keep persistence and provider calls in dedicated components, expose small feature entry points, and avoid dependency cycles.
- Use narrow interfaces for replaceable providers, synchronization transport, and file/object storage; introduce other interfaces and separate models only when behavior or testing justifies them.
- Preserve explicit transaction boundaries and validate public wire contracts.
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
- Use Docker Compose for local orchestration and baseline self-hosting deployment; keep the Dev Container optional.
- Do not add production business features until the complete technical foundation is implemented and
  validated. A deliberately limited V0 product-validation slice may begin after the V0 technical
  readiness gate in `docs/development/foundation-implementation-plan.md`; it must use the real
  outbox-based synchronization boundaries and must not be presented as MVP or production readiness.

## Privacy

- Ship without telemetry, optional trackers, third-party analytics, or third-party runtime assets by default. Local first-party analytics may be enabled only within the privacy boundary and activation gates defined by ADR-0017.
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

Before submitting a change, run the checks documented in `docs/development/README.md`. Before the executable scaffold exists, run the documented pre-scaffold checks instead; do not treat the selected but not-yet-scaffolded toolchain as having runnable validation commands.
