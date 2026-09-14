#!/usr/bin/env bash
set -euo pipefail

export CI=true
export REDOCLY_TELEMETRY=off
export REDOCLY_SUPPRESS_UPDATE_NOTICE=true

set +e
redocly_output="$(redocly lint --config tooling/contracts/redocly.yaml tooling/contracts/fixtures/invalid-openapi.yaml 2>&1)"
redocly_status=$?
set -e

if [[ "$redocly_status" -eq 0 ]]; then
  printf '%s\n' "$redocly_output" >&2
  printf '%s\n' 'Redocly unexpectedly accepted the invalid OpenAPI fixture.' >&2
  exit 1
fi

if [[ "$redocly_output" != *'field `version` must be present'* ]]; then
  printf '%s\n' "$redocly_output" >&2
  printf '%s\n' 'Redocly failed for an unexpected reason while checking the invalid OpenAPI fixture.' >&2
  exit 1
fi

printf '%s\n' 'Redocly correctly rejected the invalid OpenAPI fixture.'
