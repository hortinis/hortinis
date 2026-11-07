# Development

The repository contains one Angular PWA at the repository root. Use Node.js 24.18.0 and
pnpm 11.26.0, pinned by `.nvmrc` and the root package manifest.

```shell
pnpm install --frozen-lockfile
pnpm start
```

The pnpm configuration allows install scripts for `@parcel/watcher`, `esbuild`, `lmdb`, and
`msgpackr-extract`, which support the frontend build and file watching.

## Validation

Install Playwright Chromium once:

```shell
pnpm exec playwright install chromium
```

Run the full frontend and repository checks with `pnpm validate`.
Individual checks are available from the repository root:

```shell
pnpm format:check
pnpm lint
pnpm architecture:check
pnpm test:architecture
pnpm typecheck
pnpm test
pnpm test:e2e
pnpm build:development
pnpm build
git diff --check
git ls-files -ci --exclude-standard
git check-attr text eol -- .gitattributes README.md
git ls-files --eol
```

Browser tests serve the production build and check application loading and offline
shell caching in Chromium. Unit tests cover local persistence and application behavior.
Architecture checks cover component boundaries, pure rules, and import cycles.
Review changes for English language consistency and valid relative documentation links.

## Local data and build output

Gardens and setup state are stored locally in IndexedDB through Dexie. The database
upgrade removes obsolete synchronization stores while preserving local gardens and
configuration. There is no backend or frontend/backend synchronization.

`pnpm build` writes static deployment files to `dist/web/browser`. Serve
that directory over HTTPS, with navigation fallback to `index.html`, for PWA use.
See [the project README](../../README.md).
