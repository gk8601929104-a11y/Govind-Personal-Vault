#!/usr/bin/env bash
# Shared, validated build configuration for all scripts.
# This file is meant to be sourced, not executed directly.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${PROJECT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
VERSION_FILE="${VERSION_FILE:-$PROJECT_DIR/app/version.properties}"

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "Missing version file: $VERSION_FILE" >&2
  return 1 2>/dev/null || exit 1
fi

# shellcheck disable=SC1090
source "$VERSION_FILE"

: "${VERSION_CODE:?VERSION_CODE is required in app/version.properties}"
: "${VERSION_NAME:?VERSION_NAME is required in app/version.properties}"
: "${MIN_SDK:?MIN_SDK is required in app/version.properties}"
: "${TARGET_SDK:?TARGET_SDK is required in app/version.properties}"
: "${COMPILE_SDK:?COMPILE_SDK is required in app/version.properties}"
: "${BUILD_TOOLS_VERSION:?BUILD_TOOLS_VERSION is required in app/version.properties}"
APK_BASENAME="${APK_BASENAME:-Govind_Personal_Vault}"

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo "VERSION_CODE must be a positive integer." >&2; return 1 2>/dev/null || exit 1; }
[[ "$VERSION_NAME" =~ ^[0-9]+([.][0-9]+){1,3}([._-][A-Za-z0-9]+)*$ ]] || { echo "Invalid VERSION_NAME: $VERSION_NAME" >&2; return 1 2>/dev/null || exit 1; }
[[ "$MIN_SDK" =~ ^[0-9]+$ ]] || { echo "MIN_SDK must be numeric." >&2; return 1 2>/dev/null || exit 1; }
[[ "$TARGET_SDK" =~ ^[0-9]+$ ]] || { echo "TARGET_SDK must be numeric." >&2; return 1 2>/dev/null || exit 1; }
[[ "$COMPILE_SDK" =~ ^[0-9]+$ ]] || { echo "COMPILE_SDK must be numeric." >&2; return 1 2>/dev/null || exit 1; }
[[ "$BUILD_TOOLS_VERSION" =~ ^[0-9]+[.][0-9]+[.][0-9]+$ ]] || { echo "Invalid BUILD_TOOLS_VERSION." >&2; return 1 2>/dev/null || exit 1; }
[[ "$APK_BASENAME" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "Invalid APK_BASENAME." >&2; return 1 2>/dev/null || exit 1; }

if (( MIN_SDK > TARGET_SDK || TARGET_SDK > COMPILE_SDK )); then
  echo "SDK values must satisfy MIN_SDK <= TARGET_SDK <= COMPILE_SDK." >&2
  return 1 2>/dev/null || exit 1
fi

OUTPUT_APK_NAME="${APK_BASENAME}_v${VERSION_NAME}.apk"
OUTPUT_APK="$PROJECT_DIR/dist/$OUTPUT_APK_NAME"

export PROJECT_DIR VERSION_FILE VERSION_CODE VERSION_NAME MIN_SDK TARGET_SDK COMPILE_SDK
export BUILD_TOOLS_VERSION APK_BASENAME OUTPUT_APK_NAME OUTPUT_APK
