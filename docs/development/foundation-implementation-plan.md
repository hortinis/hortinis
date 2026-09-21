# Technical foundation implementation plan

- Status: approved implementation specification; incremental implementation is underway.
- Scope: executable technical foundation and the readiness gates that control when the limited V0 and
  production garden-management slices may begin.
- Tracking: this file is the repository source of truth until the work is optionally transferred to GitHub issues.

## 1. Purpose

This document divides the Hortinis technical foundation into small, independently reviewable increments. Every increment must leave the repository in a valid state and provide explicit evidence for its acceptance criteria.

The foundation must demonstrate that the selected architecture can build, run, operate offline,
synchronize, recover, and remain self-hostable before production business features are introduced.
Technical test records validate infrastructure and synchronization behavior first. A deliberately limited
V0 business slice may follow the smaller V0 technical readiness gate defined below, without claiming the
complete foundation or MVP ready.

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
- Add no garden-management business feature during foundation work. The V0 product-validation slice
  starts only after its technical readiness gate and is tracked in the product implementation plan.
- Define public HTTP contracts before implementing their adapters.
- Keep business and synchronization rules framework-independent; allow framework-aware coordinating services.
- Keep persistence in dedicated components and introduce interfaces selectively as described in section 3.2.
- Validate offline behavior from the first local-persistence increment onward.
- Validate synchronization progressively rather than postponing it until the rest of the application exists.
- Pin exact tool and dependency versions when each toolchain is introduced.
- Keep native development supported alongside containers.
- Add no third-party runtime assets, mandatory external service, credential, generated secret, telemetry, or external analytics.
- Do not enable local analytics. The activation gates in ADR-0017 require follow-up decisions and validation before collection is authorized.
- Do not select unresolved authentication, backup, or catalog-distribution details implicitly during
  scaffold work. Production synchronization retention and reconciliation are governed by ADR-0023
  through ADR-0026 and remain unavailable until their Track G implementation increments are validated.

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
- Excludes: adding application capabilities, quality-tool dependencies, CI jobs, or a third-party monorepo task orchestrator.
- Artifacts: executable validation entry points, an optional sequential root `pnpm validate` wrapper, and repository-wide validation guidance in `docs/development/README.md`.
- Acceptance: formatting, linting, type checking, tests, architecture checks, and builds remain separately invocable, and the root command runs them in a documented sequence and stops at the first failure.
- Validation commands: `node --version`, `pnpm --version`, `pnpm install --frozen-lockfile`, `pnpm list --recursive --depth -1`, `java --version`, `sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256`, `./gradlew --version`, `./gradlew projects`, `./gradlew build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: frontend and backend foundation commands remain documented in separate sections; repository-wide checks are independently listed; future formatting, linting, type-checking, testing, architecture, and build commands have explicit owning increments; and the root wrapper invokes those entry points without adding a task-runner dependency.
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
- Follow-up: B2 enforces the Angular CLI-generated Prettier baseline and adds Angular ESLint; B3 adds browser smoke testing; B4 adds PWA assets and application-shell caching. Replace the generated favicon after the visual identity is selected. Any component-library selection, including Angular Material, requires its own decision and increment.
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

- Status: `validated`.
- Depends on: B1.
- Scope: add Playwright and verify that the empty application loads.
- Excludes: offline, installation, synchronization, and business workflows.
- Acceptance: the smoke test runs repeatably in the documented environment.
- Artifacts: the `@playwright/test` and Node.js type dependencies, a Chromium Playwright configuration with managed Angular development-server startup, a minimal application-shell smoke test, strict E2E TypeScript checking, ignored Playwright output directories, and updated frontend validation instructions.
- Validation commands: `node --version`, `pnpm --version`, `pnpm install --frozen-lockfile`, `pnpm list --recursive --depth -1`, `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web test:e2e`, `pnpm --filter @hortinis/web build:development`, `pnpm --filter @hortinis/web build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: Playwright starts the Angular development server, Chromium loads the root application, the HTTP response succeeds, the document title is `Hortinis`, and the visible level-one `Hortinis` heading is present. Existing B1 and B2 checks remain successful, and repository-wide whitespace, ignore, attribute, and line-ending checks succeed.
- Follow-up: B4 adds PWA application-shell support. Reassess browser and device coverage when the product quality envelope is defined.
- Relevant decisions: ADR-0007, ADR-0011, ADR-0018, and ADR-0019.

#### B4. Add PWA application-shell support

- Status: `validated`.
- Depends on: B1 through B3.
- Scope: add the Angular service worker, web manifest, icons or placeholders suitable for foundation validation, and versioned application-shell caching.
- Excludes: background data synchronization and analytics queues.
- Acceptance: the production application is installable where supported and reloads its shell while offline after a successful initial load.
- Validation evidence: the Chromium Playwright test serves the production build as static files, waits for the service worker to cache the application shell and control the page, enables browser offline mode, and verifies that reloading renders the cached shell from the service worker.

B5 and B6, formerly separate domain and application package setup, are folded into B8, B9, and Track E. Introduce pure rules and coordinating services with their first behavior inside `apps/web`; no empty package increments remain. These identifiers are retained here only to explain the tracker revision.

#### B7. Add automated web boundary checks

- Status: `validated`.
- Depends on: B2 and B8; extend the checks when B9 and Track E introduce synchronization code.
- Scope: use internal import checks to protect pure rules, detect dependency cycles, and prevent Angular components from accessing persistence directly. Add rules as the corresponding code appears.
- Acceptance: representative forbidden imports cause the check to fail.
- Artifacts: the independent `architecture:check` command; ESLint restrictions for Angular component persistence imports and pure-rule external packages, framework, transport, persistence, filesystem, provider, and browser-global dependencies; a TypeScript import-graph cycle check; and isolated negative fixtures with automated tests.
- Validation commands: `pnpm --filter @hortinis/web architecture:check`, `pnpm --filter @hortinis/web test:architecture`, `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web build:development`, `pnpm --filter @hortinis/web build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: the application source passes the architecture check; separate fixtures prove that direct component-to-persistence imports, framework or external-package imports from pure-rule files, and internal TypeScript cycles each fail it.

#### B8. Create the Dexie adapter

- Status: `validated`.
- Depends on: B2.
- Scope: implement dedicated persistence components under `apps/web/src/app/persistence`, an initial versioned IndexedDB schema, and migration test infrastructure. Keep transaction ownership explicit without requiring a persistence interface or separate package. B8 established the empty version-one baseline; E2 replaces that pre-release baseline with the first real application stores. A test-only fixture demonstrates version upgrades without introducing placeholder production data.
- Excludes: garden records and synchronization behavior.
- Artifacts: the Angular-injectable `HortinisDatabase` Dexie adapter, the empty version-one application-schema baseline later replaced by E2, test-only versioned migration fixtures, isolated IndexedDB unit tests, and the `fake-indexeddb` test dependency.
- Acceptance: database creation, version discovery, migration execution, transaction rollback, and test isolation are demonstrated.
- Validation commands: `pnpm install --frozen-lockfile`, `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web build:development`, `pnpm --filter @hortinis/web build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: the six-test Vitest suite opens the empty version-one application database and reports its version, upgrades a separate version-one fixture database through a version-two data migration, rolls back a failed Dexie read-write transaction, and proves separately named databases are isolated and deleted after each test. Formatting, linting, strict type checking, development and production builds, and repository-wide whitespace, ignore, attribute, and line-ending checks succeed.
- Follow-up: introduce real feature-owned stores and their migrations only with the relevant product increments; E2 defines the first atomic local state and outbox transaction.
- Relevant decisions: ADR-0002, ADR-0008, and ADR-0018.

#### B9. Create the HTTP synchronization adapter boundary

- Status: `validated`.
- Depends on: B2 and the technical synchronization contracts in D2.
- Scope: implement an HTTP adapter and narrow transport interface inside `apps/web/src/app/sync`; validate contract boundary shapes and map them only where internal semantics differ.
- Excludes: retry and backoff policy, outbox coordination, accepted-result persistence, conflict resolution, authentication, and server integration tests.
- Artifacts: framework-free D2 request/response and error guards; the `SynchronizationTransport` interface; typed protocol, unavailable, boundary, and unexpected-response errors; an Angular `HttpClient` adapter for submit and pull; an overridable same-origin API base URL; HTTP provider registration; adapter unit tests using `HttpTestingController`; and an architecture rule preventing HTTP imports from other synchronization modules.
- Acceptance: transport failure behavior is testable through the interface, invalid request and response boundary data is rejected, documented `400`, `404`, and `409` responses are exposed as typed protocol errors, and pure protocol rules remain independent of HTTP.
- Validation commands: `pnpm install --frozen-lockfile`, `pnpm contracts:validate`, `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web architecture:check`, `pnpm --filter @hortinis/web test:architecture`, `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web build:development`, `pnpm --filter @hortinis/web build`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: on 2026-09-15, the Angular/Vitest suite passed 23 tests across five files, including both HTTP endpoints, opaque cursor handling, all documented protocol-error variants, malformed boundary data, pre-request validation, network failure classification, and no retry. Strict Angular/TypeScript checks, Angular ESLint, the web architecture check and negative HTTP-import fixture, the development build, contract validation, and repository-wide whitespace, ignore, attribute, and line-ending checks passed. The standard production build was attempted but remains blocked by the pre-existing external Google Fonts inlining request (`fonts.googleapis.com` DNS failure); this is unrelated to B9.
- Follow-up: E3 consumes this boundary to submit and persist one operation atomically; E5 consumes pull behavior for cursor-backed change application. Retry/backoff remains governed by ADR-0010 and later synchronization increments.
- Relevant decisions: ADR-0003, ADR-0007, ADR-0009, ADR-0010, ADR-0011, ADR-0018, and ADR-0021.

### Track C: minimal Spring application

C1 through C4, formerly separate backend layer and protocol module setup, are folded into C5, D2, and Track E. Introduce internal pure rules and coordinating services with their first behavior in `services/sync`. These identifiers are retained here only to explain the tracker revision; they are not prerequisites or empty-module work.

#### C5. Create the empty Spring Boot synchronization service

- Status: `validated`.
- Depends on: A4.
- Scope: register and create `services/sync` as the single Spring Boot application project under the existing root Gradle build. Introduce internal packages only as behavior needs them.
- Excludes: PostgreSQL, authentication, synchronization endpoints, and business behavior.
- Artifacts: the registered `:services:sync` Gradle application project; the `com.hortinis.sync.HortinisSyncApplication` Spring Boot entry point; a minimal application name configuration; application-context startup coverage; dependency locking; and reviewed Gradle dependency-verification metadata for the introduced Spring Boot artifacts.
- Acceptance: the service starts and stops cleanly without a database.
- Validation commands: `sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256`, `java --version`, `./gradlew --version`, `./gradlew projects`, `./gradlew --dependency-verification=strict :services:sync:clean :services:sync:test --rerun-tasks`, `./gradlew :services:sync:build`, `./gradlew :services:sync:bootRun`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: on 2026-09-14, strict dependency verification, a clean rerun of the application-context test, the service build, Gradle project discovery, and repository whitespace, ignore, attribute, and line-ending checks succeeded. `bootRun` started the application with Java 25 without configuring a database and returned normally because this empty application deliberately does not yet include a web server or other non-daemon work.
- Follow-up: C6 adds observable health/readiness behavior and safe structured logging; D1 introduces HTTP contract structure. Add dedicated lifecycle coverage only when the service owns resources whose shutdown behavior requires application-specific verification.
- Relevant decisions: ADR-0003, ADR-0007, ADR-0009, ADR-0011, ADR-0015, and ADR-0018.

#### C6. Add health, readiness, and safe structured logging

- Status: `validated`.
- Depends on: C5.
- Scope: provide health and readiness endpoints and minimized structured JSON logs consistent with ADR-0015.
- Excludes: request or response bodies and user-provided content.
- Artifacts: the Spring Boot Actuator dependency; status-only health, liveness, and readiness endpoint
  configuration; the privacy-safe request correlation and completion-event filter; structured JSON console
  logging configuration; focused endpoint and log-safety tests; updated dependency locks; and reviewed
  Gradle dependency-verification metadata.
- Acceptance: `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` return
  status-only JSON (the global response may list the liveness and readiness group names); only the
  health endpoint is exposed over HTTP; request and trace identifiers are
  generated server-side and cleared after each request; request completion events contain only the
  reviewed operational fields; and tests demonstrate that query parameters, headers, request content,
  and prohibited fields are absent from captured JSON logs.
- Validation commands: `./gradlew --dependency-verification=strict :services:sync:spotlessCheck
  :services:sync:test --rerun-tasks`, `./gradlew :services:sync:checkstyleMain
  :services:sync:checkstyleTest`, `./gradlew :services:sync:pmdMain :services:sync:pmdTest`,
  `./gradlew :services:sync:build`, `git diff --check`, `git ls-files -ci --exclude-standard`,
  `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: on 2026-09-15, strict dependency verification, Spotless, the three endpoint
  assertions, the structured-log JSON and sensitive-canary assertions, PMD, Checkstyle, and the service
  build passed. Checkstyle reports the two existing Javadoc warnings on the C5 application entry point;
  they do not fail the configured task. Repository whitespace, ignore, attribute, and line-ending checks
  remain required for the final change validation.
- Follow-up: F1/F2 define container log rotation and the operator-selected local log consumer; later
  endpoint increments must extend the reviewed route-template allowlist. Synchronization operation IDs
  may be added to relevant events only when the D2/E3 protocol handling exists.
- Relevant decisions: ADR-0002, ADR-0006, ADR-0015, and ADR-0018.

#### C7. Add backend quality enforcement

- Status: `validated`.
- Depends on: C5.
- Scope: configure the Java compiler, JUnit 5, AssertJ, Checkstyle, Spotless, and ArchUnit with independent commands.
- Acceptance: formatting, unit tests, and architecture tests are deterministic. As code appears, focused checks reject framework or infrastructure imports in pure rules, dependency cycles, and controller access to persistence; representative violations fail.
- Current implementation: Checkstyle, PMD, and Spotless are configured. Spotless applies Google Java Format to Java sources and ktlint to Gradle Kotlin scripts. JUnit 5 is enabled through the JUnit Platform, and AssertJ is provided by Spring Boot's test starter. ArchUnit and executable architecture rules remain deferred until internal package boundaries exist.

### Track D: contracts and PostgreSQL

#### D1. Establish contract structure and validation

- Status: `validated`.
- Depends on: A3.
- Scope: author contracts in TypeSpec; generate OpenAPI 3.1 and JSON Schema Draft 2020-12; establish the contract and synchronization-fixture locations; and provide independent formatting, generation, drift, specification, schema, and negative-fixture checks.
- Excludes: synchronization envelopes, garden resources, authentication and authorization, generated Java or TypeScript boundary code, runtime request validation, controllers, adapters, and business behavior.
- Artifacts: ADR-0021; a pinned TypeSpec compiler and compatible HTTP, OpenAPI, and JSON Schema libraries; `contracts/typespec/`; generated `contracts/openapi/openapi.yaml`; the reserved `contracts/schemas/` and `contracts/sync/fixtures/` locations; contract validation scripts and fixtures; and updated API, architecture, and development documentation.
- Acceptance: the production TypeSpec source compiles without warnings and emits an endpoint-free OpenAPI 3.1 document; a synthetic compatibility fixture emits matching OpenAPI and JSON Schema representations for required, optional, nullable, constrained, closed-object, and literal-union shapes; generated output is byte-for-byte reproducible; independent validators accept valid contracts and reject deliberately invalid TypeSpec, OpenAPI, schemas, instances, unresolved references, and external references; and validation requires no network after frozen dependency installation.
- Validation commands: `pnpm install --frozen-lockfile`, `pnpm contracts:format:check`, `pnpm contracts:check-generated`, `pnpm contracts:lint`, `pnpm contracts:test`, `pnpm contracts:validate`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: on 2026-09-14, `pnpm install --frozen-lockfile`, `pnpm contracts:validate`, all five contract tests, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol` passed. Contract generation reproduced the committed OpenAPI byte-for-byte; Redocly accepted the production document and rejected the invalid OpenAPI fixture. Ajv accepted the valid instance and rejected missing-required, constrained, invalid-union, and extra-property payloads against both generated representations; the invalid schema was also rejected. The full `pnpm validate` run passed contract and frontend checks, including Playwright and both builds, but stopped at the unrelated Gradle root-build configuration error in `:spotlessJavaCheck` (`target` must be specified or the Java plugin applied); backend quality work remains tracked under C7.
- Follow-up: D2 adds the technical service and synchronization models and operations in TypeSpec; D2 and E1 add production schemas and protocol conformance fixtures. Choose TypeScript and Java code generators only when their respective adapter increments establish a need and can verify generated output quality.
- Relevant decisions: ADR-0003, ADR-0007, ADR-0009, ADR-0011, ADR-0018, and ADR-0021.

#### D2. Define technical service and synchronization envelopes

- Status: `validated`.
- Depends on: D1 and C5.
- Scope: specify only the versioned technical push and pull endpoints and protocol envelopes needed by
  the walking skeleton. Use one deliberately technical record, single-operation submission, stable
  stable client-generated UUID record and operation identifiers, operation-level idempotency, expected revisions, monotonic
  server sequences, and opaque incremental cursors.
- Excludes: garden resources, final reconciliation formats, authentication, analytics, backup, and catalog distribution.
- Artifacts: technical TypeSpec models and operations; generated OpenAPI 3.1 and JSON Schema Draft
  2020-12 artifacts; closed create, replace, result, change-page, and explicit error envelopes; generated
  artifact synchronization and drift checks; production-schema validation and focused constraint tests;
  and updated API and contract documentation.
- Acceptance: contracts describe path versioning; canonical lowercase UUID identifiers; an operation
  identifier that is also its idempotency identifier; create and expected-revision replace
  operations; precision-safe revision and sequence strings; opaque cursors; ordered change pages; and
  explicit invalid-request, missing-record, identifier-reuse, existing-record, and revision-conflict
  errors. Generated objects are closed, local references resolve without network access, committed
  artifacts reproduce byte-for-byte, and the selected slice does not define deferred production
  synchronization policy.
- Validation commands: `pnpm install --frozen-lockfile`, `pnpm contracts:format:check`,
  `pnpm contracts:check-generated`, `pnpm contracts:lint`, `pnpm contracts:test`,
  `pnpm contracts:validate`, `git diff --check`, `git ls-files -ci --exclude-standard`,
  `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: on 2026-09-14, frozen installation and all seven contract tests passed. TypeSpec
  compiled without diagnostics; generated OpenAPI and 16 standalone schemas reproduced byte-for-byte;
  Redocly accepted the OpenAPI contract without warnings; Ajv compiled every production schema together
  with local-only references; and focused tests rejected wrong UUID versions, uppercase identifiers,
  invalid revision and sequence representations, incorrect operation variants, empty cursors, unknown
  properties, and incomplete or unknown conflicts. Repository whitespace, ignore, attribute, and
  line-ending checks also passed. The full `pnpm validate` run passed the contract and frontend checks,
  including Playwright and both Angular builds, then stopped at the pre-existing C7 root Spotless
  configuration error; that independently tracked backend-quality failure is outside D2.
- Follow-up: B9 implements the browser HTTP boundary against these contracts; D3 adds the server
  persistence components; E1 adds canonical cross-runtime protocol examples without redefining the D2
  wire shapes. G1 through G4 add the deliberately deferred production synchronization policies and wire
  formats.
- Relevant decisions: ADR-0003, ADR-0009, ADR-0010, ADR-0018, and ADR-0021.

#### D3. Create PostgreSQL persistence components

- Status: `validated`.
- Depends on: C5 and D2.
- Scope: establish the Spring JDBC/PostgreSQL runtime and the persistence package boundary inside
  `services/sync`. Concrete technical-record SQL and transaction-owning acceptance methods remain
  deferred until D5a and E3; no placeholder persistence package is introduced.
- Excludes: JPA and garden persistence.
- Artifacts: Spring JDBC and PostgreSQL runtime dependencies, reviewed dependency locks and verification
  metadata, and datasource configuration consumed by the development topology.
- Acceptance: the service can create its JDBC runtime against PostgreSQL without JPA or an embedded
  database, and the deferred SQL boundary is documented for D5a/E3.
- Validation commands: `./gradlew --dependency-verification=strict :services:sync:spotlessCheck
  :services:sync:test --rerun-tasks`, `./gradlew --dependency-verification=strict
  :services:sync:build --rerun-tasks`, `HORTINIS_POSTGRES_PASSWORD=validation-only pnpm compose:validate`,
  and the repository-wide Git checks.
- Validation evidence: on 2026-09-15, strict dependency verification, formatting, the database-independent
  Spring test suite, and the complete sync-service build passed. The Fedora Compose run subsequently
  started the service against PostgreSQL after the cold Gradle cache completed, confirming datasource
  runtime initialization. No JPA or embedded database was introduced. Concrete SQL remains deferred to
  D5a/E3.

#### D4. Add PostgreSQL to the development topology

- Status: `validated`.
- Depends on: D3 and F1.
- Scope: add PostgreSQL 18 to the existing Compose topology with named persistent development storage,
  health ordering, and environment-provided credentials.
- Artifacts: PostgreSQL service and health check, `postgres-data` volume, sync datasource environment,
  `depends_on` health ordering, and documented startup, inspection, persistence, and shutdown behavior.
- Excludes: Flyway, application schema migrations, technical-record tables, and Testcontainers.
- Acceptance: Compose configuration validates; PostgreSQL becomes healthy before sync starts; both
  services become healthy with a configured local password; normal shutdown preserves the named volume;
  and no database credential is committed.
- Validation commands: `pnpm compose:validate`, `docker compose --env-file .env --file
  infrastructure/docker/compose.yaml up --wait`, `docker compose --env-file .env --file
  infrastructure/docker/compose.yaml ps`, `docker compose --env-file .env --file
  infrastructure/docker/compose.yaml down --remove-orphans`, and the repository-wide Git checks.
- Validation evidence: on 2026-09-15, Compose configuration validation passed. On Fedora, PostgreSQL
  18.0 became healthy, the sync service eventually became healthy after the cold Gradle cache completed,
  and the named `postgres-data` volume was created and retained by the topology. Credentials remained in
  the ignored local `.env` file. The initial health timeout and Gradle cache contention were resolved by
  using the image-installed Gradle binary and the extended startup grace period.

#### D5. Establish the Flyway lifecycle

- Status: `validated`.
- Depends on: D3 and D4.
- Scope: establish the Flyway lifecycle and configuration without selecting or creating application tables.
- Excludes: application schema design, technical-record tables, persistence queries, garden tables, and
  the production retention policy later selected by ADR-0023.
- Acceptance: Flyway is enabled for the service, an empty database can start cleanly without an
  application migration, and repeat startup remains idempotent.
- Follow-up from D3+D4: define the first application migration only after E1 establishes canonical
  conformance fixtures and the E3 transaction cases establish the minimum persistence requirements.

#### D5a. Define the first technical migration

- Status: `validated`.
- Depends on: D5 and E1.
- Scope: derive and implement the smallest additive PostgreSQL schema required by the synchronization
  walking skeleton and its E3 transaction cases.
- Excludes: garden tables, tombstones, retention or compaction policy, synchronization generations,
  reconciliation snapshots, and other production synchronization details now governed by ADR-0023
  through ADR-0026.
- Acceptance: the migration is traceable to the accepted fixtures and transaction cases, succeeds against
  an empty database, is idempotent across service restart, and introduces no unused placeholder tables.
- Artifacts: Flyway migration `V1__create_technical_sync_schema.sql` defining the current technical-record
  projection, accepted-operation idempotency receipts, and immutable technical-record change journal;
  the Spring Boot Flyway starter and PostgreSQL database module; and migration validation evidence.
- Validation commands: `./gradlew --write-verification-metadata sha256 :services:sync:dependencies
  --write-locks`, `./gradlew --dependency-verification=strict :services:sync:spotlessCheck
  :services:sync:test`, `HORTINIS_POSTGRES_PASSWORD=validation-only pnpm compose:validate`, and a
  disposable PostgreSQL 18 Compose startup, schema inspection, service restart, and Flyway-history check.
- Validation evidence: on 2026-09-16, a clean disposable PostgreSQL 18 volume applied Flyway version 1
  successfully and created only the three application tables plus `flyway_schema_history`. The sync
  service became healthy, and after service restart the migration history still contained exactly one
  successful version-one entry. Backend formatting and tests, dependency metadata generation, Compose
  validation, and repository whitespace checks passed.
- Follow-up: E3 owns the concrete JDBC SQL and atomic server-acceptance transaction that uses this schema.

#### D6. Add PostgreSQL integration tests

- Status: `validated`.
- Depends on: D5a.
- Scope: use Testcontainers to validate explicit SQL, transactions, migrations, and service restart behavior.
- Acceptance: tests run from a clean environment and prove rollback and atomicity assumptions used by synchronization.
- Artifacts: an independent Gradle `integrationTest` source set and task; pinned PostgreSQL 18
  Testcontainers dependencies with reviewed locks and verification metadata; schema and constraint tests;
  transaction rollback tests; and application-context restart coverage proving Flyway idempotence and data
  retention.
- Validation commands: `./gradlew --dependency-verification=strict :services:sync:test`,
  `./gradlew --dependency-verification=strict :services:sync:integrationTest`,
  `./gradlew :services:sync:spotlessCheck`, `./gradlew :services:sync:checkstyleMain
  :services:sync:checkstyleTest :services:sync:checkstyleIntegrationTest`,
  `./gradlew :services:sync:pmdMain :services:sync:pmdTest :services:sync:pmdIntegrationTest`, and
  the repository-wide Git checks.
- Validation evidence: on 2026-09-16, the integration-test task passed against disposable PostgreSQL 18
  Testcontainers. Tests verified Flyway version-one application and schema shape, valid three-row acceptance
  writes, generated and increasing server sequences, schema constraint enforcement, rollback after a
  constraint failure, rollback after an application failure, and data retention plus single-entry Flyway
  history across two application-context starts. E3 now owns the production acceptance SQL exercised by
  these integration tests.

### Track E: first synchronization walking skeleton

The first slice uses a deliberately technical record. It validates the mechanism without introducing a garden entity or implying a final resource model.

#### E1. Define technical record conformance fixtures

- Status: `validated`.
- Depends on: D2.
- Scope: define technical records, operations, results, errors, and language-neutral fixtures; compare
  validated operation requests structurally by their typed fields; and consume the shared corpus from
  the TypeScript and Java test runtimes.
- Artifacts: declarative valid and invalid JSON scenarios under `contracts/sync/fixtures/`; pure
  TypeScript protocol validation and typed comparison checks; a Java fixture consumer; and updated
  synchronization protocol and API documentation.
- Acceptance: TypeScript and Java consume the same valid and invalid examples, agree on operation
  validity and operation comparisons, and cover accepted operations, pull pages, explicit
  errors, server-state preconditions, invalid identifiers/revisions, closed operation variants, and
  opaque cursor validation.
- Validation evidence: the shared twelve-scenario corpus passes the TypeScript unit suite and the Java
  JUnit suite. TypeScript strict checking, linting, and formatting also pass; the Java synchronization
  tests compile and pass with the Gradle wrapper.

#### E2. Commit local state and an outbox operation atomically

- Status: `validated`.
- Depends on: B8 and E1.
- Scope: persist one technical local change and its stable outbox operation in one Dexie transaction.
- Acceptance: success persists both, failure persists neither, and reload retains the pending operation.
- Artifacts: the version-one technical-record, outbox, and accepted-result schema in `apps/web/src/app/persistence/database-schema.ts`; typed local projection and persistence components; a create-only local coordination service using the pinned `uuid` UUIDv7 generator; the UUID generation decision in ADR-0022; and isolated IndexedDB transaction, rollback, reload, and identifier tests.
- Excludes: replace workflow submission, server acceptance and stable-result persistence, cursors, conflict handling, retry policy, and user-facing synchronization state.
- Validation commands: `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web architecture:check`, `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web build:development`, `pnpm --filter @hortinis/web build`, and the repository-wide Git checks.
- Validation evidence: on 2026-09-15, the Angular/Vitest suite passed 13 tests across four files, including atomic success, rollback at either write, pending-operation retention after close/reopen, and canonical UUIDv7 generation. Prettier, Angular ESLint, the web architecture check, strict Angular/TypeScript checks, development build, and production build all passed. E2 established the version-one `technicalRecords` and `outboxOperations` stores; E3 extended that same pre-release version directly with `acceptedOperationResults`, without a migration.
- Relevant decisions: ADR-0002, ADR-0008, ADR-0010, ADR-0018, and ADR-0022.

#### E3. Push and atomically accept one operation

- Status: `validated`.
- Depends on: B9, D5a, D6, and E2.
- Scope: submit one operation per explicit HTTP request and atomically persist its idempotency receipt,
  accepted state, revision, server sequence, and immutable change journal. The browser uses a best-effort
  online hint before submitting, keeps the outbox row on offline or transport failure, and commits the
  accepted result back to IndexedDB only after a successful response. UUIDv7 remains the browser's
  generation choice, while the protocol validates canonical lowercase UUIDs without requiring a UUID
  version.
- Acceptance: create and replace operations are validated and accepted; malformed and non-canonical
  requests are rejected; idempotency receipts, accepted state, and the journal are written together;
  a failed journal write leaves no partial server state; and a failed browser push leaves the pending
  outbox operation intact. Calls are serialized one at a time; retry replay proof, batching, backoff,
  pull cursors, and user-facing conflict resolution remain outside E3.
- Artifacts: the typed Spring protocol, request parser, controller and error mapping; JDBC acceptance
  persistence and transactional synchronization service; the Angular network-status seam, one-operation
  synchronization service, and atomic accepted-result persistence; and PostgreSQL/Testcontainers plus
  browser unit coverage.
- Validation commands: `pnpm contracts:validate`, `pnpm --filter @hortinis/web format:check`,
  `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web architecture:check`,
  `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`,
  `./gradlew --dependency-verification=strict :services:sync:test :services:sync:integrationTest
  --rerun-tasks`, and the backend Spotless, Checkstyle, and PMD tasks.
- Validation evidence: on 2026-09-16, contracts validation passed; all six web test files and 29 tests
  passed with strict typecheck, lint, architecture, and formatting checks; backend unit and PostgreSQL
  integration tests passed (5 and 12 tests respectively); and Spotless, Checkstyle, and PMD completed
  successfully. Integration coverage proves exact accepted results, version-independent UUID acceptance,
  malformed request rejection, and rollback of all acceptance writes on journal failure. Browser coverage
  proves offline skip, transport-failure retention, accepted-result commit, and single-flight submission.
- Relevant decisions: ADR-0002, ADR-0008, ADR-0010, ADR-0018, and ADR-0022.

#### E4. Prove idempotent retry

- Status: `validated`.
- Depends on: E3.
- Scope: retry an identical stable operation and reject reuse of its idempotency identifier with different validated request fields.
- Acceptance: identical retries return the stable result without duplicate effects; mismatched reuse returns the specified error.
- Artifacts: PostgreSQL-backed HTTP integration coverage for create and replace replay, validated
  identifier reuse, and concurrent identical submissions; browser coverage for a lost acknowledgement
  followed by retry of the unchanged outbox operation. No schema, contract, or dependency changes are
  required.
- Validation commands: `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`,
  `pnpm --filter @hortinis/web architecture:check`, `pnpm --filter @hortinis/web typecheck`,
  `pnpm --filter @hortinis/web test`, `./gradlew --dependency-verification=strict
  :services:sync:test :services:sync:integrationTest`, and the backend Spotless, Checkstyle, and PMD
  tasks, followed by the repository-wide Git checks.
- Validation evidence: identical create and replace retries returned their original revision and server
  sequence without additional records, receipts, or journal entries; validated identifier reuse returned
  `OPERATION_ID_REUSED` without changing accepted state; concurrent identical submissions produced one
  acceptance bundle; and browser retry reused the exact persisted operation after a lost acknowledgement.
- Excludes: automatic retry scheduling or backoff, batching, dependent operation chains, pull cursors,
  conflict presentation, and idempotency-receipt compaction.

#### E4a. Prove one dependent operation chain

- Status: `validated`.
- Depends on: E4.
- Scope: persist and submit two causally dependent operations in order, deriving the successor's expected
  revision from its predecessor's stable result before first submission.
- Excludes: atomic multi-operation batches and arbitrary dependency graphs.
- Acceptance: the successor is never submitted before the predecessor has a stable result, and retries do
  not change either operation's identifier, request fields or expected revision.
- Artifacts: local-only deferred and ready outbox operation types; atomic dependent replacement persistence;
  explicit predecessor readiness checks; atomic successor revision materialization when the predecessor is
  accepted; and technical-record local replacement workflow and browser coverage. The wire contract and
  PostgreSQL schema remain unchanged.
- Validation commands: `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`,
  `pnpm --filter @hortinis/web architecture:check`, `pnpm --filter @hortinis/web typecheck`,
  `pnpm --filter @hortinis/web test`, followed by the E4 backend and repository-wide checks.
- Validation evidence: on 2026-09-16, the Angular/Vitest suite passed 36 tests across six files. Coverage
  proves durable deferred successor persistence, predecessor-before-successor submission even when IDs sort
  out of causal order, revision derivation, preservation of newer local intent, lost-acknowledgement retry
  invariants for both operations, and rollback when successor resolution cannot be completed. Formatting,
  linting, strict Angular/TypeScript checks, and the web architecture check passed.
- Relevant decisions: ADR-0002, ADR-0008, ADR-0010, ADR-0018, and ADR-0022.

#### E5. Pull changes through an opaque cursor

- Status: `validated`.
- Depends on: E3.
- Scope: retrieve accepted changes ordered by server sequence and persist the new cursor with their local application.
- Acceptance: repeated pulls create no duplicates and cannot persist a cursor beyond unapplied changes.
- Artifacts: the typed change-page and technical-change protocol models, opaque cursor codec, server-side
  ordered change-page retrieval, browser cursor persistence, atomic pulled-page application, and browser
  and PostgreSQL integration coverage.
- Validation commands: `pnpm contracts:validate`, `pnpm --filter @hortinis/web format:check`,
  `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web architecture:check`,
  `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`,
  `./gradlew --dependency-verification=strict :services:sync:test :services:sync:integrationTest`,
  the backend Spotless, Checkstyle, and PMD tasks, followed by the repository-wide Git checks.
- Validation evidence: the browser persistence tests apply ordered pages atomically, retain local intent
  while applying remote changes, reject unordered pages without advancing the cursor, and reuse the
  persisted cursor on the next pull. The server tests prove opaque cursor validation, sequence-ordered
  pages, page-size boundaries, `hasMore`, and continuation from the returned cursor. The current web
  baseline passes formatting, lint, architecture, strict typecheck, and all unit tests.
- Relevant decisions: ADR-0002, ADR-0008, ADR-0010, ADR-0018, and ADR-0022.

#### E6. Recover synchronization after browser reload

- Status: `validated`.
- Depends on: E2 through E5.
- Scope: restore local state, pending operations, stable results, and cursors after reloading the browser application.
- Acceptance: synchronization resumes without losing or duplicating accepted local intent.
- Artifacts: non-blocking Angular startup recovery, serialized pending-operation draining, paginated pull
  recovery from the persisted cursor, single-flight recovery protection, IndexedDB close/reopen coverage,
  and Chromium browser-reload coverage with strict operation and cursor assertions.
- Validation commands: `pnpm contracts:validate`, `pnpm --filter @hortinis/web format:check`,
  `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web architecture:check`,
  `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`,
  `pnpm --filter @hortinis/web test:e2e`, `pnpm --filter @hortinis/web build`,
  `./gradlew --dependency-verification=strict :services:sync:test :services:sync:integrationTest`,
  the backend Spotless, Checkstyle, and PMD tasks, followed by the repository-wide Git checks.
- Validation evidence: 45 web unit tests and 3 Chromium E2E tests passed. The reload scenario restored
  an IndexedDB outbox operation and opaque cursor, submitted the exact persisted operation, committed
  its stable result once, pulled from the saved cursor, and advanced the cursor only after the page was
  applied. Backend unit and PostgreSQL integration tests passed, as did contract validation and backend
  quality checks.
- Excludes: automatic retry backoff, periodic background synchronization, conflict presentation and
  resolution, synchronization-history compaction, and full reconciliation.

#### E7. Represent an expected-revision conflict

- Status: `validated`.
- Depends on: E5.
- Scope: submit an operation based on a stale server revision and return an explicit conflict without silent last-write-wins behavior.
- Excludes: final domain-specific conflict presentation and resolution.
- Acceptance: the accepted server state and competing proposal remain identifiable and independent local work remains usable.
- Artifacts: the existing `REVISION_CONFLICT` contract and server behavior are exercised by a stale-write
  PostgreSQL integration scenario; the browser development schema remains at version 1 and adds a
  durable revision-conflict table; conflict persistence retains the exact outbox proposal, quarantines it
  from automatic resubmission, and exposes a typed conflict outcome while unrelated operations continue.
- Validation commands: `pnpm contracts:validate`, `pnpm --filter @hortinis/web format:check`,
  `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web architecture:check`,
  `pnpm --filter @hortinis/web typecheck`, `pnpm --filter @hortinis/web test`,
  `pnpm --filter @hortinis/web test:e2e`, `./gradlew --dependency-verification=strict
  :services:sync:spotlessCheck :services:sync:test :services:sync:integrationTest`, the backend
  Checkstyle and PMD tasks, followed by the repository-wide Git checks.
- Validation evidence: on 2026-09-17, contract validation passed; the Angular/Vitest suite passed 47
  tests across seven files; Chromium passed four browser scenarios including conflict persistence and
  independent-operation continuation; backend Spotless, unit tests, PostgreSQL integration tests,
  Checkstyle, and PMD passed. The stale-write integration test confirmed that the server retained the
  accepted revision and created no receipt or change for the rejected proposal.
- Schema note: this development-only increment intentionally keeps Dexie at schema version 1; existing
  local development databases must be recreated when adopting the new table. Production migration
  policy remains outside this slice.

#### E8. Demonstrate offline and failure independence

- Status: `validated`.
- Depends on: E2 and B4.
- Scope: create additional local technical work while the service is unreachable or a synchronization operation has failed.
- Acceptance: local commits remain available, failures are observable, and recovery does not require discarding local storage.
- Artifacts: a privacy-safe observable synchronization status signal; explicit recovery status transitions;
  offline and unavailable-service independence coverage through the real local technical-record workflow;
  and offline production-shell validation with external font assets disabled.
- Validation commands: `pnpm --filter @hortinis/web format:check`, `pnpm --filter @hortinis/web lint`,
  `pnpm --filter @hortinis/web architecture:check`, `pnpm --filter @hortinis/web typecheck`,
  `pnpm --filter @hortinis/web test`, `pnpm --filter @hortinis/web build`,
  `pnpm --filter @hortinis/web test:e2e`, followed by the repository-wide Git checks.
- Validation evidence: the Angular/Vitest suite passed 49 tests. New coverage proves two independent
  local commits complete while offline, no transport request is attempted, offline status is observable,
  and both operations are later accepted through explicit recovery. A separate scenario proves that a
  failed unavailable-service submission is observable, later local work still commits, both outbox rows
  remain durable, and recovery accepts both operations without clearing local storage. Chromium passed
  all four production-shell, offline-reload, reload-recovery, and conflict scenarios. External Google
  Fonts and Material Icons links are commented in the foundation shell so production builds do not
  require network access for font inlining.
- Excludes: automatic retry scheduling, exponential backoff, periodic background synchronization,
  synchronization-history compaction, and user-facing product workflow screens.

#### E9. Run cross-runtime conformance validation

- Status: `validated`.
- Depends on: E1 through E8.
- Scope: execute shared fixtures against TypeScript and Java interpretations of the implemented protocol slice.
- Acceptance: typed comparison, validation, identifiers, revisions, results, and error classifications agree.
- Artifacts: strengthened TypeScript and Java shared-fixture consumers; production-rule and typed-model
  validation for operation requests, opaque cursors, accepted results, change pages, and all protocol
  error classifications; canonical request assertions; and the root `conformance:validate` command.
- Validation commands: `pnpm conformance:validate`, `pnpm --filter @hortinis/web format:check`,
  `pnpm --filter @hortinis/web lint`, `pnpm --filter @hortinis/web architecture:check`,
  `pnpm --filter @hortinis/web typecheck`, followed by the repository-wide Git checks.
- Validation evidence: on 2026-09-17, the shared twelve-scenario corpus passed the TypeScript suite
  (49 tests) and Java unit suite (7 tests). TypeScript production validators now check typed operation,
  record, result, change-page, error, cursor, and closed-shape behavior; Java conformance uses the
  production request parser, `OperationRules`, `SyncCursorCodec`, and typed protocol/error models.
  Canonical requests, typed operation equality, invalid identifiers/revisions, opaque cursor validity,
  accepted results, change pages, and each error classification are exercised. Formatting and strict
  dependency verification passed for the modified consumers.

#### E10. Publish the V0 technical readiness report

- Initial status: `planned`.
- Depends on: B4, B7 through B9, C6, C7, D1 through D6, E1 through E9, F1, and F3.
- Scope: run the integrated browser, service, and PostgreSQL V0 topology and record commands,
  environments, evidence, limitations, failures found, and remaining protocol risks.
- Acceptance: every item in the V0 technical readiness gate is traced to passing evidence; the report
  distinguishes demonstrated behavior from planned behavior and authorizes only the V0 product track.

### Track F: packaging and continuous integration

This track may proceed alongside the web, server, contract, and synchronization tracks once its prerequisites exist.

#### F1. Add the minimal Compose topology

- Status: `validated`.
- Depends on: C5.
- Scope: provide standard local and self-hosting orchestration without making container development mandatory.
- Artifacts: the sync service Compose definition under `infrastructure/docker/compose.yaml`, a named
  Gradle cache volume, an HTTP readiness health check against `/actuator/health/readiness`, an
  independently runnable Compose validation command, and documented startup, inspection, and shutdown
  commands.
- Acceptance: the topology validates and the sync service starts, becomes healthy through its readiness
  endpoint, and stops predictably.
- Validation commands: `pnpm compose:validate`, `docker compose --file infrastructure/docker/compose.yaml up --wait`, `docker compose --file infrastructure/docker/compose.yaml ps`, `docker compose --file infrastructure/docker/compose.yaml down --remove-orphans`, `git diff --check`, `git ls-files -ci --exclude-standard`, `git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat`, and `git ls-files --eol`.
- Validation evidence: on 2026-09-15, the Compose document and repository-level checks passed. The
  combined D3/D4 topology then started PostgreSQL and sync successfully; both containers reported
  healthy, sync passed the `/actuator/health/readiness` check, `ps` confirmed their healthy state, and
  `down --remove-orphans` stopped and removed the containers and network predictably.
- Follow-up: F2 adds separate development and production multi-stage container builds; F3 validates
  same-origin shell and API routing.

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

#### F3a. Resolve the production edge and TLS design

- Initial status: `planned`.
- Depends on: F2 and F3.
- Scope: select and document the supported production reverse proxy, certificate provisioning and renewal,
  trusted-proxy handling, security headers, forwarded metadata, and operator configuration in an accepted
  architecture decision.
- Excludes: implementing automated deployment or selecting an optional hosting provider.
- Acceptance: F3b has a testable production topology and security contract, with no implicit CORS or
  public Spring-service dependency.

#### F3b. Implement the production edge routing

- Initial status: `planned`.
- Depends on: F3a.
- Scope: route `/` to the built Angular application and `/api/*` to Spring in the selected production edge implementation.
- Acceptance: the selected production topology serves the shell and API through one origin without publicly exposing the Spring service.

#### F4. Add incremental GitHub Actions validation

- Initial status: `planned`.
- Depends on: the corresponding local command from each implemented increment. Its final acceptance for
  G6 includes the checks introduced by Track G.
- Scope: add jobs progressively for documentation, formatting, linting, type checking, tests, architecture checks, builds, image builds, and Compose validation.
- Excludes: automated deployment.
- Acceptance: CI invokes pnpm and Gradle explicitly, reports failures by toolchain, and reproduces documented local checks.

#### F5. Validate native and container development paths

- Initial status: `planned`.
- Depends on: F1, F2, F3, and F4. The production-edge design and implementation in F3a and F3b are MVP
  release prerequisites, not prerequisites for validating development paths.
- Scope: document and verify native development and the optional container-assisted path.
- Acceptance: the Dev Container, when added, reuses the Compose environment and does not define a competing topology.

#### F6. Validate autonomous runtime operation

- Initial status: `planned`.
- Depends on: F2 and F3.
- Scope: prove that an installed deployment runs without a package registry, Hortinis-operated service, analytics provider, plant provider, or optional external adapter.
- Acceptance: the validation records all attempted outbound runtime dependencies and demonstrates that none is required for the implemented foundation slice.

### Track G: production synchronization completion

Track G turns the guarantees that are deliberately deferred from V0 into numbered prerequisites for
production product work. It starts after E10 and may proceed alongside eligible Track F work. G1 must
resolve the open protocol policies before an implementation task relies on them.

#### G1. Resolve production synchronization policies

- Status: `specified`; final validation remains blocked by the planned E10 dependency.
- Depends on: E10.
- Scope: accept the remaining retention, compaction, synchronization-generation, tombstone, anchored
  snapshot, continuation, indeterminate-outcome, and full-reconciliation wire decisions identified in
  `docs/architecture/open-decisions.md`.
- Excludes: domain-specific merge and conflict-resolution rules.
- Acceptance: one or more accepted architecture decisions define testable policies and wire behavior
  without weakening ADR-0010's preservation of pending local intent.
- Artifacts: ADR-0023 through ADR-0026; contract-first generation, deletion, tombstone, reconciliation,
  snapshot-continuation, indeterminate-outcome, and generic reconciliation schemas; anchored
  reconciliation endpoints; canonical policy fixtures; a policy-to-implementation traceability matrix;
  generated-contract validation; and updated architecture, API, and contract documentation.
- Validation: contract formatting, generation drift, OpenAPI linting, schema validation, canonical
  positive examples, and focused negative checks must pass. After E10 is validated, review its V0 risk
  evidence against these decisions before changing G1 to `validated`.

#### G2. Implement tombstones and interrupted-exchange recovery

- Initial status: `planned`.
- Depends on: G1.
- Scope: add technical tombstones, resumable exchanges, bounded background retry, retry exhaustion, and
  observable manual recovery across Dexie, HTTP contracts, Spring, and PostgreSQL.
- Acceptance: deletion propagates without resurrection, interrupted exchanges resume safely, and retry
  scheduling neither blocks local work nor hides permanent failure.

#### G3. Implement generation rollover and indeterminate outcomes

- Initial status: `planned`.
- Depends on: G2.
- Scope: associate client state and operations with a synchronization generation, reject expired
  incremental histories, and preserve operations whose acceptance can no longer be proven as explicit
  indeterminate local work.
- Acceptance: a lost acknowledgement followed by receipt compaction and generation rollover cannot
  duplicate, discard, or silently accept the pending intent.

#### G4. Implement anchored snapshots and full reconciliation

- Initial status: `planned`.
- Depends on: G3.
- Scope: transfer an internally consistent snapshot anchored to a server sequence, continue strictly after
  that sequence, and reconcile it with the client's last-synchronized base and pending operation journal.
- Excludes: domain-specific automatic merges beyond the protocol's safe generic cases.
- Acceptance: concurrent writes during a paginated snapshot are not missed; non-overlapping state is
  retained and contradictions remain explicit without overwriting pending local work.

#### G5. Validate synchronization retention and recovery

- Initial status: `planned`.
- Depends on: G4, B8, and D6.
- Scope: exercise compaction, tombstone retention, browser migrations, transaction recovery, server
  migrations, restart recovery, reconciliation repetition, and the ADR-0010 failure scenarios.
- Acceptance: shared fixtures, browser tests, PostgreSQL integration tests, and end-to-end tests prove the
  complete technical protocol guarantees across supported recovery paths.

#### G6. Publish the foundation readiness report

- Initial status: `planned`.
- Depends on: G1 through G5, F2, F3, F4, F5, and F6.
- Scope: run the complete native, Compose, container-image, CI, migration, restart, synchronization, and
  autonomous-runtime validation matrix and record remaining risks.
- Acceptance: every item in the foundation readiness gate is traced to passing evidence, no blocking
  foundation risk remains, and the report identifies feature-specific product decisions that remain
  prerequisites rather than treating them as foundation completion work.

## 6. Readiness gates and milestone boundaries

### V0 technical readiness gate

The first synchronized product test may begin only when E10 is validated. E10 has exact dependencies on
B4, B7 through B9, C6, C7, D1 through D6, E1 through E9, F1, and F3; their transitive dependencies are
therefore required as well. Together they must demonstrate that:

- the Angular shell builds, has PWA application-shell support and reloads offline;
- Dexie schema versioning, migrations, transaction rollback and test isolation are demonstrated;
- the browser synchronization transport boundary rejects invalid data and exposes failures;
- the Spring service, safe diagnostics and backend architecture checks pass;
- the OpenAPI and JSON Schema synchronization contracts validate before their adapters;
- PostgreSQL, Flyway, explicit acceptance transactions and restart behavior pass integration tests;
- one technical record completes atomic local state and outbox commit, push, atomic acceptance,
  idempotent retry, one dependent operation chain, pull, cursor persistence and reload recovery;
- an expected-revision conflict is represented explicitly without implementing domain conflict resolution;
- synchronization failure does not prevent independent local work;
- shared TypeScript and Java fixtures agree for the implemented protocol slice;
- one documented native or Compose test topology exercises the browser, service and PostgreSQL together;
  and
- a focused V0 readiness report records commands, evidence, limitations and deferred protocol behavior.

This gate does not require production container images, complete CI, production authentication, catalog
HTTPS acquisition, tombstones, compaction, generation rollover, full reconciliation, indeterminate-outcome
recovery or domain conflict-resolution UI. While those mechanisms are absent, V0 must expose no hard
deletion, must not compact synchronization history or idempotency receipts, and must use explicit manual
retry rather than claiming complete background recovery.

Passing this gate authorizes only the V0 increments in the product implementation plan. It does not
authorize the production MVP increments.

### Initial foundation milestone

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

Completion of this milestone validates a thin vertical foundation slice. It does not validate every
guarantee in ADR-0010 and does not authorize production garden functionality until the Foundation
readiness gate below is complete. The limited V0 slice is governed separately by the V0 technical
readiness gate above.

### Foundation readiness gate

Production business-feature implementation may begin only when G6 is validated. G6 depends on G1 through
G5 and on F2, F3, F4, F5, and F6; their transitive dependencies include the initial foundation milestone.
Together they must demonstrate that:

- the initial foundation milestone is validated;
- causal operation chains, tombstones, interrupted exchanges, bounded retry behavior, generation rollover, anchored snapshots, full reconciliation, and indeterminate outcomes are implemented and tested;
- browser schema migration, transaction recovery, server migration, and restart behavior are demonstrated;
- native, Compose, container-image, and CI validation pass; and
- the foundation report contains no unresolved blocking foundation risk.

Feature-specific decisions remain additional prerequisites. For example, synchronized garden data requires the accepted access design, and catalog-dependent features require the catalog acquisition design.

### Synchronized V0 product-validation authorization

The complete gate above remains mandatory for production business features. After the V0 technical
readiness gate passes, the product plan may introduce its limited V0 slice under these constraints:

- every user-owned mutation commits local state and its outbox operation atomically;
- V0 uses the same synchronization transport, operation, idempotency, revision, sequence and cursor
  boundaries intended for production;
- a minimal dependent operation chain is supported rather than bypassing ordering requirements;
- protocol conflicts are detected and preserved explicitly, while domain merge, presentation and
  resolution remain deferred;
- catalog artifacts are acquired independently on each client and are not synchronized as user data;
- the slice is explicitly non-production and uses disposable or migration-safe validation data; and
- it is excluded from MVP release claims until the complete foundation gate passes.

For automated and developer-run V0 validation, a test-only single synchronization scope may be used
without selecting the production access mechanism. It must be unavailable in production builds and must
not be exposed as a remotely reachable unauthenticated deployment. Any external user test requires the
P0.5 access design and its necessary architecture decision first.

### Gate-to-product handoff

- E10 authorizes only V0.1 through V0.3 in the product implementation plan.
- G6 authorizes production product increments only when each increment's P0 and domain prerequisites are
  also complete.
- V0 completion is evidence and a product prerequisite for later work, but it does not replace G1 through
  G6 or authorize production implementation by itself.
- Foundation identifiers define technical ordering; product identifiers define domain and experience
  ordering. A product increment must list both kinds when both are direct prerequisites.

## 7. Deferred and prohibited selections

The following must not be resolved accidentally while executing this plan:

- concrete garden API resources and domain persistence shapes;
- authentication library, roles, permissions, optional-account server access, session expiry, recovery, and device revocation;
- production reverse proxy and TLS product selection;
- final backup, restoration, rollback, and upgrade formats and procedures;
- optional S3-compatible adapter implementation;
- the exact catalog version pin, consumer-fixture update procedure, and production HTTPS/update details;
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
