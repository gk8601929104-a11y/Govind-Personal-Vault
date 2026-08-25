#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/version_config.sh"

APK=$(find "$PROJECT_DIR/dist" -name "${APK_BASENAME}_v${VERSION_NAME}_${VERSION_CODE}.apk" | head -1)
if [[ -z "$APK" ]]; then
  APK=$(find "$PROJECT_DIR/dist" -name "*.apk" | head -1)
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "No APK found in dist/" >&2
  exit 1
fi

echo "Verifying APK: $APK"

# Basic size check
SIZE=$(stat -c%s "$APK")
if [[ $SIZE -lt 100000 ]]; then
  echo "APK too small: $SIZE bytes" >&2
  exit 1
fi
echo "PASS: APK size $SIZE bytes"

# Check for v2/v3 signature using apksigner if available
if command -v apksigner >/dev/null 2>&1; then
  apksigner verify --verbose "$APK" || {
    echo "apksigner verify failed" >&2
    exit 1
  }
  echo "PASS: apksigner verify"
else
  echo "apksigner not available, skipping signature check"
fi

echo "=== APK verification PASSED ==="
