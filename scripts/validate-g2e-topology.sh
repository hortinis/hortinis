#!/usr/bin/env bash
set -euo pipefail

: "${HORTINIS_POSTGRES_PASSWORD:?Set HORTINIS_POSTGRES_PASSWORD for the G2e topology}"

compose=(docker compose --project-name hortinis-g2e --file infrastructure/docker/compose.yaml)

cleanup() {
  "${compose[@]}" down --remove-orphans
}

trap cleanup EXIT
"${compose[@]}" up --wait
pnpm --filter @hortinis/web test:e2e:integration
