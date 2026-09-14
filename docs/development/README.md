# Development

The selected development toolchain is:

- Node.js 24 LTS, pnpm, Angular, and strict TypeScript for the PWA;
- Java 25 LTS, Spring Boot 4, and the Gradle Wrapper with Kotlin DSL for the backend;
- PostgreSQL 18 and Flyway migrations;
- Docker Compose and an optional Dev Container;
- TypeSpec for language-neutral HTTP and JSON contracts, with generated OpenAPI 3.1 and JSON Schema Draft 2020-12;
- ESLint, Prettier, Vitest, Playwright, Checkstyle, Spotless, JUnit, AssertJ, ArchUnit, and Testcontainers for automated quality checks.

Validation commands are documented at the capability boundary that introduces them. Run each entry point independently when focusing on one toolchain or repository check, or run the full sequential suite from the repository root:

```shell
pnpm validate
```

The root command stops at the first failure and runs the existing checks without hiding their independent entry points. Browser validation requires the Playwright Chromium binary to be installed once as described below.

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

The expected versions are Node.js `v24.18.0` and pnpm `11.26.0`. The root `pnpm validate` command checks these versions and runs workspace discovery before application validation.

### Contracts

TypeSpec sources under `contracts/typespec` are the authority for HTTP and language-neutral JSON
contracts. OpenAPI documents and standalone JSON Schemas are generated artifacts. The D1 source has no
operations or payload models; the emitter compatibility fixture exercises representative wire shapes.

Run contract checks independently from the repository root:

```shell
pnpm contracts:format:check
pnpm contracts:check-generated
pnpm contracts:lint
pnpm contracts:test
pnpm contracts:validate
```

After editing TypeSpec, regenerate the committed OpenAPI with `pnpm contracts:generate`, then review the
source and generated diff. Generated files must not be edited manually. Validation rejects external
references and validates local references without contacting remote hosts. The Redocly launcher disables
telemetry and update notices. D2 adds the first production JSON Schemas; the standalone schema validator
is already exercised against the generated compatibility fixture.

### Web application

The standalone Angular application is the `@hortinis/web` workspace member under `apps/web`.

Run its validation entry points independently from the repository root:

```shell
pnpm --filter @hortinis/web format:check
pnpm --filter @hortinis/web lint
pnpm --filter @hortinis/web architecture:check
pnpm --filter @hortinis/web test:architecture
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

Run the browser smoke and offline application-shell tests independently:

```shell
pnpm --filter @hortinis/web test:e2e
```

Playwright builds the production bundle, serves its static output on `http://127.0.0.1:4200`, and stops the server afterward. The PWA test waits until the service worker has cached the application shell, enables browser offline mode, and verifies that reloading returns the cached shell from the service worker. Tests currently cover Chromium only; the supported browser and device matrix remains part of the product quality-envelope work.

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

The backend workspace requires a Java 25 JDK and uses Gradle 9.7.1 exclusively through the committed Gradle Wrapper. A separate system Gradle installation is neither required nor supported. The root build centralizes plugin and dependency repositories, rejects project-specific repositories, and applies the Java 25 toolchain and compiler release to the Spring Boot application project at `services/sync`.

Validate the Wrapper files before running the build:

```shell
sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256
```

The Wrapper also verifies the downloaded Gradle binary distribution against the SHA-256 checksum recorded in `gradle/wrapper/gradle-wrapper.properties`. The root has no external Java dependencies; the C5 Spring Boot application has reviewed dependency-verification metadata and committed dependency locks. Each later increment that adds a dependency must update and review both at the same time.

Validate the backend workspace independently of the frontend workspace:

```shell
java --version
./gradlew --version
./gradlew projects
./gradlew :services:sync:spotlessCheck
./gradlew :services:sync:checkstyleMain :services:sync:checkstyleTest
./gradlew :services:sync:pmdMain :services:sync:pmdTest
./gradlew --dependency-verification=strict :services:sync:test
./gradlew :services:sync:build
./gradlew :services:sync:bootRun
```

Java must report major version 25 and Gradle must report version 9.7.1. The projects report includes the single Spring Boot application project at `:services:sync`. `spotlessCheck` validates Java formatting with Google Java Format and Gradle Kotlin script formatting with ktlint. Checkstyle and PMD run independently through their source-set tasks. JUnit 5 is executed through Gradle's `test` task; Spring Boot's test starter provides AssertJ. The C5 application-context test verifies that the empty service starts without a database; `bootRun` starts and returns normally because the empty application does not yet include a web server or other non-daemon work. ArchUnit architecture checks will receive a separate command when internal package boundaries exist; no aggregate command should hide that entry point.

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
