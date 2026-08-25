#!/usr/bin/env bash
set -euo pipefail

echo "Verifying repository safety..."

if grep -rE '(password|secret|api[_-]?key|keystore).*[=:].*["'\''][^"'\'']{8,}' --include='*.java' --include='*.xml' --include='*.gradle' --include='*.properties' --include='*.sh' . 2>/dev/null | grep -v version.properties | grep -v '#'; then
  echo "Possible hardcoded secret found" >&2
  exit 1
fi

echo "Repository safety checks passed."
