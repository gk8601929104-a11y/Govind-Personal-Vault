#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=version_config.sh
source "$SCRIPT_DIR/version_config.sh"

DIST_DIR="$PROJECT_DIR/dist"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

: "${SIGNING_KEYSTORE:?SIGNING_KEYSTORE is required}"
: "${SIGNING_STORE_PASSWORD:?SIGNING_STORE_PASSWORD is required}"
: "${SIGNING_KEY_ALIAS:?SIGNING_KEY_ALIAS is required}"
: "${SIGNING_KEY_PASSWORD:?SIGNING_KEY_PASSWORD is required}"
command -v gradle >/dev/null || { echo "Gradle is not installed on PATH." >&2; exit 1; }

ACTUAL_GRADLE_VERSION="$(gradle --version | awk '/^Gradle / { print $2; exit }')"
if [[ "$ACTUAL_GRADLE_VERSION" != "$GRADLE_VERSION" ]]; then
  echo "Expected Gradle $GRADLE_VERSION but found $ACTUAL_GRADLE_VERSION." >&2
  exit 1
fi
[[ -f "$SIGNING_KEYSTORE" ]] || { echo "Signing keystore file was not found." >&2; exit 1; }
[[ -n "$SDK_DIR" ]] || { echo "Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2; exit 1; }

rm -rf "$PROJECT_DIR/app/build" "$DIST_DIR"
mkdir -p "$DIST_DIR"

gradle --no-daemon --stacktrace \
  -p "$PROJECT_DIR" \
  :app:assembleRelease

BUILT_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
[[ -f "$BUILT_APK" ]] || { echo "Gradle did not produce the expected release APK." >&2; exit 1; }

APKSIGNER="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/apksigner"
[[ -x "$APKSIGNER" ]] || { echo "apksigner is missing: $APKSIGNER" >&2; exit 1; }
"$APKSIGNER" verify --verbose --print-certs "$BUILT_APK"
cp "$BUILT_APK" "$OUTPUT_APK"
if [[ -f "$BUILT_APK.idsig" ]]; then cp "$BUILT_APK.idsig" "$OUTPUT_APK.idsig"; fi
"$APKSIGNER" verify --verbose "$OUTPUT_APK"
printf 'Built %s (versionCode=%s, versionName=%s)\n' "$OUTPUT_APK" "$VERSION_CODE" "$VERSION_NAME"
