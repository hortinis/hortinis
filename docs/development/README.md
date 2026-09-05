# Development

The selected development toolchain is:

- Node.js 24 LTS, pnpm, Angular, and strict TypeScript for the PWA;
- Java 25 LTS, Spring Boot 4, and the Gradle Wrapper with Kotlin DSL for the backend;
- PostgreSQL 18 and Flyway migrations;
- Docker Compose and an optional Dev Container;
- ESLint, Prettier, Vitest, Playwright, Checkstyle, Spotless, JUnit, AssertJ, ArchUnit, and Testcontainers for automated quality checks.

Exact setup and automated validation commands will be added with the executable scaffold.

Until that scaffold exists, validate every change by:

- running `git diff --check` against the change;
- reviewing all added or modified content and descriptive paths for English language consistency, as required by [ADR-0018](../architecture/decisions/0018-repository-language.md);
- verifying that every relative documentation link resolves to an existing file and anchor;
- checking that referenced repository paths agree with the planned layout in the root README and with accepted architecture decisions;
- reviewing the change for consistency with the dependency, offline-first, synchronization, privacy, and self-hosting rules;
- confirming that the change introduces no business functionality, secrets, generated credentials, local data, or environment-specific configuration.
