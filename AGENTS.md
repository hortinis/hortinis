# Repository guidance

## Scope

Keep this repository focused on the Hortinis Angular web application. Backend
services and frontend/backend synchronization are outside the current scope.
Do not restore them without an explicit request.

## Repository language

Write repository content in English, including documentation, identifiers,
comments, tests, examples, interface text, and diagnostics. Use English descriptive
file and directory names. Follow [ADR-0018](docs/architecture/decisions/0018-repository-language.md).

## Architecture

- Use Angular with strict TypeScript and pnpm. Node.js is frontend tooling only.
- Organize the application by feature and supporting capability.
- Keep business rules independent of frameworks and persistence.
- Components delegate workflows to services. Dedicated persistence components own
  IndexedDB access through Dexie and explicit transaction boundaries.
- Treat offline operation as the default application state.
- Keep PWA application-shell caching and local application data separate.
- Preserve existing local data when changing the IndexedDB schema; use versioned migrations.
- Record technology decisions and their rationale in `docs/architecture/decisions/`.

## Privacy

- Ship without telemetry, trackers, third-party analytics, or third-party runtime assets.
- Never log credentials, tokens, garden content, precise locations, or other user-provided content.
- Never commit secrets, local data, generated credentials, or environment-specific configuration.

## Changes and validation

Keep changes focused, add appropriate tests for changed behavior, and update
relevant documentation. Run the checks in [the development guide](docs/development/README.md).
Review changes for English language consistency and resolve relative documentation links.
