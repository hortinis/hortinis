# Hortinis

Hortinis is an offline-first garden-management web application built with Angular.
The repository root is the application: `src` contains the UI and local IndexedDB
persistence, `public` contains static assets, and `e2e` contains browser tests.

## Development

Use Node.js 24.18.0 and pnpm 11.26.0:

```shell
pnpm install --frozen-lockfile
pnpm start
```

Open `http://localhost:4200/`.

## Build and validate

```shell
pnpm build
pnpm exec playwright install chromium
pnpm validate
```

The production build writes static files to `dist/web/browser`. Browser tests
verify application loading and offline PWA shell reloads. See the
[development guide](docs/development/README.md) for individual checks.

Use `pnpm fix` to run lint fixes followed by formatting.
`pnpm-workspace.yaml` holds pnpm installation settings only; there are no workspace members.

Hortinis is licensed under the GNU Affero General Public License version 3 only.
See [LICENSE](LICENSE).
