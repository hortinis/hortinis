#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

run_check() {
  printf '\n%s\n' "> $*"
  "$@"
}

printf '%s\n' 'Validating Hortinis workspace'

run_check node --version
run_check pnpm --version
run_check pnpm list --recursive --depth -1

printf '\n%s\n' 'Frontend validation'
run_check pnpm --filter @hortinis/web format:check
run_check pnpm --filter @hortinis/web lint
run_check pnpm --filter @hortinis/web architecture:check
run_check pnpm --filter @hortinis/web test:architecture
run_check pnpm --filter @hortinis/web typecheck
run_check pnpm --filter @hortinis/web test
run_check pnpm --filter @hortinis/web test:e2e
run_check pnpm --filter @hortinis/web build:development
run_check pnpm --filter @hortinis/web build

printf '\n%s\n' 'Backend validation'
run_check java --version
run_check sha256sum --check gradle/wrapper/gradle-wrapper.jar.sha256
run_check ./gradlew --version
run_check ./gradlew projects
run_check ./gradlew build

printf '\n%s\n' 'Repository validation'
run_check git diff --check
run_check git ls-files -ci --exclude-standard
run_check git check-attr text eol -- .gitattributes README.md gradlew gradlew.bat
run_check git ls-files --eol

printf '\n%s\n' 'All validation checks passed.'
