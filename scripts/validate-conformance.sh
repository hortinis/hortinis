#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

pnpm --filter @hortinis/web test
./gradlew --dependency-verification=strict :services:sync:test
