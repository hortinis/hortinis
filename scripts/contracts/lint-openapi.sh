#!/usr/bin/env bash
set -euo pipefail

export CI=true
export REDOCLY_TELEMETRY=off
export REDOCLY_SUPPRESS_UPDATE_NOTICE=true

redocly lint --config tooling/contracts/redocly.yaml contracts/openapi/openapi.yaml
