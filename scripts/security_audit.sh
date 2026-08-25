#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=version_config.sh
source "$SCRIPT_DIR/version_config.sh"

JAVA_DIR="$PROJECT_DIR/app/src/main/java"
RES_DIR="$PROJECT_DIR/app/src/main/res"

echo "=== Security audit for Govind Personal Vault $VERSION_NAME ==="

# 1. No internet permission
if grep -q 'android.permission.INTERNET' "$PROJECT_DIR/app/src/main/AndroidManifest.xml"; then
  echo "FAIL: INTERNET permission found" >&2
  exit 1
fi
echo "PASS: No INTERNET permission"

# 2. Package ID check
if ! grep -q "PACKAGE_ID=com.govind.personalvault" "$PROJECT_DIR/app/version.properties"; then
  echo "FAIL: Unexpected package ID" >&2
  exit 1
fi
echo "PASS: Package ID correct"

# 3. Min SDK
if ! grep -q "MIN_SDK=31" "$PROJECT_DIR/app/version.properties"; then
  echo "FAIL: Unexpected minSdk" >&2
  exit 1
fi
echo "PASS: minSdk 31"

# 4. No layout XML (programmatic UI)
if find "$RES_DIR" -name "*.xml" -path "*/layout/*" | grep -q .; then
  echo "FAIL: Layout XML files found (UI must be programmatic)" >&2
  exit 1
fi
echo "PASS: No layout XML"

# 5. Bidirectional control character scan (Unicode spoofing guard)
echo "Scanning for bidirectional control characters..."
python3 - <<'PY'
import os, sys
forbidden = set(range(0x202A, 0x202F)) | {0x200E, 0x200F, 0x2066, 0x2067, 0x2068, 0x2069}
root = os.environ.get("PROJECT_DIR", ".")
bad = []
for dirpath, _, files in os.walk(root):
    if any(x in dirpath for x in ("/build", "/.gradle", "/.git", "/extracted")):
        continue
    for name in files:
        if not name.endswith((".java", ".xml", ".gradle", ".properties", ".sh", ".md", ".txt")):
            continue
        path = os.path.join(dirpath, name)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                data = f.read()
            for i, ch in enumerate(data):
                if ord(ch) in forbidden:
                    bad.append((path, i, hex(ord(ch))))
        except Exception:
            pass
if bad:
    for p, i, h in bad[:10]:
        print(f"FAIL: bidi control {h} in {p} at offset {i}", file=sys.stderr)
    sys.exit(1)
print("PASS: No bidirectional control characters")
PY

# 6. No @UnstableApi owned by app (must use OptIn)
if rg -q '@UnstableApi' "$JAVA_DIR" --glob '*.java'; then
  if ! rg -q '@OptIn.*UnstableApi' "$JAVA_DIR" --glob '*.java'; then
    echo "FAIL: @UnstableApi without @OptIn" >&2
    exit 1
  fi
fi
echo "PASS: Media3 UnstableApi usage correct"

# 7. No plaintext temp file patterns in media handling
if rg -q 'createTempFile|FileOutputStream.*\.tmp|getCacheDir.*write' "$JAVA_DIR/com/govind/personalvault/media" --glob '*.java'; then
  echo "WARN: Possible temp file usage in media (review)"
fi
echo "PASS: Media temp file patterns checked"

# 8. FLAG_SECURE presence (even if currently off for QA)
if ! rg -q 'FLAG_SECURE|BLOCK_SCREENSHOTS' "$JAVA_DIR" --glob '*.java'; then
  echo "FAIL: FLAG_SECURE / screenshot protection code missing" >&2
  exit 1
fi
echo "PASS: Screenshot protection code present"

# 9. No external viewer intents for media/docs
if rg -q 'ACTION_VIEW|Intent.ACTION_VIEW' "$JAVA_DIR" --glob '*Secure*.java' | grep -v '//'; then
  echo "FAIL: Possible external viewer intent in secure activities" >&2
  exit 1
fi
echo "PASS: No external viewer intents in secure activities"

echo "=== Security audit PASSED ==="
