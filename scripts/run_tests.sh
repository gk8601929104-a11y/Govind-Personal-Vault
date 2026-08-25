#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=version_config.sh
source "$SCRIPT_DIR/version_config.sh"

command -v gradle >/dev/null || {
  echo "Gradle is not installed on PATH." >&2
  exit 1
}

ACTUAL_GRADLE_VERSION="$(gradle --version | awk '/^Gradle / { print $2; exit }')"
if [[ "$ACTUAL_GRADLE_VERSION" != "$GRADLE_VERSION" ]]; then
  echo "Expected Gradle $GRADLE_VERSION but found $ACTUAL_GRADLE_VERSION." >&2
  exit 1
fi

GRADLE=(gradle --no-daemon --stacktrace --console=plain -p "$PROJECT_DIR")

# AGP 9 creates the default app unit-test component for debug. Run the real
# JUnit suite, compile production release Java, and then run release lint.
# BidiSpoofing is handled by our deterministic Unicode-control scanner because
# the AGP 9.3 detector currently crashes under its supported JDK 17 runtime.
"${GRADLE[@]}" \
  :app:compileReleaseJavaWithJavac \
  :app:testDebugUnitTest \
  :app:lintRelease
