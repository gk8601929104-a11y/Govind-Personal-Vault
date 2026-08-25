#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=version_config.sh
source "$SCRIPT_DIR/version_config.sh"

: "${PACKAGE_ID:?PACKAGE_ID is required in app/version.properties}"

APK="$OUTPUT_APK"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BUILD_DIR="$PROJECT_DIR/app/build"
SIGNATURE_REPORT="$BUILD_DIR/apk-signature.txt"
ALIGN_REPORT="$BUILD_DIR/apk-zipalign.txt"
PERMISSIONS_REPORT="$BUILD_DIR/apk-permissions.txt"
MANIFEST_XML="$BUILD_DIR/apk-manifest.xml"
DEX_PACKAGES="$BUILD_DIR/apk-dex-packages.txt"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  echo "PASS: $*"
}

trim_output() {
  tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

expect_equal() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail "$label mismatch; expected '$expected', found '${actual:-<empty>}'."
  fi
}

[[ -n "$SDK_DIR" ]] || fail "ANDROID_SDK_ROOT or ANDROID_HOME is not set."
[[ -f "$APK" ]] || fail "APK was not found: $APK"

BUILD_TOOLS_DIR="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKANALYZER="$SDK_DIR/cmdline-tools/latest/bin/apkanalyzer"
if [[ ! -x "$APKANALYZER" ]]; then
  APKANALYZER="$(command -v apkanalyzer || true)"
fi

for required in "$APKSIGNER" "$ZIPALIGN"; do
  [[ -x "$required" ]] || fail "Required Android build tool is missing or not executable: $required"
done
[[ -n "$APKANALYZER" && -x "$APKANALYZER" ]] || \
  fail "apkanalyzer is missing from Android SDK Command-Line Tools."

mkdir -p "$BUILD_DIR"

# Verify the APK against the minimum Android version declared by the project.
# A v3-only result is valid for this app's minSdk 31; every signature scheme
# does not need to report true simultaneously.
if ! "$APKSIGNER" verify \
    --verbose \
    --print-certs \
    --min-sdk-version "$MIN_SDK" \
    "$APK" | tee "$SIGNATURE_REPORT"; then
  fail "APK signature verification failed."
fi
if ! grep -Eq '^Number of signers: 1$' "$SIGNATURE_REPORT"; then
  fail "APK must have exactly one signer."
fi
if ! grep -Eq '^Verified using v(2|3|3[.]1) scheme .*: true$' "$SIGNATURE_REPORT"; then
  fail "APK is not verified by a modern Android signature scheme (v2 or newer)."
fi
pass "APK signature and signer count verified"

if ! "$ZIPALIGN" -c -v 4 "$APK" >"$ALIGN_REPORT" 2>&1; then
  cat "$ALIGN_REPORT" >&2
  fail "APK zip alignment verification failed."
fi
pass "APK zip alignment verified"

if ! unzip -t "$APK" >/dev/null; then
  fail "APK ZIP structure is corrupt."
fi
pass "APK ZIP integrity verified"

# Use apkanalyzer's dedicated manifest commands instead of parsing the
# human-readable 'aapt2 dump badging' text. The dedicated commands return the
# exact final values from the merged binary manifest and are stable across
# Build Tools output-format changes.
actual_package="$("$APKANALYZER" manifest application-id "$APK" | trim_output)"
actual_version_code="$("$APKANALYZER" manifest version-code "$APK" | trim_output)"
actual_version_name="$("$APKANALYZER" manifest version-name "$APK" | trim_output)"
actual_min_sdk="$("$APKANALYZER" manifest min-sdk "$APK" | trim_output)"
actual_target_sdk="$("$APKANALYZER" manifest target-sdk "$APK" | trim_output)"
actual_debuggable="$("$APKANALYZER" manifest debuggable "$APK" | trim_output)"

expect_equal "applicationId" "$PACKAGE_ID" "$actual_package"
expect_equal "versionCode" "$VERSION_CODE" "$actual_version_code"
expect_equal "versionName" "$VERSION_NAME" "$actual_version_name"
expect_equal "minSdk" "$MIN_SDK" "$actual_min_sdk"
expect_equal "targetSdk" "$TARGET_SDK" "$actual_target_sdk"
expect_equal "debuggable flag" "false" "${actual_debuggable,,}"

"$APKANALYZER" manifest permissions "$APK" | tr -d '\r' > "$PERMISSIONS_REPORT"
if ! grep -Fxq 'android.permission.USE_BIOMETRIC' "$PERMISSIONS_REPORT"; then
  fail "USE_BIOMETRIC permission is missing from the APK."
fi
if grep -Fxq 'android.permission.INTERNET' "$PERMISSIONS_REPORT"; then
  fail "Unexpected INTERNET permission."
fi
if grep -Eq '^android[.]permission[.](READ_MEDIA_[A-Z_]+|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE)$' "$PERMISSIONS_REPORT"; then
  fail "Unexpected broad media/storage permission."
fi

"$APKANALYZER" manifest print "$APK" > "$MANIFEST_XML"
python3 - "$MANIFEST_XML" "$PACKAGE_ID" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

manifest_path = Path(sys.argv[1])
package_id = sys.argv[2]
android = '{http://schemas.android.com/apk/res/android}'

try:
    root = ET.parse(manifest_path).getroot()
except Exception as exc:
    print(f"FAIL: Could not parse APK manifest XML: {exc}", file=sys.stderr)
    raise SystemExit(1)

application = root.find('application')
if application is None:
    print('FAIL: APK manifest has no application element.', file=sys.stderr)
    raise SystemExit(1)

def fqcn(name: str) -> str:
    if name.startswith('.'):
        return package_id + name
    if '.' not in name:
        return package_id + '.' + name
    return name

launcher_found = False
for element_name in ('activity', 'activity-alias'):
    for activity in application.findall(element_name):
        raw_name = activity.get(android + 'name', '')
        if fqcn(raw_name) != package_id + '.MainActivity':
            continue
        for intent_filter in activity.findall('intent-filter'):
            actions = {
                action.get(android + 'name', '')
                for action in intent_filter.findall('action')
            }
            categories = {
                category.get(android + 'name', '')
                for category in intent_filter.findall('category')
            }
            if ('android.intent.action.MAIN' in actions and
                    'android.intent.category.LAUNCHER' in categories):
                launcher_found = True
                break

if not launcher_found:
    print('FAIL: MainActivity is not the MAIN/LAUNCHER activity.', file=sys.stderr)
    raise SystemExit(1)
PY
pass "APK package, SDK, version, permission, debuggable, and launcher metadata verified"

# Verify required vault/media/document classes through APK Analyzer rather than dexdump -f,
# which only reports DEX file headers and is not a class-enumeration command.
"$APKANALYZER" dex packages --defined-only "$APK" > "$DEX_PACKAGES"
for class_name in \
  'com.govind.personalvault.security.SecurityManager' \
  'com.govind.personalvault.data.VaultDb' \
  'com.govind.personalvault.MediaVaultActivity' \
  'com.govind.personalvault.DocumentsVaultActivity' \
  'com.govind.personalvault.SecureDocumentActivity' \
  'com.govind.personalvault.SecureMediaPlayerActivity' \
  'com.govind.personalvault.media.EncryptedCipherDataSource'; do
  if ! grep -Fq "$class_name" "$DEX_PACKAGES"; then
    fail "Required class is missing from APK: $class_name"
  fi
done
pass "Required vault, media, and document classes verified in DEX"

sha256sum "$APK"
printf 'PASS: signed Vault APK verified: %s (code %s)\n' \
  "$OUTPUT_APK_NAME" "$VERSION_CODE"
