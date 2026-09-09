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
pnpm --filter @hortinis/web typecheck
pnpm --filter @hortinis/web test
pnpm --filter @hortinis/web build:development
pnpm --filter @hortinis/web build
```

Browser end-to-end testing, PWA support, application packages, persistence, and synchronization are introduced by later foundation increments.

See the repository [development guide](../../docs/development/README.md) for toolchain requirements and repository-wide validation.
