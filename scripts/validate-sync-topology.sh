#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

# Always allocate a fresh project: cleanup must never remove a developer's database.
export HORTINIS_TOPOLOGY_PROJECT_NAME="hortinis-sync-test-$$-$(node -e 'process.stdout.write(require("node:crypto").randomBytes(8).toString("hex"))')"
export HORTINIS_POSTGRES_PASSWORD="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')"
compose=(docker compose --project-name "$HORTINIS_TOPOLOGY_PROJECT_NAME"
  --file infrastructure/docker/compose.yaml --file infrastructure/docker/compose.sync-test.yaml)

cleanup() {
  local result=$?
  trap - EXIT
  set +e
  "${compose[@]}" down --volumes --remove-orphans
  local cleanup_result=$?
  if (( result == 0 )); then
    result=$cleanup_result
  fi
  exit "$result"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"${compose[@]}" up --wait
pnpm --filter @hortinis/web test:e2e:integration
