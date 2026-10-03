#!/usr/bin/env bash
set -euo pipefail

: "${HORTINIS_POSTGRES_PASSWORD:?Set HORTINIS_POSTGRES_PASSWORD for the G2e topology}"

export HORTINIS_G2E_PROJECT_NAME="${HORTINIS_G2E_PROJECT_NAME:-hortinis-g2e}"
compose=(docker compose --project-name "$HORTINIS_G2E_PROJECT_NAME"
  --file infrastructure/docker/compose.yaml --file infrastructure/docker/compose.g2e.yaml)

cleanup() {
  "${compose[@]}" down --remove-orphans
}

trap cleanup EXIT
"${compose[@]}" up --wait
pnpm --filter @hortinis/web test:e2e:integration
