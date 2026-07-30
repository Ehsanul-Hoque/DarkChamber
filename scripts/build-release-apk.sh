#!/usr/bin/env bash
#
# build-release-apk.sh — build a signed release APK at a given version.
#
# The version is passed in (computed from tags by compute-version.sh) rather than
# read from a file, since nothing stores the version on main. Gradle receives it as
# project properties; the Android module reads them for versionName / versionCode.
#
# Env (required):
#   VERSION_NAME    e.g. 0.2.0
#   VERSION_CODE    e.g. 2000
#   KEYSTORE_B64    base64-encoded release keystore  (GitHub secret)
#   KEYSTORE_PASS   keystore password                (GitHub secret)
# Env (optional):
#   KEY_ALIAS       default: darkchamber
#   KEY_PASS        default: $KEYSTORE_PASS
#   MODULE          default: :androidApp
#
set -euo pipefail

: "${VERSION_NAME:?VERSION_NAME is required}"
: "${VERSION_CODE:?VERSION_CODE is required}"
: "${KEYSTORE_B64:?KEYSTORE_B64 is required}"
: "${KEYSTORE_PASS:?KEYSTORE_PASS is required}"

KEY_ALIAS="${KEY_ALIAS:-darkchamber}"
KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"
MODULE="${MODULE:-:androidApp}"

OUT_DIR="build/outputs"
KEYSTORE_PATH="$(mktemp -d)/release.keystore"

# Never leave the decoded keystore on disk, even if the build fails.
cleanup() { rm -rf "$(dirname "$KEYSTORE_PATH")"; }
trap cleanup EXIT

echo "$KEYSTORE_B64" | base64 -d > "$KEYSTORE_PATH"
echo "Building DarkChamber ${VERSION_NAME} (versionCode ${VERSION_CODE})"

./gradlew "${MODULE}:assembleRelease" \
  --no-daemon \
  -PversionName="$VERSION_NAME" \
  -PversionCode="$VERSION_CODE" \
  -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
  -Pandroid.injected.signing.store.password="$KEYSTORE_PASS" \
  -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
  -Pandroid.injected.signing.key.password="$KEY_PASS"

# Collect the APK under a predictable, versioned name for the release step.
mkdir -p "$OUT_DIR"
SRC_APK="$(find . -path '*/androidApp/build/outputs/apk/release/*.apk' -type f | head -n1)"
if [ -z "$SRC_APK" ]; then
  echo "error: no release APK found after build" >&2
  exit 1
fi

DEST_APK="${OUT_DIR}/darkchamber-${VERSION_NAME}.apk"
cp "$SRC_APK" "$DEST_APK"
echo "APK ready: ${DEST_APK}"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "apk_path=${DEST_APK}" >> "$GITHUB_OUTPUT"
fi
