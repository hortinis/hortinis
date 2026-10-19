# Development

The [Spring email/password replacement plan](spring-email-authentication-plan.md) (S9-R) is implemented
and validated under closed production admission. R1 finalized policy/contracts and inventories. R2 replaces the native backend boundary with
normal authenticated startup and old host workflows closed. R3 supplies internal JDBC action-token
and bounded Boot SMTP primitives. R4 adds account-action adapters and isolated host invitations;
R5 replaces the browser; R6 shared accounting, review and full validation are complete. Production
activation remains closed pending separate explicit approval. See the [R6 evidence](spring-email-authentication-r6-evidence.md).
Earlier S8/S9 descriptions below are historical where they require passkeys/MFA, recovery codes or
operator-ticket browser recovery. Generated authentication contracts now target S9-R ahead of runtime.

The proposed [G2e review hardening plan](g2e-review-hardening-plan.md) records findings from an external
review, implementation dependencies, and decisions to resolve before the affected work begins.

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

### S9-R authentication contract handoff

[ADR-0034](../architecture/decisions/0034-spring-email-password-authentication.md) and the
[authentication/session specification](../architecture/authentication-and-sessions.md) replace the S8
MFA design. R1 defines native form login, sessions/CSRF/logout and email action-token contracts before
adapters, plus SMTP/privacy/threat/ASVS reconciliation and the
[production-code baseline](spring-email-authentication-r1-inventory.md). TypeSpec/OpenAPI describes the
complete authentication surface. R2 implements only native form login, sessions, CSRF/logout and
verified-email/current-version authority, exercised through test-source assembly while production
admission is closed. R3 supplies shared token/mail primitives and bounded cleanup. R4 implements
invitation/reset/password adapters and the isolated invite/resend/cancel commands; R5 owns the browser
replacement. R6 resolves Q1/Q2 through shared PostgreSQL delivery accounting and actual
HTTP/host evidence; timing resistance remains unverified.
The R1 contract tests check shape, not runtime security; use the R2 evidence record for actual native
boundary checks. S10 and production gates remain blocked; historical S8/S9 evidence does not validate
this design.

### R5 authenticated browser evidence

Run the replacement browser fixture independently:

```shell
pnpm test:e2e:authentication
```

The command builds the real production Angular bundle and runs Gradle's separate
`:services:sync:authenticationBrowserTest` task. Its integration-test-only assembly selects the
production native security/account/synchronization graph while omitting the closed admission bean.
Neither production archive includes the fixture, its controls or a configurable bypass. Ordinary
`integrationTest` excludes this browser launcher; `pnpm validate` runs it separately.

The fixture uses disposable PostgreSQL 18, a loopback HTTPS edge, a generated one-day certificate
pinned by its public key in Chromium, and synthetic local SMTP only. The edge proxies native cookies,
form bodies and API responses unchanged. Fixture controls reset synthetic accounts, delivery ledger rows and transient request state
between scenarios, issue/expire test tokens and select SMTP refusal. SMTP still uses the real mail
adapter and transport. R6 exercises the real shared delivery store in this fixture and adds actual host/HTTP interleaving
evidence separately. These are not timing-resistance or authenticated-production-startup claims. It requires Chromium, Docker,
Node.js and Java; temporary TLS/output files and disposable containers are removed afterward.
Browser tracing is disabled for these secret-bearing synthetic workflows; diagnostics are fixed or
redacted. The production bundle and both backend archives have replacement-isolation checks.

### Container topology

The Docker Compose development topology runs PostgreSQL 18 and the synchronization service from the
repository checkout. The pinned Gradle image supplies Gradle directly, so the container does not need to
download a second Gradle distribution through the wrapper. PostgreSQL readiness gates service startup,
and the service readiness probe then determines when the topology is healthy. Validate the Compose
document independently from the repository root:

```shell
pnpm compose:validate
```

Docker Engine with the Compose plugin is required for this topology and for PostgreSQL-backed
integration tests. Export an explicit access mode and a local database password before validation
or startup. This example deliberately starts the development topology in `single-user-no-auth` mode:

```shell
export HORTINIS_POSTGRES_PASSWORD='choose-a-local-password'
export HORTINIS_ACCESS_MODE='single-user-no-auth'
export HORTINIS_ACCESS_PUBLIC_ORIGIN='http://127.0.0.1:4200'
docker compose --file infrastructure/docker/compose.yaml up --wait
```

The sync service is available on `http://127.0.0.1:8080`; PostgreSQL remains private to the Compose
network. Inspect service status with `docker compose --file infrastructure/docker/compose.yaml ps`.

No-auth allows every reachable client to read and modify the sole configured scope; it does not
provide account isolation. Use synthetic data only at this stage. The service records its explicitly
selected mode in the installation transaction, emits a fixed configuration warning, and rejects
missing/unknown modes or a mismatch with committed setup. Direct configuration edits do not perform
mode transitions. Startup validates the designated sole scope without creating scopes on requests.
Browser synchronization mutations require same-origin Origin/Fetch Metadata when those headers are
present; non-browser requests remain permitted in no-auth mode. Host and raw forwarded headers cannot relax
the guard. Explicit `HORTINIS_ACCESS_PUBLIC_ORIGIN` selects the one allowed browser origin; it is
required at startup and avoids DNS-rebinding/edge-header trust. It does not enable CORS. Same-origin
compromise and reachable non-browser callers remain within the documented no-auth/shared-device
boundary. Production edge trust and deployment readiness remain separate gates.

Historical S9 enabled `single-user-auth` with password/passkey and host tickets. R2 now closes that
mode during replacement: normal startup fails before mode initialization, with no bypass property.
The native backend graph is exercised through disposable PostgreSQL and certificate-verified loopback
HTTPS tests only. Do not configure the old operator directory or run ticket commands to enable it.
R4 host commands use a separate non-web Boot graph and do not enable normal HTTP startup.
The local Compose example remains explicitly no-auth. Deployment and S15/foundation gates remain
open; see the [authentication operator workflow](../deployment/authentication-operator.md).

The named `postgres-data` volume preserves database files across normal shutdown. Stop the topology
predictably with:

```shell
docker compose --file infrastructure/docker/compose.yaml down --remove-orphans
```

PostgreSQL initializes its password only when the data volume is empty. Changing
`HORTINIS_POSTGRES_PASSWORD` does not update credentials in an existing `postgres-data` volume;
update the database role password to match before restarting with a new value.

The development Gradle container runs as root by default for compatibility with the pinned image.
On Linux with a rootful Docker daemon, map sync to the checkout owner's numeric IDs before startup:

```shell
export HORTINIS_UID="$(id -u)"
export HORTINIS_GID="$(id -g)"
docker compose --file infrastructure/docker/compose.yaml up --wait
```

Both variables default to `0`; PostgreSQL continues to use its image's own account. Rootless Docker
and Docker Desktop translate filesystem ownership differently; use IDs appropriate to the daemon's
bind-mount mapping rather than assuming host IDs are identical inside the container.

`gradle-cache-init` runs once as root before sync, preparing only the named Gradle-cache volume for
the configured IDs. `GRADLE_USER_HOME` explicitly points to `/home/gradle/.gradle`. The initializer
does not mount the checkout or database data and does not follow cache symlinks. It exits after
preparation, so a successful exited initializer alongside healthy PostgreSQL and sync is expected.
Stop the topology before changing IDs; restarting with different values prepares the existing cache
for the new owner. Do not share that cache between simultaneously running users.

The checkout is bind-mounted: native Gradle output and project caches written by sync receive its
configured ownership. Existing files from earlier root runs are not repaired automatically. With
sync stopped, inspect those generated paths and repair their ownership separately if necessary;
cache initialization never recursively changes checkout permissions. Production images replace
this development bind mount in F2. The isolated test override keeps build output and project caches
in container-local temporary directories and also supports the UID/GID overrides.

The first startup can take several minutes while Gradle and the application dependencies populate the
named `gradle-cache` volume. Do not start a second Gradle command using the same Compose service while
that build is running; it will contend for Gradle's cache lock. Follow the existing service instead with
`docker compose --file infrastructure/docker/compose.yaml logs --follow sync`.

The topology applies versioned technical Flyway migrations at sync-service startup. V1 creates the
walking-skeleton projection, receipts, and journal; V2 adds deletion state; V3 introduces the earlier
sole publication lock. V4 installs the scope-partitioned model on an empty technical database:
installation and scope UUIDs, scope-leading composite keys and references, and independent transactional
publication counters and nullable initial mode state. S4 commits explicit mode configuration during
startup on fresh installations. Publication locks are per scope. Ordinary synchronization cannot create a scope.
PostgreSQL integration tests cover installation, constraints, independent identical IDs, replay,
tombstones, retirement, cursor substitution, rollback, restart, and controlled concurrent publication.
Angular serving, same-origin edge routing, and production images remain F3/F2 work.

### S5 fresh local database and ownership

The application creates Dexie version eight (S6; S5 introduced version seven) with one offline-generated local data-set identity per
physical database. Every projection, operation, accepted base/result, conflict/rejection, tombstone,
cursor/repair state, retry, lease, and local binding belongs to that database's identity. Opening the
same locator from another tab/reload retains identity and lease fencing. Other databases have distinct
identities, independent state and locks. The root locator remains `hortinis`; future user selection
requires another database/service scope and belongs to S12.

Older unowned application schemas are rejected without changing their version, data, or pending intent.
Missing/invalid identity is not regenerated. Exchange stops and the shell reports that pending changes
cannot be checked; it never assigns old work to a new owner or resets storage automatically. There is
no production browser-data migration in this pre-release slice. Reset only disposable synthetic site
data explicitly, as described below. Local binding storage accepts only the closed provenance contract
and its own data-set identity, without passwords/session IDs/tokens. S6 owns binding activation.

Same-origin script access, extensions, and shared browser profiles can reach all local data sets.
Partitioning is not authentication or XSS protection; IDs are not secrets. Logs contain no identities
or local content. Review the [S5 plan](s5-implementation-plan.md) and
[privacy lifecycle](../privacy/access-data-lifecycle.md) before connecting real personal data.

### S6 explicit server binding

Local-only startup sends no synchronization or discovery request. Use **Connect to server** to observe
compatibility and the authorized destination, confirm an empty scope and persist binding/readiness
atomically. Both an empty browser and standalone pending local changes may bind to an empty server;
operation IDs remain unchanged. A new browser against a populated server pauses for S14 reconciliation.
Existing established devices resume their exact retained destination after reload or explicit reconnect.
There is no server selector or authentication fallback.

A replaced instance, origin, mode, account, scope or boundary pauses exchange. Capabilities and
retained-expectation bootstrap are checked before each content attempt; `Sync-Expectation` is mandatory
at HTTP acceptance and excluded from canonical operation bodies. Scope-owned server expectations have
15-minute lifetime and a 128-row outstanding limit, with issuance and 60-second scheduled cleanup.
Preflight reobservation allocates no row and extends no lifetime. Initial browser admission has a
10-minute deadline, purges on active-page expiration/next access, and retains an uncertain phase after
expiry. Explicit reconnect cannot silently admit uncertain work to a populated destination.

V4 includes expectation metadata; Dexie version eight adds local readiness/initial admission. Earlier
schemas are preserved and rejected; use only the documented disposable development resets. Runtime
server privileges additionally require expectation `SELECT/INSERT/UPDATE/DELETE`; no scope provisioning
or schema authority is needed. The [S6 plan](s6-implementation-plan.md) records the boundary and review
refinements. S7 topology isolation and authenticated/production readiness remain incomplete.

### S7 synthetic scope topology

The disposable `pnpm test:e2e:topology` override runs `:services:sync:topologyTestRun`, a separate
source-set launcher that imports controlled synthetic access configuration. It provisions two
additional test scopes, maps A1/A2 to A and B to B, and keeps the designated sole technical scope for
existing G2/S6 regressions. Missing markers use that regression scope; unknown, malformed or duplicate
markers deny access. The closed test header is fixture input, never an authentication credential or
production API. Ordinary requests still cannot provision scopes.

Production `bootRun`, runtime dependencies, executable and plain archives exclude this fixture.
`verifyProductionTopologyIsolation` checks the runtime and both archives during `build`. Production
Spring integration checks show that the test header cannot select a scope and the fixture classes are
unavailable. A profile/property cannot activate absent code. Unit tests include fixture output only
for mapping tests; PostgreSQL production integration tests use the normal application classpath.
Dependency lock changes add source-set configurations without changing dependency versions.

The real topology binds A1/A2/B while their scopes are empty, then verifies colliding IDs with
independent values/receipts, predecessor edits, delayed acknowledgement/retry/lease independence,
A tombstone propagation, B updates after A retirement, cursor/expectation substitution, and persisted
identity/expectation/replay/cursor recovery after service restart. Browser/server diagnostics exclude
content, stable IDs, expectation and credential canaries. API bootstrap/capabilities are the restart
probe through the test proxy; Compose separately uses the service readiness endpoint. This proves
synthetic scope isolation, not authenticated user/device authorization or production transport,
logging-retention, privacy or ASVS readiness. See the [S7 plan](s7-implementation-plan.md).

### S3 fresh-install development reset

S3 supports fresh installations only. V1-V3 history remains intact; V4 refuses any existing live records,
receipts, journal rows, tombstones, or identifier reservations before changing the schema. There is no
populated technical-data upgrade or user-data ownership migration. Fresh installations apply V1-V4 in
order and provision one persistent installation identity and the sole synthetic technical scope.
Restart does not regenerate identities. Additional scopes are provisioned only by controlled synthetic
test setup until access/account setup is implemented.

For an existing **synthetic development** Compose installation, stop it and remove its disposable
project volumes, then restart using the documented credentials and UID/GID settings:

```shell
docker compose --file infrastructure/docker/compose.yaml down --volumes --remove-orphans
docker compose --file infrastructure/docker/compose.yaml up --wait
```

The reset deletes that Compose project's PostgreSQL data and Gradle cache. Do not use it for user data.
For a separate developer database, recreate only that disposable database through its own tooling.
Also clear the Hortinis development origin's browser site data before reconnecting: old opaque `v1`
cursors and previously submitted synthetic work are not supported across the fresh installation.
These are explicit development resets, not automated migration or rebinding workflows. The isolated
`pnpm test:e2e:topology` runner already creates fresh volumes and browser contexts per run.

### Scoped database deployment handoff

The Compose credentials remain development credentials, not least-privilege production evidence.
Deployment work must separate the migration/DDL role from the runtime role. Runtime synchronization
needs installation `SELECT`, scope `SELECT` and counter-column `UPDATE`, record
`SELECT/INSERT/UPDATE/DELETE`, and receipt/journal/tombstone/reservation `SELECT/INSERT`; it needs no
schema ownership, global sequence privileges, arbitrary scope provisioning, or cross-account role
selection. Future setup, purge, and compaction capabilities require separately reviewed privileges.
Operator credentials must stay outside committed configuration and logs.

Backups contain installation identities and every backed-up scope. Restrict and encrypt backup access,
apply the documented retention/deletion boundary, and change installation identity before serving a
restore that rolls synchronization state back. S3 tests ordinary restart, not a production backup/
restore workflow. The [privacy lifecycle](../privacy/access-data-lifecycle.md) and S12/S15 retain the
restore-suppression and deployment-evidence gates.

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
defines the V0 technical synchronization operations, including the server-side deletion adapter added in
G2b, plus the contract-first production reconciliation endpoints and schemas selected by G1. The emitter
compatibility fixture separately exercises representative wire shapes. The
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

The browser synchronization facade delegates to concrete per-instance collaborators. ESLint enforces
size and complexity budgets for production synchronization/persistence files, with a measured exception
for existing wire validators. H6 removes the former persistence size/complexity exception. `test:architecture` verifies these guardrails.
See the [web application guide](../../apps/web/README.md#synchronization-entry-point) and
[ADR-0028](../architecture/decisions/0028-browser-synchronization-coordination.md#browser-execution-structure).

The formatting check validates the committed web application files against the Angular CLI-generated Prettier configuration. The lint command validates TypeScript, component templates, and accessibility rules with the recommended Angular ESLint flat configuration. The type-checking command validates strict TypeScript and Angular templates. The two build commands validate the development and production configurations separately. Browser end-to-end testing is introduced by B3.

Install the Playwright Chromium binary once before running browser tests:

```shell
pnpm --filter @hortinis/web exec playwright install chromium
```

Run the browser smoke and offline application-shell tests independently:

```shell
pnpm --filter @hortinis/web test:e2e
```

Playwright builds the production bundle, serves its static output on `http://127.0.0.1:4200`, and stops the server afterward. The PWA test waits until the service worker has cached the application shell, enables browser offline mode, and verifies that reloading returns the cached shell from the service worker. An isolated browser regression also verifies rollback of failed test-helper transactions and database
connection cleanup after failures. Tests currently cover Chromium only; the supported browser and device matrix remains part of the product quality-envelope work.

The full `pnpm validate` suite includes the real synchronization topology. Run it independently with:

```shell
pnpm test:e2e:topology
```

The command builds the production web bundle, starts the Compose Spring and PostgreSQL services,
proxies the browser's same-origin API requests, and validates lost-acknowledgement replay,
cross-context tombstone propagation, recovery after a real PostgreSQL outage, three offline edits in
causal order, and immutable replay when edits arrive after server acceptance but before acknowledgement. The test-only
Compose override bounds pool acquisition to one second so the server's `503` arrives before the
browser request timeout. The outage test stops and restarts only PostgreSQL in its test project.

Each invocation generates a unique Compose project name and an ephemeral database password;
no password configuration is required. Existing project-name and password environment values are
ignored. Containers, networks, and that project's database and Gradle-cache volumes are removed
on normal exit, failure, or handled interruption. Container build output and project caches stay in
container-local temporary directories, leaving native Gradle output untouched. Existing development
topology volumes remain intact.
Docker Engine with Compose, installed workspace dependencies, and Playwright Chromium are required.
Ports 4200 and 8080 must be available; concurrent topology runs are not supported.

This command replaces `pnpm g2e:topology`. Historical G2e validation evidence refers to the earlier
runner, which required a configured password and retained named volumes.

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

Unexpected exceptions mapped to HTTP `500` by the synchronization MVC advice emit exactly one
ERROR-level `request_failed` event with `exception_class` (the fully qualified class of the handled
exception) and the filter's existing random `request_id` and `trace_id`. The fixed message is
`Request failed`; exception messages, causes, stack traces, SQL, bodies, and persisted identifiers are
not attached. Non-transient transaction-start failures use the same boundary and record the outer
`CannotCreateTransactionException`. Controlled `400`, `404`, `409`, and transient `503` responses do
not emit this event. The filter separately emits `request_completed` with the same correlation IDs,
status, and allowlisted route, including both synchronization routes. This guarantee covers failures
handled by the MVC advice; failures outside MVC and deployment logging layers require separate
verification before real-data use under ADR-0015 and ADR-0029.

Technical synchronization operations validate their invariants at construction. Accepted revisions use
exact bounded arithmetic; request expectations retain the contract's unbounded positive-decimal
range. The JDBC component owns persistence conversions and typed receipt reconstruction. HTTP wire
representations explicitly emit the existing string-valued identifiers and revisions, keeping Jackson
metadata outside the internal result and change types. The rationale is recorded in
[ADR-0009](../architecture/decisions/0009-server-api-and-persistence.md#technical-synchronization-representations).

Validate the Wrapper files before running the build:

```shell
sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256
```

The Wrapper also verifies the downloaded Gradle binary distribution against the SHA-256 checksum recorded in `gradle/wrapper/gradle-wrapper.properties`. The C6 and D3 Spring Boot application dependencies have reviewed dependency-verification metadata and committed dependency locks. The application uses PostgreSQL through Spring JDBC; native `bootRun` therefore requires datasource variables and explicit `HORTINIS_ACCESS_MODE`/`HORTINIS_ACCESS_PUBLIC_ORIGIN` (the Compose topology supplies them). Each later increment that adds a dependency must update and review the lockfile and verification metadata together.

The authentication password reference corpus is generated by `:services:sync:generatePasswordCorpus`
during resource processing. It downloads a fixed upstream revision, verifies pinned source, license
and generated-artifact SHA-256 values, and caches the upstream file under `GRADLE_USER_HOME`. A fresh
build requires network access once; subsequent builds can use the verified cache, and the running
server never fetches the corpus. The raw list is not stored in Git or packaged in the application.

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

S4's representative controller/service dependency and context-signature rules run in the standard
backend `test` task. Resolver ordering/denial tests are database-independent. PostgreSQL
`integrationTest` additionally exercises test-only scope selection, cross-scope HTTP dispatch,
persisted-mode mismatch, startup rollback, and restart denial. The synthetic resolver exists only in
integration-test sources; no profile or configuration property activates it in an application JAR.

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
- running `pnpm docs:validate` to check documentation links, heading anchors, ADR headers, the ADR
  index, and the explicit cross-repository URL allowlist;
- checking that referenced repository paths agree with the planned layout in the root README and with accepted architecture decisions;
- reviewing the change for consistency with the dependency, offline-first, synchronization, privacy, and self-hosting rules;
- confirming that the change introduces no business functionality, secrets, generated credentials, local data, or environment-specific configuration.
