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

The technical foundation targets the following physical structure:

```text
apps/
  web/                         Angular presentation and composition root

packages/
  web-domain/                  Pure TypeScript rules and value types
  web-application/             Offline use cases and ports
  web-adapters-dexie/          IndexedDB persistence adapter
  web-adapters-sync-http/      Synchronization transport adapter

backend/
  domain/                      Framework-independent Java domain
  application/                 Server use cases and ports
  sync-protocol/               Framework-independent synchronization primitives
  adapters/
    postgres/                  PostgreSQL persistence adapter
    filesystem/                Local filesystem storage adapter
    catalog/                   Plant catalog acquisition adapter

services/
  sync/                        Spring Boot composition and HTTP delivery

contracts/
  openapi/                     HTTP contracts
  schemas/                     Language-neutral schemas
  sync/
    fixtures/                  Cross-runtime synchronization conformance cases

infrastructure/
  docker/                      Development and production container resources

tooling/                       Shared tooling configuration
tests/                         Cross-component, architecture, deployment, and end-to-end tests
```

Unit tests remain beside their corresponding modules. The root `tests/` directory is reserved for tests that cross module, runtime, process, or deployment boundaries.

The directories may be introduced only when their first planned increment requires them. The intended final structure is not authorization to generate every empty directory during the first change.

### 3.2 Web dependency boundaries

The web application must enforce the same inward dependency rule as the rest of the architecture:

```text
apps/web
    |
    v
@hortinis/web-application
    |
    v
@hortinis/web-domain

@hortinis/web-adapters-dexie ------> web-application ports
@hortinis/web-adapters-sync-http --> web-application ports
```

The packages provide build-visible boundaries rather than relying only on folders inside the Angular application. This separation is required because it:

- prevents domain code from importing Angular, Dexie, browser storage, or HTTP concerns;
- permits domain and application tests without starting Angular or IndexedDB;
- isolates core behavior from Angular upgrades and presentation changes;
- allows persistence and synchronization adapters to be replaced;
- identifies the Angular application as the outer composition root;
- lets CI detect dependency violations;
- makes equivalent browser and server behavior testable with shared conformance fixtures.

The additional package configuration and boundary mappings are accepted costs of treating the offline browser as an application runtime rather than a thin user interface.

The packages are introduced incrementally. The empty Angular application precedes the domain, application, and adapter packages.

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
- Keep Angular and Spring at composition and delivery boundaries.
- Keep infrastructure behind ports owned by inner layers.
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

#### A2. Add repository-wide text and ignore conventions

- Initial status: `planned`.
- Scope: add minimal editor and ignore conventions required by both toolchains.
- Excludes: generated application code and dependency installation.
- Acceptance: conventions do not hide source, contracts, wrappers, or required reproducibility files.

#### A3. Initialize the pnpm workspace

- Initial status: `planned`.
- Depends on: A2.
- Scope: pin Node.js 24 LTS and pnpm, create one workspace and lockfile, and expose attributable frontend validation commands.
- Excludes: Angular generation.
- Acceptance: installation is reproducible and the empty workspace validation commands succeed.

#### A4. Initialize the Gradle multi-project build

- Initial status: `planned`.
- Depends on: A2.
- Scope: add the Gradle Wrapper, Kotlin DSL settings, Java 25 requirements, dependency verification where supported, and attributable backend validation commands.
- Excludes: Spring Boot and application modules.
- Acceptance: wrapper integrity checks and an empty build succeed with the documented JDK.

#### A5. Document executable validation entry points

- Initial status: `planned`.
- Depends on: A3 and A4.
- Scope: replace the pre-scaffold-only development instructions with exact independent commands as capabilities become runnable.
- Acceptance: formatting, linting, type checking, tests, architecture checks, and builds remain separately invocable rather than hidden behind a monorepo orchestrator.

### Track B: minimal Angular application

#### B1. Generate an empty standalone Angular application

- Initial status: `planned`.
- Depends on: A3.
- Scope: create `apps/web` with strict TypeScript and a minimal application shell.
- Excludes: PWA support, Dexie, synchronization, domain behavior, and business UI.
- Acceptance: development build, production build, strict type checking, and the initial unit-test command succeed.

#### B2. Enforce frontend formatting and linting

- Initial status: `planned`.
- Depends on: B1.
- Scope: add Prettier, Angular ESLint, and explicit validation commands.
- Acceptance: formatting and linting are deterministic and independently runnable.

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

#### B5. Create the TypeScript domain package

- Initial status: `planned`.
- Depends on: B2.
- Scope: create `@hortinis/web-domain` as a pure strict-TypeScript package.
- Excludes: Angular, browser APIs, Dexie, HTTP, technical sync storage, and garden behavior.
- Acceptance: forbidden dependencies are absent and pure unit tests can run independently.

#### B6. Create the TypeScript application package

- Initial status: `planned`.
- Depends on: B5.
- Scope: create `@hortinis/web-application`, depending only on the web domain, and establish port conventions.
- Excludes: concrete adapters and business use cases.
- Acceptance: dependency checks prevent imports from presentation and infrastructure packages.

#### B7. Add automated web boundary checks

- Initial status: `planned`.
- Depends on: B5 and B6.
- Scope: enforce the approved package dependency direction during local validation and CI.
- Acceptance: representative forbidden imports cause the check to fail.

#### B8. Create the Dexie adapter

- Initial status: `planned`.
- Depends on: B6.
- Scope: create `@hortinis/web-adapters-dexie`, an initial empty versioned IndexedDB schema, and migration test infrastructure.
- Excludes: garden records and synchronization behavior.
- Acceptance: database creation, version discovery, migration execution, transaction rollback, and test isolation are demonstrated.

#### B9. Create the HTTP synchronization adapter boundary

- Initial status: `planned`.
- Depends on: B6 and the technical synchronization contracts in D2.
- Scope: create `@hortinis/web-adapters-sync-http` and map generated or validated boundary shapes without connecting business behavior.
- Acceptance: the adapter depends on application ports and contract boundary types without leaking transport shapes inward.

### Track C: minimal Spring application

#### C1. Declare backend modules

- Initial status: `planned`.
- Depends on: A4.
- Scope: declare `backend/domain`, `backend/application`, and `backend/sync-protocol` as initially empty Gradle projects.
- Acceptance: Gradle project dependencies reflect the approved inward direction.

#### C2. Establish the Java domain boundary

- Initial status: `planned`.
- Depends on: C1.
- Scope: configure `backend/domain` under `com.hortinis` without framework dependencies.
- Excludes: garden behavior, persistence annotations, Spring, and transport types.
- Acceptance: the module compiles and dependency inspection proves that no forbidden framework is present.

#### C3. Establish the Java application boundary

- Initial status: `planned`.
- Depends on: C2.
- Scope: configure `backend/application`, depending only on permitted inner modules, and establish port conventions.
- Excludes: concrete adapters and business use cases.
- Acceptance: forbidden outer-layer dependencies fail an automated architecture check.

#### C4. Establish the synchronization protocol module

- Initial status: `planned`.
- Depends on: C1.
- Scope: configure `backend/sync-protocol` for framework-independent protocol primitives.
- Excludes: HTTP controllers, JDBC, and resource-specific domain rules.
- Acceptance: it compiles without Spring or persistence dependencies.

#### C5. Create the empty Spring Boot synchronization service

- Initial status: `planned`.
- Depends on: C1 through C4.
- Scope: create `services/sync` as the Spring Boot composition and HTTP-delivery boundary.
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
- Depends on: C1.
- Scope: configure the Java compiler, JUnit 5, AssertJ, Checkstyle, Spotless, and ArchUnit with independent commands.
- Acceptance: formatting, unit tests, and architecture tests are deterministic; representative boundary violations fail.

### Track D: contracts and PostgreSQL

#### D1. Establish contract structure and validation

- Initial status: `planned`.
- Depends on: A3 or A4 as required by the selected validator.
- Scope: create OpenAPI 3.1, JSON Schema, and synchronization fixture locations with validation commands.
- Excludes: garden resources and unresolved authentication contracts.
- Acceptance: valid minimal contracts pass and deliberately invalid fixtures fail.

#### D2. Define technical service and synchronization envelopes

- Initial status: `planned`.
- Depends on: D1 and C4.
- Scope: specify only the technical endpoints and protocol envelopes needed by the walking skeleton.
- Excludes: garden resources, final reconciliation formats, authentication, analytics, backup, and catalog distribution.
- Acceptance: contracts describe versioning, identifiers, idempotency, revisions, sequences, cursors, and explicit protocol errors required by the selected slice.

#### D3. Create the PostgreSQL adapter module

- Initial status: `planned`.
- Depends on: C3 and D2.
- Scope: create the explicit-SQL Spring JDBC adapter boundary.
- Excludes: JPA and garden persistence.
- Acceptance: application and domain modules remain independent of Spring JDBC and PostgreSQL.

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
- the TypeScript domain, application, Dexie adapter, and HTTP adapter boundaries are enforced;
- the Spring service builds, exposes safe health information, and preserves Java module boundaries;
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

- dependency direction and composition boundaries;
- offline-first behavior and preservation of accepted local work;
- deterministic, observable, recoverable synchronization;
- contract-first adapter implementation;
- privacy-safe diagnostics and absence of prohibited data in logs;
- autonomous self-hosting and optional external providers;
- reproducible native, container, and CI validation;
- English language consistency in repository content;
- absence of business functionality, secrets, generated credentials, local data, and environment-specific configuration.

Before executable validation exists, use the [pre-scaffold development checks](README.md). As each increment introduces runnable checks, document and execute those checks in addition to the pre-scaffold review.
