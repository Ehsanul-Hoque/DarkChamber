#!/usr/bin/env bash
#
# tag-and-release.sh — tag main's HEAD and publish a GitHub Release.
#
# Creates an annotated tag vX.Y.Z on the commit already at main's HEAD (no commit is
# pushed to main, so branch protection needs no bypass), then publishes a GitHub
# Release whose body is the changelog produced by compute-version.sh, attaching the
# APK and the Play Store "what's new" draft as assets.
#
# Env (required):
#   VERSION       e.g. 0.2.0        (from compute-version.sh)
#   GITHUB_TOKEN  provided by Actions; needs `contents: write`
# Env (optional):
#   APK_PATH      default: build/outputs/darkchamber-<VERSION>.apk
#   NOTES_FILE    default: build/release-notes.md
#   WHATSNEW_FILE default: build/whatsnew-en-US.txt
#
set -euo pipefail

: "${VERSION:?VERSION is required (e.g. 0.2.0)}"

TAG="v${VERSION}"
NOTES_FILE="${NOTES_FILE:-build/release-notes.md}"
WHATSNEW_FILE="${WHATSNEW_FILE:-build/whatsnew-en-US.txt}"
APK_PATH="${APK_PATH:-build/outputs/darkchamber-${VERSION}.apk}"

if [ ! -f "$NOTES_FILE" ]; then
  echo "error: ${NOTES_FILE} not found — run compute-version.sh first" >&2
  exit 1
fi

# Refuse to clobber an existing release.
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "error: tag ${TAG} already exists" >&2
  exit 1
fi

# --- Tag main's HEAD ---------------------------------------------------------
# An annotated tag (-a) carries a message and author, unlike a lightweight tag,
# and is what `git describe` prefers.
git config user.name  "${GIT_AUTHOR_NAME:-github-actions[bot]}"
git config user.email "${GIT_AUTHOR_EMAIL:-github-actions[bot]@users.noreply.github.com}"

git tag -a "$TAG" -m "Release ${VERSION}"
git push origin "$TAG"      # pushes the TAG ref only — main's history is untouched

echo "Tagged $(git rev-parse --short HEAD) as ${TAG}"

# --- Publish the GitHub Release ---------------------------------------------
ASSETS=()
[ -f "$APK_PATH" ]      && ASSETS+=("$APK_PATH")
[ -f "$WHATSNEW_FILE" ] && ASSETS+=("$WHATSNEW_FILE")

# `gh` is preinstalled on GitHub-hosted runners and reads GITHUB_TOKEN from the env.
gh release create "$TAG" \
  --title "DarkChamber ${VERSION}" \
  --notes-file "$NOTES_FILE" \
  "${ASSETS[@]}"

echo "Published GitHub Release ${TAG} with ${#ASSETS[@]} asset(s)"
