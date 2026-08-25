#!/usr/bin/env bash
set -euo pipefail

# Build signed release APK using the version.properties and signing env vars.
# Output goes to dist/

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

source "$SCRIPT_DIR/version_config.sh"

mkdir -p dist

echo "Building release APK for $VERSION_NAME ($VERSION_CODE)..."

gradle assembleRelease --no-daemon --stacktrace

APK_SRC=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
if [[ -z "$APK_SRC" ]]; then
  echo "No release APK found" >&2
  exit 1
fi

APK_DEST="dist/${APK_BASENAME}_v${VERSION_NAME}_${VERSION_CODE}.apk"
cp "$APK_SRC" "$APK_DEST"
echo "APK written to $APK_DEST"

# Also copy idsig if present
IDSIG_SRC="${APK_SRC}.idsig"
if [[ -f "$IDSIG_SRC" ]]; then
  cp "$IDSIG_SRC" "${APK_DEST}.idsig"
fi
