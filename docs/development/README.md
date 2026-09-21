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

### Container topology

The Docker Compose development topology runs PostgreSQL 18 and the synchronization service from the
repository checkout. The pinned Gradle image supplies Gradle directly, so the container does not need to
download a second Gradle distribution through the wrapper. PostgreSQL readiness gates service startup,
and the service readiness probe then determines when the topology is healthy. Validate the Compose
document independently from the repository root:

```shell
pnpm compose:validate
```

With Docker available, export a local database password and start the topology:

```shell
export HORTINIS_POSTGRES_PASSWORD='choose-a-local-password'
docker compose --file infrastructure/docker/compose.yaml up --wait
```

The sync service is available on `http://127.0.0.1:8080`; PostgreSQL remains private to the Compose
network. Inspect service status with `docker compose --file infrastructure/docker/compose.yaml ps`.
The named `postgres-data` volume preserves database files across normal shutdown. Stop the topology
predictably with:

```shell
docker compose --file infrastructure/docker/compose.yaml down --remove-orphans
```

The first startup can take several minutes while Gradle and the application dependencies populate the
named `gradle-cache` volume. Do not start a second Gradle command using the same Compose service while
that build is running; it will contend for Gradle's cache lock. Follow the existing service instead with
`docker compose --file infrastructure/docker/compose.yaml logs --follow sync`.

The topology now applies the first technical Flyway migration at sync-service startup. It creates only the
walking-skeleton technical-record projection, accepted-operation receipts, and immutable change journal;
JDBC acceptance SQL is implemented in E3 and PostgreSQL integration tests remain owned by E3 and D6. Angular static-file
serving, same-origin edge routing, and production image builds belong to F3 and F2 respectively.

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
contracts. OpenAPI documents and standalone JSON Schemas are generated artifacts. The current contract
defines the implemented V0 technical synchronization push and pull operations plus the contract-first
production reconciliation endpoints and schemas selected by G1. The emitter compatibility fixture
separately exercises representative wire shapes. The
[production synchronization traceability matrix](synchronization-policy-traceability.md) records which
later increment implements and validates each selected policy.

Run contract checks independently from the repository root:

```shell
pnpm contracts:format:check
pnpm contracts:check-generated
pnpm contracts:lint
pnpm contracts:test
pnpm contracts:validate
```

After editing TypeSpec, regenerate the committed artifacts with `pnpm contracts:generate`, then review
the source and generated diff. Generation also removes obsolete generated contract files while
preserving the directory documentation. Generated files must not be edited manually. Validation rejects
external references and validates local references without contacting remote hosts. The Redocly launcher
disables telemetry and update notices. Standalone production schemas are compiled together so their
local references and the technical protocol constraints are validated.

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

The backend workspace requires a Java 25 JDK and uses Gradle 9.7.1 exclusively through the committed Gradle Wrapper. A separate system Gradle installation is neither required nor supported. The root build centralizes plugin and dependency repositories, rejects project-specific repositories, and applies the Java 25 toolchain and compiler release to the Spring Boot application project at `services/sync`. The service exposes status-only health, liveness, and readiness probes under `/actuator` and emits minimized structured JSON logs to stdout; deployment-specific log collection and rotation are defined separately.

Validate the Wrapper files before running the build:

```shell
sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256
```

The Wrapper also verifies the downloaded Gradle binary distribution against the SHA-256 checksum recorded in `gradle/wrapper/gradle-wrapper.properties`. The C6 and D3 Spring Boot application dependencies have reviewed dependency-verification metadata and committed dependency locks. The application uses PostgreSQL through Spring JDBC; native `bootRun` therefore requires datasource variables (the Compose topology supplies them). Each later increment that adds a dependency must update and review the lockfile and verification metadata together.

Update metadata:
```shell
./gradlew --write-verification-metadata sha256
```

Validate the backend workspace independently of the frontend workspace:

```shell
java --version
./gradlew --version
./gradlew projects
./gradlew :services:sync:spotlessCheck
./gradlew :services:sync:checkstyleMain :services:sync:checkstyleTest :services:sync:checkstyleIntegrationTest
./gradlew :services:sync:pmdMain :services:sync:pmdTest :services:sync:pmdIntegrationTest
./gradlew --dependency-verification=strict :services:sync:test
./gradlew --dependency-verification=strict :services:sync:integrationTest
./gradlew :services:sync:build
./gradlew :services:sync:bootRun
```

Java must report major version 25 and Gradle must report version 9.7.1. The projects report includes the single Spring Boot application project at `:services:sync`. `spotlessCheck` validates Java formatting with Google Java Format and Gradle Kotlin script formatting with ktlint. Checkstyle and PMD run independently through their source-set tasks. JUnit 5 is executed through the database-independent `test` task and the PostgreSQL-backed `integrationTest` task; the latter requires access to a Docker-compatible daemon and starts a clean PostgreSQL 18 Testcontainers instance. The application-context tests explicitly exclude datasource auto-configuration so they remain database-independent; Compose startup validates the real JDBC connection. `bootRun` starts the web server and remains active while the service is running when datasource variables are configured; stop it with `Ctrl+C` after checking the probes. ArchUnit architecture checks remain outside D3+D4 and will receive a separate command when C7 establishes the internal package boundaries.

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
