#!/usr/bin/env bash
#
# publish-test-build.sh — build a test APK from the current branch and publish it
# as a GitHub PRE-RELEASE for testers. Replaces third-party distribution services:
# testers install straight from the Releases page — no accounts, no Play Services.
#
# The version is a throwaway (0.0.0-<branch>-<shortsha>, versionCode 1) so a test
# build can never be confused with, or upgrade-block, a real release.
#
# Env (required):
#   KEYSTORE_B64   base64-encoded release keystore  (GitHub secret)
#   KEYSTORE_PASS  keystore password                (GitHub secret)
#   GITHUB_TOKEN   provided by Actions; needs `contents: write`
# Env (optional):
#   KEY_ALIAS      default: darkchamber
#
set -euo pipefail

: "${KEYSTORE_B64:?KEYSTORE_B64 is required}"
: "${KEYSTORE_PASS:?KEYSTORE_PASS is required}"

BRANCH="$(git rev-parse --abbrev-ref HEAD | tr -c 'a-zA-Z0-9._-' '-' | sed 's/-*$//')"
SHORT_SHA="$(git rev-parse --short HEAD)"
VERSION_NAME="0.0.0-${BRANCH}-${SHORT_SHA}"
TAG="test/${BRANCH}/${SHORT_SHA}"

# Refuse to clobber an existing test tag for the same commit.
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "error: test tag ${TAG} already exists" >&2
  exit 1
fi

# Build via the shared script; versionCode 1 keeps test builds below any release.
VERSION_NAME="$VERSION_NAME" \
VERSION_CODE=1 \
KEYSTORE_B64="$KEYSTORE_B64" \
KEYSTORE_PASS="$KEYSTORE_PASS" \
  "$(dirname "$0")/build-release-apk.sh"

APK_PATH="build/outputs/darkchamber-${VERSION_NAME}.apk"
[ -f "$APK_PATH" ] || { echo "error: expected APK at ${APK_PATH}" >&2; exit 1; }

git config user.name  "${GIT_AUTHOR_NAME:-github-actions[bot]}"
git config user.email "${GIT_AUTHOR_EMAIL:-github-actions[bot]@users.noreply.github.com}"
git tag "$TAG"                # lightweight tag is fine for throwaway builds
git push origin "$TAG"

gh release create "$TAG" \
  --prerelease \
  --title "Test build: ${BRANCH}@${SHORT_SHA}" \
  --notes "Unversioned test build from \`${BRANCH}\` (\`${SHORT_SHA}\`). Not a supported release — see SECURITY.md. Built $(date -u +%Y-%m-%dT%H:%MZ)." \
  "$APK_PATH"

echo "Published pre-release ${TAG}"
