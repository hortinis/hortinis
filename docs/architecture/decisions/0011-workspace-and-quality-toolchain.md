# ADR-0011: Workspace and quality toolchain

- Status: Accepted

## Context

Hortinis is a polyglot monorepo. Contributors need reproducible commands and focused dependency checks without mandatory build modules for each responsibility.

## Decision

- Manage Angular and TypeScript packages with a pnpm workspace and one lockfile.
- Use the Gradle Wrapper and Kotlin DSL, with one Spring Boot application project at `services/sync` under the existing root build. Do not create separate domain, application, protocol, or adapter build modules initially.
- Do not add a separate monorepo task orchestrator initially. Provide an optional root `pnpm validate` command that runs the existing independent checks sequentially, without task caching, dependency graphs, or parallel scheduling.
- Use strict TypeScript, Angular ESLint, Prettier, Vitest, and Playwright for the web application.
- Use the Java compiler, JUnit 5, AssertJ, ArchUnit, Testcontainers, Checkstyle, and Spotless for the backend application.
- Make formatting, linting, type checking, unit tests, integration tests, architecture tests, and builds independently runnable.

## Consequences

- pnpm commands govern only JavaScript and TypeScript workspaces.
- ESLint and ArchUnit checks protect pure rules, detect dependency cycles, and prevent components/controllers from accessing persistence directly within each application. Separate build packages are introduced only for demonstrated reuse or independent enforcement needs.
- Wrapper distributions and dependencies are pinned and verified where their tooling supports checksums.
- Contributors can use `pnpm validate` for the complete local validation sequence or invoke each pnpm, Gradle, and repository check independently. The wrapper stops at the first failure and introduces no additional dependencies.
- CI may continue to invoke pnpm and Gradle checks explicitly so failures remain attributable to one toolchain.
