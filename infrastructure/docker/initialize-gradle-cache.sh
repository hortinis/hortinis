#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )) || [[ ! "$1" =~ ^[0-9]+$ || ! "$2" =~ ^[0-9]+$ ]]; then
  printf '%s\n' 'HORTINIS_UID and HORTINIS_GID must be numeric container IDs.' >&2
  exit 1
fi

# This container mounts only the cache and this script, never the checkout or database volume.
chown -hR -- "$1:$2" /home/gradle/.gradle
