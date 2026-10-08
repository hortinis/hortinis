# Hortinis web application

This directory contains the Angular browser application for Hortinis. It includes the application shell, browser persistence, technical-record synchronization, and bounded recovery. Business workflows remain planned.

Run frontend commands from the repository root using the pnpm workspace.

## Development server

```shell
pnpm --filter @hortinis/web start
```

The development server is available at `http://localhost:4200/`.

## Validation

Run each validation entry point independently:

```shell
pnpm --filter @hortinis/web format:check
pnpm --filter @hortinis/web lint
pnpm --filter @hortinis/web typecheck
pnpm --filter @hortinis/web test
pnpm --filter @hortinis/web build:development
pnpm --filter @hortinis/web build
```

Apply configured formatting and automatically fix supported lint violations when needed:

```shell
pnpm --filter @hortinis/web fix
```

The combined command runs lint fixes before formatting. The underlying commands remain independently runnable:

```shell
pnpm --filter @hortinis/web format
pnpm --filter @hortinis/web lint:fix
```

### Browser smoke test

Install the Playwright Chromium binary once for the local environment:

```shell
pnpm --filter @hortinis/web exec playwright install chromium
```

Run the browser tests; Playwright builds the production bundle and starts and stops its static server automatically:

```shell
pnpm --filter @hortinis/web test:e2e
```

Browser tests cover the application shell, offline reload, persisted synchronization recovery, revision
conflicts, and tombstone application after reload. They use a single Chromium project. The real browser-to-Spring-to-PostgreSQL
integration suite runs as part of `pnpm validate` and independently from the repository root with
`pnpm test:e2e:topology`. It creates an isolated database and removes its test volumes afterward. Business workflow coverage belongs to the
product increments.

See the repository [development guide](../../docs/development/README.md) for toolchain requirements and repository-wide validation.

## Synchronization entry point

`TechnicalRecordSynchronizationService` exposes reload recovery, background recovery after local
commits, explicit manual retry, and standalone push/pull methods. Its existing status signal and type
import paths remain available. Standalone push/pull returns `busy` when exchange is active or another
tab owns the lease; an empty outbox returns `empty`. Recovery retains `already-running` for active
recovery or another tab's lease. Explicit reload recovery and manual retry wait for a standalone
exchange to finish, then retain their retry/reset intent. Contention outcomes do not consume an attempt
or replace the active status. Destroying the facade permanently cancels triggers and prevents queued
recovery from starting; in-flight work can still settle durable state and release its lease.

The facade owns small concrete collaborators for execution, recovery, push, pull, retry settlement,
status, and triggers. Recovery uses explicit ownership internally, preserving the existing fenced
IndexedDB transactions. Unrelated JavaScript errors publish `unknown`; storage-origin failures retain
`local-persistence`. Neither change modifies wire contracts or persisted data.

Production synchronization and persistence files are limited to 350 nonblank, noncomment lines and
cyclomatic complexity 15. The facade has a 150-line limit. Specs and testing helpers are excluded.
Existing wire validators retain a measured complexity ceiling of 25. The persistence component retains
measured ceilings of 606 lines and complexity 38 until H6 splits it; these allowances apply only to the
named files. Architecture tests verify enforcement. No new dependencies are required.
