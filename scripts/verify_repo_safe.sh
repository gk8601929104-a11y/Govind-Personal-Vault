#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

if find . -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' \) -print -quit | grep -q .; then
  echo "ERROR: signing material exists in the repository." >&2
  exit 1
fi


if rg -n --hidden -g '!build/**' -g '!.gradle/**' -g '!scripts/verify_repo_safe.sh' \
  'SIGNING_KEYSTORE_BASE64=[A-Za-z0-9+/]{80,}|BEGIN (PRIVATE KEY|ENCRYPTED PRIVATE KEY)' . >/dev/null; then
  echo "ERROR: private signing material appears in the repository." >&2
  exit 1
fi

PASSWORD_ASSIGNMENT_PATTERN="(SIGNING_(STORE|KEY)_PASSWORD|storePassword|keyPassword)[[:space:]]*[:=][[:space:]]*[\"''][^$\"'']{5,}[\"'']"
if rg -n --hidden -g '!build/**' -g '!.gradle/**' -g '!scripts/verify_repo_safe.sh' \
  "$PASSWORD_ASSIGNMENT_PATTERN" . >/dev/null; then
  echo "ERROR: a hard-coded signing password assignment appears in the repository." >&2
  exit 1
fi

echo "Repository safety check passed: no signing key or hard-coded signing password is committed."
