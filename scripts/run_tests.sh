#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

echo "Running unit tests..."
gradle testDebugUnitTest --no-daemon --stacktrace || {
  echo "Unit tests failed" >&2
  exit 1
}

echo "Running release lint..."
gradle lintRelease --no-daemon --stacktrace || {
  echo "Lint failed" >&2
  exit 1
}

echo "Tests and lint passed."
