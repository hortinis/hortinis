# Development

The selected development toolchain is:

- Node.js 24 LTS, pnpm, Angular, and strict TypeScript for the PWA;
- Java 25 LTS, Spring Boot 4, and the Gradle Wrapper with Kotlin DSL for the backend;
- PostgreSQL 18 and Flyway migrations;
- Docker Compose and an optional Dev Container;
- ESLint, Prettier, Vitest, Playwright, Checkstyle, Spotless, JUnit, AssertJ, ArchUnit, and Testcontainers for automated quality checks.

Validation commands are documented at the capability boundary that introduces them. Run each entry point independently so a failure remains attributable to one toolchain or repository check. The repository does not use a separate monorepo task orchestrator.

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

The workspace explicitly permits install scripts for `@parcel/watcher`, `esbuild`, `lmdb`, and `msgpackr-extract`. These reviewed transitive dependencies provide Angular build, Sass file-watching, and build-cache capabilities. Review and record any future lifecycle-script dependency before allowing it.

Validate the frontend workspace independently of the future backend build:

```shell
node --version
pnpm --version
pnpm install --frozen-lockfile
pnpm list --recursive --depth -1
```

The expected versions are Node.js `v24.18.0` and pnpm `11.26.0`. No aggregate command hides the independent validation entry points.

### Web application

The standalone Angular application is the `@hortinis/web` workspace member under `apps/web`.

Run its validation entry points independently from the repository root:

```shell
pnpm --filter @hortinis/web format:check
pnpm --filter @hortinis/web lint
pnpm --filter @hortinis/web typecheck
pnpm --filter @hortinis/web test
pnpm --filter @hortinis/web build:development
pnpm --filter @hortinis/web build
```

The formatting check validates the committed web application files against the Angular CLI-generated Prettier configuration. The lint command validates TypeScript, component templates, and accessibility rules with the recommended Angular ESLint flat configuration. The type-checking command validates strict TypeScript and Angular templates. The two build commands validate the development and production configurations separately. Browser end-to-end testing is introduced by B3.

Install the Playwright Chromium binary once before running browser tests:

```shell
pnpm --filter @hortinis/web exec playwright install chromium
```

Run the B3 browser smoke test independently:

```shell
pnpm --filter @hortinis/web test:e2e
```

Playwright starts the Angular development server on `http://127.0.0.1:4200` for the test and stops it afterward. The test currently covers Chromium only; the supported browser and device matrix remains part of the product quality-envelope work.

Apply supported lint fixes followed by configured formatting with the ordered convenience command:

```shell
pnpm --filter @hortinis/web fix
```

The combined command runs Angular ESLint fixes first and Prettier formatting second. Both mutating steps also remain independently runnable:

```shell
pnpm --filter @hortinis/web format
pnpm --filter @hortinis/web lint:fix
```

## Backend workspace

The backend workspace requires a Java 25 JDK and uses Gradle 9.7.1 exclusively through the committed Gradle Wrapper. A separate system Gradle installation is neither required nor supported. The root build centralizes plugin and dependency repositories, rejects project-specific repositories, and applies the Java 25 toolchain and compiler release to the future Spring Boot application project.

Validate the Wrapper files before running the build:

```shell
sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256
```

The Wrapper also verifies the downloaded Gradle binary distribution against the SHA-256 checksum recorded in `gradle/wrapper/gradle-wrapper.properties`. The root has no external Java dependencies, so `gradle/verification-metadata.xml` intentionally begins with an empty component list. Each increment that adds a dependency must add and review its verification metadata at the same time.

Validate the existing empty backend root build independently of the frontend workspace:

```shell
java --version
./gradlew --version
./gradlew projects
./gradlew build
```

Java must report major version 25 and Gradle must report version 9.7.1. The projects report contains only the root project until the single Spring Boot application project at `services/sync` is introduced by C5. Formatting, unit tests, integration tests, architecture tests, and application builds will receive separate commands when their corresponding executable capabilities are introduced; no aggregate command should hide those entry points.

## Repository-wide validation

Run the checks applicable to every change independently from the repository root:

```shell
git diff --check
git ls-files -ci --exclude-standard
git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat
git ls-files --eol
```

These commands cover whitespace, tracked-file ignore rules, and the repository's text and line-ending conventions. For repository areas that do not yet have executable validation, also:

- reviewing all added or modified content and descriptive paths for English language consistency, as required by [ADR-0018](../architecture/decisions/0018-repository-language.md);
- verifying that every relative documentation link resolves to an existing file and anchor;
- checking that referenced repository paths agree with the planned layout in the root README and with accepted architecture decisions;
- reviewing the change for consistency with the dependency, offline-first, synchronization, privacy, and self-hosting rules;
- confirming that the change introduces no business functionality, secrets, generated credentials, local data, or environment-specific configuration.
