#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

run_check() {
  printf '\n%s\n' "> $*"
  "$@"
}

printf '%s\n' 'Validating Hortinis web application'

run_check node --version
run_check pnpm --version
run_check pnpm list --depth -1

printf '\n%s\n' 'Frontend validation'
run_check pnpm format:check
run_check pnpm lint
run_check pnpm architecture:check
run_check pnpm test:architecture
run_check pnpm typecheck
run_check pnpm test
run_check pnpm test:e2e
run_check pnpm build:development
run_check pnpm build

printf '\n%s\n' 'Repository validation'
run_check git diff --check
run_check git ls-files -ci --exclude-standard
run_check git check-attr text eol -- .gitattributes README.md
run_check git ls-files --eol

printf '\n%s\n' 'All validation checks passed.'
