# Technical foundation implementation plan

- Status: approved implementation specification; implementation not started.
- Scope: executable technical foundation only, without garden-management business features.
- Tracking: this file is the repository source of truth until the work is optionally transferred to GitHub issues.

## 1. Purpose

This document divides the Hortinis technical foundation into small, independently reviewable increments. Every increment must leave the repository in a valid state and provide explicit evidence for its acceptance criteria.

The foundation must demonstrate that the selected architecture can build, run, operate offline, synchronize, recover, and remain self-hostable before business features are introduced. Technical test records may be used to validate infrastructure and synchronization behavior; they must not become an accidental business model.

This plan implements the accepted architecture decisions. It does not reopen their selected technologies or implicitly resolve decisions that remain open.

## 2. Completion and tracking model

Each tracked increment uses one of these states:

- `planned`: agreed but not started;
- `in progress`: implementation or validation is underway;
- `validated`: all acceptance criteria and documented validation commands pass;
- `blocked`: work cannot proceed until a named dependency or decision is resolved.

When implementation begins, each increment must record:

- its current status;
- prerequisite increments and decisions;
- exact scope and explicit exclusions;
- artifacts produced or changed;
- acceptance criteria;
- validation commands;
- relevant architecture decisions;
- validation evidence and follow-up findings.

The plan may later be represented by GitHub issues. Until then, this document remains authoritative. Moving work into issues must retain the dependencies, acceptance criteria, exclusions, and evidence defined here rather than replacing them with titles alone.

## 3. Foundation architecture

### 3.1 Repository structure

The technical foundation targets one application per runtime:

```text
apps/web/                      Angular application
  src/app/
    sync/                      Pure protocol rules, coordination, HTTP adapter
    persistence/               Dexie database, transactions, migrations
    catalog/                   Catalog acquisition and local use, when needed
    garden/                    Future feature code, after foundation readiness

services/sync/                 Single Spring Boot Gradle application project
  src/main/java/com/hortinis/
    sync/                      Pure protocol rules, coordination, HTTP delivery
    persistence/               JDBC queries and transaction operations
    catalog/                   Catalog integration, when needed
    storage/                   File/object storage, when needed
    garden/                    Future feature code, after foundation readiness

contracts/
  openapi/                     HTTP contracts
  schemas/                     Language-neutral schemas
  catalog/                     Pinned catalog contracts and fixtures
  sync/
    fixtures/                  Cross-runtime synchronization conformance cases

infrastructure/
  docker/                      Development and production container resources

tooling/                       Shared tooling configuration
tests/                         Cross-runtime, deployment, and end-to-end tests
```

Unit and integration tests remain within their owning application using its test conventions. The root `tests/` directory is reserved for tests crossing runtime, process, or deployment boundaries.

Introduce directories only when behavior needs them. Do not create empty feature folders, separate layer packages, or a parallel `backend` module tree. The existing root Gradle build can register `services/sync` as its single application subproject. The existing pnpm workspace can retain its reserved package pattern without creating packages.

### 3.2 Internal dependency boundaries

- Keep business invariants and synchronization decisions in plain TypeScript or Java files without framework, transport, database, filesystem, or vendor imports.
- Components and controllers delegate meaningful workflows to services. Services may use Angular or Spring dependency injection and concrete persistence components.
- Dedicated components own SQL, Dexie, filesystem, and provider operations. Components and controllers do not access persistence directly.
- Keep feature entry points small and avoid dependency cycles. Add internal rules, services, and persistence files as needed rather than requiring a fixed layer hierarchy.
- Use narrow interfaces for synchronization transport, replaceable external providers, and file/object storage. Introduce persistence interfaces when orchestration testing or an actual alternative implementation justifies them.
- Preserve explicit transaction boundaries and validate public wire contracts. Separate models and mappings are needed when semantics, lifecycle, or invariants differ, not merely because a call crosses an internal boundary.
- Use focused ESLint and ArchUnit checks for pure-rule isolation, dependency cycles, and direct persistence access. Extract build packages only for demonstrated reuse or independent enforcement needs.

Plain rule tests run without starting Angular, Spring, or a database. Real persistence integration tests establish transaction and recovery behavior. Shared fixtures establish equivalent browser and server protocol behavior.

### 3.3 Browser and server rule consistency

The browser must validate supported work locally so it can complete offline. The server remains authoritative for synchronized operations. Consequently, some rules will have TypeScript and Java representations.

Language-neutral contracts, fixtures, and accepted behavioral scenarios must test that both runtimes interpret shared identifiers, revisions, operations, validation outcomes, and synchronization rules consistently. Runtime-specific types must not be treated as the wire contract.

### 3.4 Canonical namespaces

Use these namespaces consistently:

```text
Java packages:    com.hortinis
npm packages:     @hortinis/*
container images: hortinis/*
```

More specific names must remain subordinate to these roots. Published coordinates or external registry ownership are not selected by this namespace convention.

### 3.5 Same-origin topology

The default supported production topology exposes the PWA and HTTP API through one origin:

```text
https://garden.example/
https://garden.example/api/...
```

Conceptually, a reverse proxy applies this routing:

```text
Browser
   |
   v
garden.example
   |-- /       --> Angular static files
   `-- /api/*  --> Spring synchronization service
```

The same-origin contract provides secure same-site cookie behavior without a cross-origin dependency, avoids production CORS configuration, permits one public hostname and TLS boundary, simplifies the Content Security Policy, and keeps the backend service private to the deployment topology.

During native development, the Angular development server may proxy `/api` to the Spring service so the browser-facing behavior remains same-origin. The exact development ports are scaffold configuration rather than an architectural guarantee.

This contract does not yet select Nginx, Caddy, Traefik, or another production reverse proxy. Production reverse proxy and TLS examples remain an open decision. A separately hosted API is not part of the initial supported topology; the HTTP adapter should avoid unnecessary assumptions that would prevent a future explicitly approved topology.

## 4. Implementation principles

- Introduce one coherent capability per increment whenever practical.
- Keep every completed increment buildable and independently reviewable.
- Add no garden-management business feature during foundation work.
- Define public HTTP contracts before implementing their adapters.
- Keep business and synchronization rules framework-independent; allow framework-aware coordinating services.
- Keep persistence in dedicated components and introduce interfaces selectively as described in section 3.2.
- Validate offline behavior from the first local-persistence increment onward.
- Validate synchronization progressively rather than postponing it until the rest of the application exists.
- Pin exact tool and dependency versions when each toolchain is introduced.
- Keep native development supported alongside containers.
- Add no third-party runtime assets, mandatory external service, credential, generated secret, telemetry, or external analytics.
- Do not enable local analytics. The activation gates in ADR-0017 require follow-up decisions and validation before collection is authorized.
- Do not select unresolved authentication, backup, catalog-distribution, retention, or reconciliation details implicitly during scaffold work.

## 5. Increment plan

### Track A: repository and toolchains

#### A1. Establish this implementation tracker

- Initial status: `validated` when this specification has passed the pre-scaffold documentation checks.
- Scope: record the approved structure, sequencing, boundaries, and acceptance model.
- Excludes: executable code, generated projects, dependency manifests, and CI configuration.
- Acceptance: the document is internally consistent, written in English, and aligned with accepted architecture decisions.

#### A2. Establish the repository baseline

- Status: `validated`.
- Depends on: A1.
- Scope: add minimal repository-wide editor, text-normalization, and ignore conventions required by both toolchains.
- Excludes: generated application code, dependency installation, application directories, package directories, and backend modules.
- Artifacts: `.editorconfig`, `.gitattributes`, and `.gitignore`.
- Acceptance: conventions do not hide source, contracts, wrappers, lockfiles, or other required reproducibility files, and no placeholder project structure is introduced.
- Validation commands: `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: repository text is normalized to LF, Windows command scripts are assigned CRLF, no tracked file is ignored, representative future source, contract, wrapper, and lockfile paths remain visible, and no placeholder project structure was introduced.
- Follow-up: add pnpm and frontend-specific ignore rules with A3 and the relevant Track B increments; add Gradle and backend-specific ignore rules with A4 and the relevant backend increments.
- Relevant decisions: ADR-0011 and ADR-0018.

#### A3. Establish the frontend workspace foundation

- Status: `validated`.
- Depends on: A2.
- Scope: pin Node.js 24 LTS and pnpm, create one root workspace and lockfile, declare workspace patterns for future applications and packages, and expose attributable frontend validation commands.
- Excludes: Angular generation and empty or placeholder applications and packages.
- Artifacts: `.nvmrc`, `package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml`, frontend entries in `.gitignore`, and frontend setup and validation instructions in `docs/development/README.md`.
- Acceptance: installation is reproducible, the empty workspace validation commands succeed, and a future project placed under an approved workspace pattern can join the workspace without restructuring the root configuration.
- Validation commands: `node --version`, `pnpm --version`, `pnpm install --frozen-lockfile`, `pnpm list --recursive --depth -1`, `git diff --check`, `git ls-files -ci --exclude-standard`, and `git ls-files --eol`.
- Validation evidence: Node.js reports `v24.18.0`, pnpm reports `11.26.0`, the frozen empty-workspace installation succeeds, recursive workspace discovery reports the private `@hortinis/workspace` root, the workspace reserves `apps/*` and `packages/*` for future projects, no tracked file is ignored, and repository text remains normalized to LF.
- Follow-up: add the first workspace member with B1 without changing the root workspace patterns; add capability-specific frontend validation commands with the increments that introduce those capabilities.
- Relevant decisions: ADR-0007, ADR-0011, and ADR-0018.

#### A4. Establish the backend multi-project foundation

- Status: `validated`.
- Depends on: A2.
- Scope: add the Gradle Wrapper, Kotlin DSL root settings, repository and plugin management, Java 25 requirements, shared build conventions, dependency verification where supported, and attributable backend validation commands.
- Excludes: Spring Boot, included backend modules, and empty or placeholder subprojects.
- Artifacts: `settings.gradle.kts`, `build.gradle.kts`, Gradle 9.7.1 Wrapper files and checksums, dependency-verification metadata, backend entries in `.gitignore`, and backend setup and validation instructions in `docs/development/README.md`.
- Acceptance: wrapper integrity checks and an empty root build succeed with the documented JDK, and future modules can be registered without restructuring the root build.
- Validation commands: `java --version`, `sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256`, `./gradlew --version`, `./gradlew projects`, `./gradlew build`, `git diff --check`, `git ls-files -ci --exclude-standard`, and `git ls-files --eol`.
- Validation evidence: Java reports `25.0.4.1`, Gradle reports `9.7.1`, the Wrapper JAR matches Gradle's published SHA-256 checksum, the checksum-pinned distribution downloads successfully, Gradle reports only the `hortinis` root with no subprojects, the empty root `build` succeeds, no tracked file is ignored, and repository text conventions remain intact.
- Follow-up: register only the Spring Boot application project at `services/sync` with C5; add dependency checksums whenever a later increment adds an external artifact. The validated root build remains usable without separate layer modules.
- Relevant decisions: ADR-0007, ADR-0011, and ADR-0018.

#### A5. Document executable validation entry points

- Status: `validated`.
- Depends on: A3 and A4.
- Scope: replace the pre-scaffold-only development instructions with exact independent commands as capabilities become runnable.
- Excludes: adding application capabilities, quality-tool dependencies, CI jobs, or a monorepo task orchestrator.
- Artifacts: executable validation entry points and repository-wide validation guidance in `docs/development/README.md`.
- Acceptance: formatting, linting, type checking, tests, architecture checks, and builds remain separately invocable rather than hidden behind a monorepo orchestrator.
- Validation commands: `node --version`, `pnpm --version`, `pnpm install --frozen-lockfile`, `pnpm list --recursive --depth -1`, `java --version`, `sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256`, `./gradlew --version`, `./gradlew projects`, `./gradlew build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: frontend and backend foundation commands remain documented in separate sections; repository-wide checks are independently listed; future formatting, linting, type-checking, testing, architecture, and build commands have explicit owning increments; and no aggregate task runner was introduced.
- Follow-up: add each capability-specific command with B1, B2, B7, C7, D1, and later increments as those capabilities become runnable; keep CI invocation aligned with the same independent local entry points.
- Relevant decisions: ADR-0007, ADR-0011, ADR-0012, and ADR-0018.

### Track B: minimal Angular application

#### B1. Generate an empty standalone Angular application

- Status: `validated`.
- Depends on: A3.
- Scope: create `apps/web` with strict TypeScript, standalone Angular composition, an empty router configuration, SCSS compilation, and a minimal application shell.
- Excludes: PWA support, Dexie, synchronization, domain behavior, browser end-to-end testing, a component library, and business UI.
- Artifacts: the `@hortinis/web` pnpm workspace member, Angular workspace configuration, strict TypeScript configurations, minimal application shell and unit tests, frontend ignore rules, reviewed pnpm lifecycle-script permissions, application documentation, and ADR-0019.
- Acceptance: development build, production build, strict type checking, and the initial unit-test command succeed.
- Validation commands: `node --version`, `pnpm --version`, `pnpm install --frozen-lockfile`, `pnpm list --recursive --depth -1`, `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web build:development`, `pnpm --filter @hortinis/web build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: Node.js reports `v24.18.0`, pnpm reports `11.26.0`, frozen installation succeeds, workspace discovery reports `@hortinis/workspace` and `@hortinis/web`, strict TypeScript and Angular template checking succeeds, the initial two-test Vitest suite passes, and both development and production Angular builds succeed. The repository-wide whitespace, ignore, and line-ending checks also succeed.
- Follow-up: B2 enforces the Angular CLI-generated Prettier baseline and adds Angular ESLint; B3 adds browser smoke testing; B4 replaces the generated favicon while adding PWA assets and application-shell caching. Any component-library selection, including Angular Material, requires its own decision and increment.
- Relevant decisions: ADR-0007, ADR-0011, ADR-0018, and ADR-0019.

#### B2. Enforce frontend formatting and linting

- Status: `validated`.
- Depends on: B1.
- Scope: build on the Angular CLI-generated Prettier baseline, add Angular ESLint, and expose explicit formatting and linting commands.
- Excludes: type-aware lint rules, browser end-to-end testing, PWA support, a component library, and business UI.
- Artifacts: Angular ESLint 22 flat configuration, Angular CLI lint target, web-package formatting and linting scripts, an ordered lint-fix-then-format convenience command, pinned workspace dependencies, and updated frontend validation instructions.
- Acceptance: formatting and linting are deterministic and independently runnable.
- Validation commands: `node --version`, `pnpm --version`, `pnpm install --frozen-lockfile`, `pnpm list --recursive --depth -1`, `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web build:development`, `pnpm --filter @hortinis/web build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: Node.js reports `v24.18.0`, pnpm reports `11.26.0`, frozen installation and recursive workspace discovery succeed, Prettier reports all web application files formatted, Angular ESLint reports no TypeScript or template violations, and all B1 type-checking, unit-test, development-build, and production-build checks remain successful. Repository-wide whitespace, ignore, attribute, and line-ending checks also succeed.
- Follow-up: B3 adds browser smoke testing. Reassess type-aware linting when application code is substantial enough to justify its additional configuration and runtime cost.
- Relevant decisions: ADR-0007, ADR-0011, ADR-0018, and ADR-0019.

#### B3. Add a browser smoke test

- Initial status: `planned`.
- Depends on: B1.
- Scope: add Playwright and verify that the empty application loads.
- Excludes: offline, installation, synchronization, and business workflows.
- Acceptance: the smoke test runs repeatably in the documented environment.

#### B4. Add PWA application-shell support

- Initial status: `planned`.
- Depends on: B1 through B3.
- Scope: add the Angular service worker, web manifest, icons or placeholders suitable for foundation validation, and versioned application-shell caching.
- Excludes: background data synchronization and analytics queues.
- Acceptance: the production application is installable where supported and reloads its shell while offline after a successful initial load.

B5 and B6, formerly separate domain and application package setup, are folded into B8, B9, and Track E. Introduce pure rules and coordinating services with their first behavior inside `apps/web`; no empty package increments remain. These identifiers are retained here only to explain the tracker revision.

#### B7. Add automated web boundary checks

- Initial status: `planned`.
- Depends on: B2 and B8; extend the checks when B9 and Track E introduce synchronization code.
- Scope: use internal import checks to protect pure rules, detect dependency cycles, and prevent Angular components from accessing persistence directly. Add rules as the corresponding code appears.
- Acceptance: representative forbidden imports cause the check to fail.

#### B8. Create the Dexie adapter

- Initial status: `planned`.
- Depends on: B2.
- Scope: implement dedicated persistence components under `apps/web/src/app/persistence`, an initial versioned IndexedDB schema, and migration test infrastructure. Keep transaction ownership explicit without requiring a persistence interface or separate package.
- Excludes: garden records and synchronization behavior.
- Acceptance: database creation, version discovery, migration execution, transaction rollback, and test isolation are demonstrated.

#### B9. Create the HTTP synchronization adapter boundary

- Initial status: `planned`.
- Depends on: B2 and the technical synchronization contracts in D2.
- Scope: implement an HTTP adapter and narrow transport interface inside `apps/web/src/app/sync`; validate contract boundary shapes and map them only where internal semantics differ.
- Acceptance: transport failure behavior is testable through the interface, invalid boundary data is rejected, and pure protocol rules remain independent of HTTP.

### Track C: minimal Spring application

C1 through C4, formerly separate backend layer and protocol module setup, are folded into C5, D2, and Track E. Introduce internal pure rules and coordinating services with their first behavior in `services/sync`. These identifiers are retained here only to explain the tracker revision; they are not prerequisites or empty-module work.

#### C5. Create the empty Spring Boot synchronization service

- Initial status: `planned`.
- Depends on: A4.
- Scope: register and create `services/sync` as the single Spring Boot application project under the existing root Gradle build. Introduce internal packages only as behavior needs them.
- Excludes: PostgreSQL, authentication, synchronization endpoints, and business behavior.
- Acceptance: the service starts and stops cleanly without a database.

#### C6. Add health, readiness, and safe structured logging

- Initial status: `planned`.
- Depends on: C5.
- Scope: provide health and readiness endpoints and minimized structured JSON logs consistent with ADR-0015.
- Excludes: request or response bodies and user-provided content.
- Acceptance: endpoint tests pass and log tests demonstrate that prohibited fields are absent.

#### C7. Add backend quality enforcement

- Initial status: `planned`.
- Depends on: C5.
- Scope: configure the Java compiler, JUnit 5, AssertJ, Checkstyle, Spotless, and ArchUnit with independent commands.
- Acceptance: formatting, unit tests, and architecture tests are deterministic. As code appears, focused checks reject framework or infrastructure imports in pure rules, dependency cycles, and controller access to persistence; representative violations fail.

### Track D: contracts and PostgreSQL

#### D1. Establish contract structure and validation

- Initial status: `planned`.
- Depends on: A3 or A4 as required by the selected validator.
- Scope: create OpenAPI 3.1, JSON Schema, and synchronization fixture locations with validation commands.
- Excludes: garden resources and unresolved authentication contracts.
- Acceptance: valid minimal contracts pass and deliberately invalid fixtures fail.

#### D2. Define technical service and synchronization envelopes

- Initial status: `planned`.
- Depends on: D1 and C5.
- Scope: specify only the technical endpoints and protocol envelopes needed by the walking skeleton.
- Excludes: garden resources, final reconciliation formats, authentication, analytics, backup, and catalog distribution.
- Acceptance: contracts describe versioning, identifiers, idempotency, revisions, sequences, cursors, and explicit protocol errors required by the selected slice.

#### D3. Create PostgreSQL persistence components

- Initial status: `planned`.
- Depends on: C5 and D2.
- Scope: create explicit-SQL Spring JDBC persistence components inside `services/sync`, with explicit transaction ownership. Services may depend on these concrete components; no separate adapter module is required.
- Excludes: JPA and garden persistence.
- Acceptance: pure business and synchronization rules remain independent of Spring JDBC and PostgreSQL, and controllers do not access persistence directly.

#### D4. Add PostgreSQL to the development topology

- Initial status: `planned`.
- Depends on: D3 and F1.
- Scope: add PostgreSQL 18 with named persistent development storage.
- Acceptance: health checks and documented startup and shutdown behavior are reliable.

#### D5. Add Flyway and the first technical migration

- Initial status: `planned`.
- Depends on: D3 and D4.
- Scope: create an additive technical schema sufficient for the synchronization walking skeleton.
- Excludes: garden tables and unresolved long-term retention policy.
- Acceptance: migration from an empty database succeeds and repeat startup is idempotent.

#### D6. Add PostgreSQL integration tests

- Initial status: `planned`.
- Depends on: D5.
- Scope: use Testcontainers to validate explicit SQL, transactions, migrations, and service restart behavior.
- Acceptance: tests run from a clean environment and prove rollback and atomicity assumptions used by synchronization.

### Track E: first synchronization walking skeleton

The first slice uses a deliberately technical record. It validates the mechanism without introducing a garden entity or implying a final resource model.

#### E1. Define technical record conformance fixtures

- Initial status: `planned`.
- Depends on: D2.
- Scope: define canonical technical records, operations, results, errors, and language-neutral fixtures.
- Acceptance: TypeScript and Java consume the same valid and invalid examples.

#### E2. Commit local state and an outbox operation atomically

- Initial status: `planned`.
- Depends on: B8 and E1.
- Scope: persist one technical local change and its stable outbox operation in one Dexie transaction.
- Acceptance: success persists both, failure persists neither, and reload retains the pending operation.

#### E3. Push and atomically accept one operation

- Initial status: `planned`.
- Depends on: B9, D5, D6, and E2.
- Scope: send one operation and atomically persist idempotency, accepted state, revision, server sequence, and change journal.
- Acceptance: partial database state cannot remain after a failed acceptance transaction.

#### E4. Prove idempotent retry

- Initial status: `planned`.
- Depends on: E3.
- Scope: retry an identical stable operation and reject reuse of its idempotency identifier with a different canonical request.
- Acceptance: identical retries return the stable result without duplicate effects; mismatched reuse returns the specified error.

#### E5. Pull changes through an opaque cursor

- Initial status: `planned`.
- Depends on: E3.
- Scope: retrieve accepted changes ordered by server sequence and persist the new cursor with their local application.
- Acceptance: repeated pulls create no duplicates and cannot persist a cursor beyond unapplied changes.

#### E6. Recover synchronization after browser reload

- Initial status: `planned`.
- Depends on: E2 through E5.
- Scope: restore local state, pending operations, stable results, and cursors after reloading the browser application.
- Acceptance: synchronization resumes without losing or duplicating accepted local intent.

#### E7. Represent an expected-revision conflict

- Initial status: `planned`.
- Depends on: E5.
- Scope: submit an operation based on a stale server revision and return an explicit conflict without silent last-write-wins behavior.
- Excludes: final domain-specific conflict presentation and resolution.
- Acceptance: the accepted server state and competing proposal remain identifiable and independent local work remains usable.

#### E8. Demonstrate offline and failure independence

- Initial status: `planned`.
- Depends on: E2 and B4.
- Scope: create additional local technical work while the service is unreachable or a synchronization operation has failed.
- Acceptance: local commits remain available, failures are observable, and recovery does not require discarding local storage.

#### E9. Run cross-runtime conformance validation

- Initial status: `planned`.
- Depends on: E1 through E8.
- Scope: execute shared fixtures against TypeScript and Java interpretations of the implemented protocol slice.
- Acceptance: canonicalization, validation, identifiers, revisions, results, and error classifications agree.

#### E10. Publish the first foundation validation report

- Initial status: `planned`.
- Depends on: E1 through E9 and the applicable packaging and CI increments.
- Scope: record commands, environments, evidence, limitations, failures found, and remaining protocol risks.
- Acceptance: the report distinguishes demonstrated behavior from planned behavior and does not authorize business features while blocking foundation risks remain.

Later foundation increments must extend this slice to causal operation chains, tombstones, interrupted exchanges, bounded retry behavior, generation rollover, anchored snapshots, full reconciliation, and indeterminate outcomes before those guarantees are claimed as validated. Their precise wire formats and retention choices require the follow-up decisions identified by the existing architecture documentation.

### Track F: packaging and continuous integration

This track may proceed alongside the web, server, contract, and synchronization tracks once its prerequisites exist.

#### F1. Add the minimal Compose topology

- Initial status: `planned`.
- Depends on: C5.
- Scope: provide standard local and self-hosting orchestration without making container development mandatory.
- Acceptance: the topology validates and services start, become healthy, and stop predictably.

#### F2. Add development and production container builds

- Initial status: `planned`.
- Depends on: B1 and C5.
- Scope: create separate multi-stage builds whose runtime images exclude development tools.
- Acceptance: production images build reproducibly and start without package registries or a Hortinis-operated service.

#### F3. Validate the same-origin routing contract

- Initial status: `planned`.
- Depends on: B1, C5, and F1.
- Scope: validate `/` and `/api/*` through development or test infrastructure according to the approved external contract.
- Excludes: implementing or selecting the final production reverse proxy and TLS recommendation.
- Acceptance: a browser uses one origin for the shell and API in the validation topology, and the Spring service is not required to be publicly exposed.

#### F3b. Implement the production edge routing

- Initial status: `blocked` until the production reverse-proxy and TLS decision is accepted.
- Depends on: F3, F2, and the production edge decision.
- Scope: route `/` to the built Angular application and `/api/*` to Spring in the selected production edge implementation.
- Acceptance: the selected production topology serves the shell and API through one origin without publicly exposing the Spring service.

#### F4. Add incremental GitHub Actions validation

- Initial status: `planned`.
- Depends on: the corresponding local command from each earlier increment.
- Scope: add jobs progressively for documentation, formatting, linting, type checking, tests, architecture checks, builds, image builds, and Compose validation.
- Excludes: automated deployment.
- Acceptance: CI invokes pnpm and Gradle explicitly, reports failures by toolchain, and reproduces documented local checks.

#### F5. Validate native and container development paths

- Initial status: `planned`.
- Depends on: F1 through F4.
- Scope: document and verify native development and the optional container-assisted path.
- Acceptance: the Dev Container, when added, reuses the Compose environment and does not define a competing topology.

#### F6. Validate autonomous runtime operation

- Initial status: `planned`.
- Depends on: F2 and F3.
- Scope: prove that an installed deployment runs without a package registry, Hortinis-operated service, analytics provider, plant provider, or optional external adapter.
- Acceptance: the validation records all attempted outbound runtime dependencies and demonstrates that none is required for the implemented foundation slice.

## 6. Initial milestone boundary

The first technical-foundation milestone is complete only when:

- the Angular application builds, installs where supported, and reloads its shell offline;
- focused internal checks protect pure TypeScript rules, detect cycles, and prevent component access to persistence;
- the Spring service builds, exposes safe health information, and enforces equivalent internal Java dependency checks;
- PostgreSQL migrations and explicit persistence operations pass isolated integration tests;
- the OpenAPI and schema contracts validate before their adapters;
- one technical record completes local commit, outbox, push, accepted persistence, idempotent retry, pull, cursor persistence, reload recovery, and explicit stale-revision conflict scenarios;
- synchronization failure does not prevent independent local work;
- shared fixtures verify the implemented TypeScript and Java protocol behavior;
- the same-origin contract is validated through the Compose/test topology and production-style images are buildable;
- local and CI validation commands pass; and
- the validation report clearly identifies protocol guarantees not yet implemented.

Completion of this milestone validates a thin vertical foundation slice. It does not validate every guarantee in ADR-0010 and does not authorize garden functionality until the Foundation readiness gate below is complete.

### Foundation readiness gate

Business-feature implementation may begin only when:

- the initial foundation milestone is validated;
- causal operation chains, tombstones, interrupted exchanges, bounded retry behavior, generation rollover, anchored snapshots, full reconciliation, and indeterminate outcomes are implemented and tested;
- browser schema migration, transaction recovery, server migration, and restart behavior are demonstrated;
- native, Compose, container-image, and CI validation pass; and
- the foundation report contains no unresolved blocking foundation risk.

Feature-specific decisions remain additional prerequisites. For example, synchronized garden data requires the accepted access design, and catalog-dependent features require the catalog acquisition design.

## 7. Deferred and prohibited selections

The following must not be resolved accidentally while executing this plan:

- concrete garden API resources and domain persistence shapes;
- authentication library, roles, permissions, optional-account server access, session expiry, recovery, and device revocation;
- production reverse proxy and TLS product selection;
- final backup, restoration, rollback, and upgrade formats and procedures;
- optional S3-compatible adapter implementation;
- plant catalog chunk sizing, signing, key distribution, and cadence;
- synchronization retention periods, compaction thresholds, generation rollover details, final snapshot continuation, indeterminate-outcome representation, and full-reconciliation wire format;
- domain-specific merge, conflict presentation, and conflict-resolution rules;
- analytics schemas, retention, trust boundaries, visitor controls, contribution, or activation;
- Kubernetes, automated deployment, telemetry, third-party runtime assets, or mandatory external providers.

If an increment requires one of these choices, mark it `blocked`, create or update the appropriate specification or architecture decision, and resume only after that decision is accepted.

## 8. Review requirements

Every foundation change must be reviewed for:

- pure-rule isolation, feature dependencies, dedicated persistence, and justified interfaces or model mappings;
- offline-first behavior and preservation of accepted local work;
- deterministic, observable, recoverable synchronization;
- contract-first adapter implementation;
- privacy-safe diagnostics and absence of prohibited data in logs;
- autonomous self-hosting and optional external providers;
- reproducible native, container, and CI validation;
- English language consistency in repository content;
- absence of business functionality, secrets, generated credentials, local data, and environment-specific configuration.

Before executable validation exists, use the [pre-scaffold development checks](README.md). As each increment introduces runnable checks, document and execute those checks in addition to the pre-scaffold review.
