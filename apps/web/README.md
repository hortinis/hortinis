# Hortinis web application

This directory contains the Angular browser application for Hortinis. It will contain the user interface, browser-side rules, synchronization, and local persistence as those capabilities are introduced.

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

Run the browser smoke test; Playwright starts and stops the Angular development server automatically:

```shell
pnpm --filter @hortinis/web test:e2e
```

The smoke test verifies that the application shell responds and renders the Hortinis heading in Chromium. It does not validate PWA installation, offline behavior, persistence, synchronization, or business workflows.

See the repository [development guide](../../docs/development/README.md) for toolchain requirements and repository-wide validation.
