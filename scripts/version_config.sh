#!/usr/bin/env bash
# Sourced by other scripts. Loads version.properties into the environment.

if [[ -z "${PROJECT_DIR:-}" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
fi

CONFIG="$PROJECT_DIR/app/version.properties"
if [[ ! -f "$CONFIG" ]]; then
  echo "version.properties not found at $CONFIG" >&2
  exit 1
fi

# shellcheck disable=SC1090
source <(grep -E '^[A-Z_]+=.*' "$CONFIG" | sed 's/^/export /')

: "${VERSION_CODE:?}"
: "${VERSION_NAME:?}"
: "${APK_BASENAME:?}"
: "${PACKAGE_ID:?}"
