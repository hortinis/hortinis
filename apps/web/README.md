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
conflicts, and tombstone application after reload. They use a single Chromium project. The G2e
integration suite is intentionally separate because it requires the real Compose service and PostgreSQL;
run it from the repository root with `pnpm g2e:topology`. Business workflow coverage belongs to the
product increments.

See the repository [development guide](../../docs/development/README.md) for toolchain requirements and repository-wide validation.
