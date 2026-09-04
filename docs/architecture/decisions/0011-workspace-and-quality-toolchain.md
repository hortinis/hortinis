# ADR-0011: Workspace and quality toolchain

- Status: Accepted

## Context

Hortinis is a polyglot monorepo. Contributors need reproducible commands and automated enforcement of layer boundaries.

## Decision

- Manage Angular and TypeScript packages with a pnpm workspace and one lockfile.
- Build Java modules with the Gradle Wrapper and Kotlin DSL in a multi-project build.
- Do not add a separate monorepo task orchestrator initially.
- Use strict TypeScript, Angular ESLint, Prettier, Vitest, and Playwright for the web application.
- Use the Java compiler, JUnit 5, AssertJ, ArchUnit, Testcontainers, Checkstyle, and Spotless for backend modules.
- Make formatting, linting, type checking, unit tests, integration tests, architecture tests, and builds independently runnable.

## Consequences

- pnpm commands govern only JavaScript and TypeScript workspaces.
- Gradle project dependencies enforce the build order and Java module boundaries.
- Wrapper distributions and dependencies are pinned and verified where their tooling supports checksums.
- CI invokes pnpm and Gradle explicitly so failures remain attributable to one toolchain.

