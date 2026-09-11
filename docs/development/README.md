# Development

The selected development toolchain is:

- Node.js 24 LTS, pnpm, Angular, and strict TypeScript for the PWA;
- Java 25 LTS, Spring Boot 4, and the Gradle Wrapper with Kotlin DSL for the backend;
- PostgreSQL 18 and Flyway migrations;
- Docker Compose and an optional Dev Container;
- ESLint, Prettier, Vitest, Playwright, Checkstyle, Spotless, JUnit, AssertJ, ArchUnit, and Testcontainers for automated quality checks.

Capability-specific setup and automated validation commands are added as each part of the executable foundation is introduced.

The [P0.7a local catalog validation contract](catalog-validation-contract.md) defines the non-production
catalog profile and cross-repository handoff required before local catalog acquisition is implemented.

## Frontend workspace

The frontend workspace requires Node.js 24.18.0 and pnpm 11.26.0. The Node.js version is recorded in `.nvmrc`; the root package manifest enforces both tool versions.

Install the pinned pnpm version with the npm CLI supplied by Node.js. Use a user-writable global npm prefix; do not elevate this command solely to install pnpm:

```shell
npm install --global pnpm@11.26.0
```

Install the workspace from its committed lockfile:

```shell
pnpm install --frozen-lockfile
```

Validate the empty workspace foundation independently of the future backend build:

```shell
node --version
pnpm --version
pnpm install --frozen-lockfile
pnpm list --recursive --depth -1
```

The expected versions are Node.js `v24.18.0` and pnpm `11.26.0`. Formatting, linting, type checking, tests, and builds will receive separate commands when their corresponding executable capabilities are introduced.

## Backend workspace

The backend workspace requires a Java 25 JDK and uses Gradle 9.7.1 exclusively through the committed Gradle Wrapper. A separate system Gradle installation is neither required nor supported. The root build centralizes plugin and dependency repositories, rejects project-specific repositories, and applies the Java 25 toolchain and compiler release to future Java subprojects.

Validate the Wrapper files before running the build:

```shell
sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256
```

The Wrapper also verifies the downloaded Gradle binary distribution against the SHA-256 checksum recorded in `gradle/wrapper/gradle-wrapper.properties`. The root has no external Java dependencies, so `gradle/verification-metadata.xml` intentionally begins with an empty component list. Each increment that adds a dependency must add and review its verification metadata at the same time.

Validate the empty backend multi-project foundation independently of the frontend workspace:

```shell
java --version
./gradlew --version
./gradlew projects
./gradlew build
```

Java must report major version 25 and Gradle must report version 9.7.1. The projects report contains only the root project until backend modules are introduced by C1. Formatting, unit tests, integration tests, architecture tests, and module builds will receive separate commands when their corresponding executable capabilities are introduced.

## Pre-scaffold checks

For repository areas that do not yet have executable validation, validate every change by:

- running `git diff --check` against the change;
- reviewing all added or modified content and descriptive paths for English language consistency, as required by [ADR-0018](../architecture/decisions/0018-repository-language.md);
- verifying that every relative documentation link resolves to an existing file and anchor;
- checking that referenced repository paths agree with the planned layout in the root README and with accepted architecture decisions;
- reviewing the change for consistency with the dependency, offline-first, synchronization, privacy, and self-hosting rules;
- confirming that the change introduces no business functionality, secrets, generated credentials, local data, or environment-specific configuration.
