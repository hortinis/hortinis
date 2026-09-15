#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repository_root/infrastructure/docker/compose.yaml"

cd "$repository_root"
docker compose --file "$compose_file" config --quiet
