#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=version_config.sh
source "$SCRIPT_DIR/version_config.sh"

MANIFEST="$PROJECT_DIR/app/src/main/AndroidManifest.xml"
JAVA_DIR="$PROJECT_DIR/app/src/main/java"
GRADLE_FILE="$PROJECT_DIR/app/build.gradle"

rg -q "applicationId packageId" "$GRADLE_FILE"
rg -q "androidx.media3:media3-exoplayer" "$GRADLE_FILE"
rg -q "androidx.media3:media3-ui" "$GRADLE_FILE"
rg -q "androidx.activity:activity" "$GRADLE_FILE"
rg -q 'minSdk minSdkValue' "$GRADLE_FILE"
rg -q 'targetSdk targetSdkValue' "$GRADLE_FILE"
rg -q 'storePassword = signingStorePasswordValue' "$GRADLE_FILE"
rg -q "signingConfig = signingConfigs.getByName\('release'\)" "$GRADLE_FILE"
if rg -n '^[[:space:]]*(storePassword|keyAlias|keyPassword)[[:space:]]+(storePassword|keyAliasValue|keyPasswordValue)[[:space:]]*$' "$GRADLE_FILE" >/dev/null; then
  echo 'FAIL: ambiguous Groovy signing DSL method syntax detected; use explicit property assignment.' >&2
  exit 1
fi
if grep -q '<uses-sdk' "$MANIFEST"; then
  echo "FAIL: SDK versions must be configured in Gradle, not AndroidManifest.xml" >&2
  exit 1
fi
if grep -Eq '<manifest[^>]+package=' "$MANIFEST"; then
  echo "FAIL: source manifest package must come from the Gradle namespace/applicationId" >&2
  exit 1
fi
grep -q 'android:allowBackup="false"' "$MANIFEST"
grep -q 'android:usesCleartextTraffic="false"' "$MANIFEST"
grep -q 'android:dataExtractionRules="@xml/data_extraction_rules"' "$MANIFEST"
grep -q 'android.permission.USE_BIOMETRIC' "$MANIFEST"
if grep -q 'android.permission.INTERNET' "$MANIFEST"; then echo "FAIL: Internet permission" >&2; exit 1; fi
if grep -q 'android.permission.WRITE_SETTINGS' "$MANIFEST"; then echo "FAIL: global settings write permission is not allowed" >&2; exit 1; fi
if rg -n 'READ_MEDIA_|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE' "$MANIFEST" >/dev/null; then
  echo "FAIL: broad storage permission is not allowed" >&2; exit 1
fi

rg -q 'FLAG_SECURE' "$JAVA_DIR/com/govind/personalvault/BaseActivity.java"
rg -q 'extends ComponentActivity' "$JAVA_DIR/com/govind/personalvault/BaseActivity.java"
rg -q 'AES/GCM/NoPadding' "$JAVA_DIR/com/govind/personalvault/security/CryptoBox.java"
rg -q 'PBKDF2WithHmacSHA256' "$JAVA_DIR/com/govind/personalvault/security/SecurityManager.java"
rg -q 'AUTH_BIOMETRIC_STRONG' "$JAVA_DIR/com/govind/personalvault/security/SecurityManager.java"
rg -q 'BiometricPrompt.CryptoObject' "$JAVA_DIR/com/govind/personalvault/LockActivity.java"
rg -q 'newSingleThreadExecutor' "$JAVA_DIR/com/govind/personalvault/data/VaultDb.java"
rg -q 'CREATE TABLE IF NOT EXISTS media_items' "$JAVA_DIR/com/govind/personalvault/data/VaultDb.java"
rg -q 'original_name_blob TEXT NOT NULL' "$JAVA_DIR/com/govind/personalvault/data/VaultDb.java"
rg -q 'thumbnail_blob TEXT NOT NULL' "$JAVA_DIR/com/govind/personalvault/data/VaultDb.java"
rg -q 'CipherOutputStream' "$JAVA_DIR/com/govind/personalvault/media/MediaCryptoWriter.java"
rg -q 'extends BaseDataSource' "$JAVA_DIR/com/govind/personalvault/media/EncryptedCipherDataSource.java"
rg -q 'AEADBadTagException' "$JAVA_DIR/com/govind/personalvault/media/EncryptedCipherDataSource.java"
rg -q 'getFilesDir\(\)' "$JAVA_DIR/com/govind/personalvault/media/MediaFileFormat.java"
rg -q 'MediaStore.MediaColumns.IS_PENDING' "$JAVA_DIR/com/govind/personalvault/media/MediaExporter.java"
rg -q 'OpenMultipleDocuments' "$JAVA_DIR/com/govind/personalvault/MediaVaultActivity.java"
rg -q 'OpenMultipleDocuments' "$JAVA_DIR/com/govind/personalvault/DocumentsVaultActivity.java"
rg -q 'importDocumentsAsync' "$JAVA_DIR/com/govind/personalvault/DocumentsVaultActivity.java"
rg -q 'MediaStore.Downloads' "$JAVA_DIR/com/govind/personalvault/media/MediaExporter.java"
rg -q 'ExoPlayer' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'PlayerView' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'RESIZE_MODE_FIT' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'RESIZE_MODE_ZOOM' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'RESIZE_MODE_FILL' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'WindowInsets.Type.systemBars' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'CONTROLS_HIDE_DELAY_MS' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'AudioManager.STREAM_MUSIC' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'attributes.screenBrightness = target' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'MotionEvent.ACTION_MOVE' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'installPlayerInsetsHandling' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'shouldUseImmersiveVideo\(\) \? 1 : 0' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"

# Version 1.2.2 in-app viewers: pinch-zoom images and open documents without FileProvider.
rg -q 'ZoomPanImageView' "$JAVA_DIR/com/govind/personalvault/SecureImageViewerActivity.java"
rg -q 'ScaleGestureDetector' "$JAVA_DIR/com/govind/personalvault/ui/ZoomPanImageView.java"
rg -q 'PdfRenderer' "$JAVA_DIR/com/govind/personalvault/media/InAppDocumentReader.java"
rg -q 'memfd_create' "$JAVA_DIR/com/govind/personalvault/media/InAppDocumentReader.java"
rg -q 'InAppDocumentReader.open' "$JAVA_DIR/com/govind/personalvault/SecureDocumentActivity.java"
rg -q 'ScaleGestureDetector' "$JAVA_DIR/com/govind/personalvault/SecureDocumentActivity.java"

# Version 1.2 UI/UX and encrypted-document feature contract.
for required in \
  "$JAVA_DIR/com/govind/personalvault/DocumentsVaultActivity.java" \
  "$JAVA_DIR/com/govind/personalvault/SecureDocumentActivity.java"; do
  [[ -f "$required" ]] || { echo "FAIL: required document-vault class missing: $required" >&2; exit 1; }
done
grep -q 'android:name=".DocumentsVaultActivity"' "$MANIFEST"
grep -q 'android:name=".SecureDocumentActivity"' "$MANIFEST"
rg -q 'listMediaAsync\(query, "document"' "$JAVA_DIR/com/govind/personalvault/DocumentsVaultActivity.java"
rg -q 'resolveDocument' "$JAVA_DIR/com/govind/personalvault/media/MediaMetadataResolver.java"
rg -q 'isDocument\(\)' "$JAVA_DIR/com/govind/personalvault/model/MediaItemRecord.java"
rg -q 'counts.documents' "$JAVA_DIR/com/govind/personalvault/VaultActivity.java"

if rg -n 'ACTION_VIEW|Intent\.createChooser|FileProvider' "$JAVA_DIR/com/govind/personalvault" >/dev/null; then
  echo "FAIL: decrypted media must not be handed to external apps" >&2; exit 1
fi
if rg -n 'onSaveInstanceState' "$JAVA_DIR" >/dev/null; then echo "FAIL: sensitive state must not use Bundles" >&2; exit 1; fi
if rg -n 'com\.govind\.smartcalc|govind_smart_calculator' "$PROJECT_DIR/app" >/dev/null; then echo "FAIL: calculator identity leaked into vault" >&2; exit 1; fi

# All direct SQLite calls stay inside VaultDb.
db_calls="$(rg -l 'getWritableDatabase|getReadableDatabase|rawQuery\(' "$JAVA_DIR" || true)"
expected="$JAVA_DIR/com/govind/personalvault/data/VaultDb.java"
if [[ "$db_calls" != "$expected" ]]; then echo "FAIL: database access escaped VaultDb" >&2; printf '%s\n' "$db_calls" >&2; exit 1; fi

permission_count="$(rg -c '<uses-permission ' "$MANIFEST")"
if [[ "$permission_count" != "1" ]]; then echo "FAIL: unexpected manifest permission count" >&2; exit 1; fi

printf 'PASS: Vault source security/UI contract for SDK %s/%s, version %s (%s)\n' "$MIN_SDK" "$TARGET_SDK" "$VERSION_NAME" "$VERSION_CODE"

# App-owned classes that consume Media3 unstable APIs must explicitly opt in.
# Marking our own class with @UnstableApi would propagate the opt-in requirement
# to every caller and is not the intended application-side usage.
if rg -n '^@UnstableApi$' "$JAVA_DIR" >/dev/null 2>&1; then
  echo 'FAIL: app-owned classes must use androidx.annotation.OptIn, not @UnstableApi.' >&2
  exit 1
fi
rg -q 'import androidx.annotation.OptIn;' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q '@OptIn\(markerClass = UnstableApi.class\)' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q 'import androidx.annotation.OptIn;' "$JAVA_DIR/com/govind/personalvault/media/EncryptedCipherDataSource.java"
rg -q '@OptIn\(markerClass = UnstableApi.class\)' "$JAVA_DIR/com/govind/personalvault/media/EncryptedCipherDataSource.java"

# Media3 API compatibility regression guard: use the public accessor rather
# than relying on the implementation field visibility of Tracks.Group.
if rg -n 'group\.mediaTrackGroup' app/src/main/java >/dev/null 2>&1; then
  echo 'FAIL: direct Tracks.Group.mediaTrackGroup access found; use getMediaTrackGroup().' >&2
  exit 1
fi

# AGP 9.3's BidiSpoofing detector currently crashes under its supported JDK 17
# runtime. Disable only that broken detector and replace it with a deterministic
# repository-wide scan for Unicode bidirectional control characters.
rg -q "disable 'TypographyFractions', 'TypographyQuotes', 'BidiSpoofing'" "$GRADLE_FILE"
python3 - "$PROJECT_DIR" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
allowed_suffixes = {
    '.java', '.xml', '.gradle', '.properties', '.sh', '.md', '.txt', '.yml', '.yaml'
}
controls = {
    0x061C, 0x200E, 0x200F,
    *range(0x202A, 0x202F),
    *range(0x2066, 0x206A),
}
found = []
excluded_dirs = {'.git', '.gradle', '.idea', 'build', 'dist'}
for path in sorted(root.rglob('*')):
    if not path.is_file() or path.suffix.lower() not in allowed_suffixes:
        continue
    relative = path.relative_to(root)
    # Scan authored repository files only. Android/Gradle generate legitimate
    # directionality marks in app/build/intermediates (for example en-rXC
    # pseudo-locale resources); generated output is not source and must not
    # produce a false security failure.
    if any(part in excluded_dirs for part in relative.parts):
        continue
    try:
        text = path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        found.append(f"{path.relative_to(root)}: invalid UTF-8")
        continue
    for line_no, line in enumerate(text.splitlines(), 1):
        bad = [f"U+{ord(ch):04X}" for ch in line if ord(ch) in controls]
        if bad:
            found.append(
                f"{path.relative_to(root)}:{line_no}: " + ','.join(sorted(set(bad)))
            )
if found:
    print('FAIL: Unicode bidirectional control characters detected:', file=sys.stderr)
    print('\n'.join(found), file=sys.stderr)
    raise SystemExit(1)
print('PASS: deterministic Unicode bidirectional-control scan')
PY

# Regression guards for security fixes that are easy to accidentally remove.
rg -q 'VaultSession\.requireEpoch\(\)' "$JAVA_DIR/com/govind/personalvault/media/EncryptedMediaInputStream.java"
rg -q 'VaultSession\.isValidEpoch\(sessionEpoch\)' "$JAVA_DIR/com/govind/personalvault/media/EncryptedMediaInputStream.java"
rg -q 'cipher\.doFinal\(' "$JAVA_DIR/com/govind/personalvault/media/EncryptedMediaInputStream.java"
rg -q 'VaultSession\.requireEpoch\(\)' "$JAVA_DIR/com/govind/personalvault/media/MediaCryptoWriter.java"
rg -q 'throwIfInterrupted\(\)' "$JAVA_DIR/com/govind/personalvault/media/MediaExporter.java"
rg -q 'clearDroppedResult\(delivered\)' "$JAVA_DIR/com/govind/personalvault/data/VaultDb.java"
rg -q 'Context\.RECEIVER_NOT_EXPORTED' "$JAVA_DIR/com/govind/personalvault/SecureMediaPlayerActivity.java"
rg -q ':app:testDebugUnitTest' "$PROJECT_DIR/scripts/run_tests.sh"
rg -q 'gradle/actions/setup-gradle@v6' "$PROJECT_DIR/.github/workflows/build-apk.yml"
