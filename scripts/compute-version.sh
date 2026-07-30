#!/usr/bin/env bash
#
# compute-version.sh — determine the next SemVer version from Conventional Commits.
#
# Analyzes every commit since the last tag, batching all merged work into ONE bump:
# the highest level wins (a max, not a sum — five `feat:` commits still produce a
# single minor bump). Writes grouped release notes and a Play Store draft.
#
# Env:
#   LEVEL_OVERRIDE             'auto' (default) | patch | minor | major
#   PRE_1_0_BREAKING_IS_MINOR  'true' (default) — while in 0.x, a breaking change
#                              bumps minor, since SemVer guarantees start at 1.0.0
#
# Outputs (appended to $GITHUB_OUTPUT when set, always echoed):
#   level         none | patch | minor | major
#   version       e.g. 0.2.0
#   version_code  e.g. 2000        (major*100000 + minor*1000 + patch)
#   prev_tag      e.g. v0.1.0
#
# Artifacts:
#   build/release-notes.md      grouped changelog (used as the GitHub Release body)
#   build/whatsnew-en-US.txt    Play Store draft, truncated to 500 chars
#
set -euo pipefail

LEVEL_OVERRIDE="${LEVEL_OVERRIDE:-auto}"
PRE_1_0_BREAKING_IS_MINOR="${PRE_1_0_BREAKING_IS_MINOR:-true}"

NOTES_DIR="build"
NOTES_FILE="${NOTES_DIR}/release-notes.md"
WHATSNEW_FILE="${NOTES_DIR}/whatsnew-en-US.txt"
WHATSNEW_LIMIT=500

mkdir -p "$NOTES_DIR"

# Conventional Commits:  type(optional-scope)!: description
FEAT_RE='^[[:space:]]*feat(\([^)]*\))?!?:'
FIX_RE='^[[:space:]]*fix(\([^)]*\))?!?:'
PERF_RE='^[[:space:]]*perf(\([^)]*\))?!?:'
BANG_RE='^[[:space:]]*[a-zA-Z]+(\([^)]*\))?!:'      # any type with `!` = breaking
BREAKING_FOOTER_RE='^[[:space:]]*BREAKING[ -]CHANGE:'

# --- 1. Locate the previous release tag -------------------------------------
if PREV_TAG="$(git describe --tags --abbrev=0 2>/dev/null)"; then
  RANGE="${PREV_TAG}..HEAD"
else
  PREV_TAG="v0.0.0"      # very first release: take the whole history
  RANGE="HEAD"
fi

BASE="${PREV_TAG#v}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$BASE"
MAJOR="${MAJOR:-0}"; MINOR="${MINOR:-0}"; PATCH="${PATCH:-0}"

# --- 2. Collect commit SHAs in range ----------------------------------------
# Iterating by SHA (rather than parsing a delimited log stream) avoids two classic
# traps: `--pretty=format:` omits the trailing newline, silently dropping the last
# commit; and multi-line bodies break naive field splitting.
# --no-merges: with normal (non-squash) merges, merge commits carry no conventional
# prefix and would only add noise.
SHAS=()
while IFS= read -r sha; do
  [ -n "$sha" ] && SHAS+=("$sha")
done < <(git log "$RANGE" --no-merges --format='%H' 2>/dev/null || true)

# --- 3. Classify commits; the bump level is the MAX across all of them -------
level_rank() {
  case "$1" in
    major) echo 3 ;;
    minor) echo 2 ;;
    patch) echo 1 ;;
    *)     echo 0 ;;
  esac
}

LEVEL="none"
promote() {
  if [ "$(level_rank "$1")" -gt "$(level_rank "$LEVEL")" ]; then
    LEVEL="$1"
  fi
}

BREAKING_NOTES=""
FEAT_NOTES=""
FIX_NOTES=""
PERF_NOTES=""

strip_prefix() {
  # "feat(dua)!: add sharing" -> "add sharing"
  printf '%s' "$1" | sed -E 's/^[[:space:]]*[a-zA-Z]+(\([^)]*\))?!?:[[:space:]]*//'
}

for sha in "${SHAS[@]:-}"; do
  [ -z "$sha" ] && continue
  subject="$(git log -1 --format='%s' "$sha")"
  body="$(git log -1 --format='%b' "$sha")"
  short="$(git log -1 --format='%h' "$sha")"

  entry="- $(strip_prefix "$subject") (${short})"

  if [[ "$subject" =~ $BANG_RE ]] || printf '%s' "$body" | grep -qE "$BREAKING_FOOTER_RE"; then
    promote major
    BREAKING_NOTES+="${entry}"$'\n'
  fi

  if [[ "$subject" =~ $FEAT_RE ]]; then
    promote minor
    FEAT_NOTES+="${entry}"$'\n'
  elif [[ "$subject" =~ $FIX_RE ]]; then
    promote patch
    FIX_NOTES+="${entry}"$'\n'
  elif [[ "$subject" =~ $PERF_RE ]]; then
    promote patch
    PERF_NOTES+="${entry}"$'\n'
  fi
  # docs / refactor / test / chore / ci / build / style -> no bump
done

# Manual override from the workflow input.
if [ "$LEVEL_OVERRIDE" != "auto" ]; then
  LEVEL="$LEVEL_OVERRIDE"
fi

# Pre-1.0: SemVer's stability guarantees begin at 1.0.0, so a breaking change
# conventionally bumps MINOR while still in 0.x.
if [ "$MAJOR" -eq 0 ] && [ "$LEVEL" = "major" ] && [ "$PRE_1_0_BREAKING_IS_MINOR" = "true" ]; then
  echo "note: pre-1.0 — treating breaking change as a minor bump" >&2
  LEVEL="minor"
fi

# --- 4. Compute the next version --------------------------------------------
case "$LEVEL" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
  none)  : ;;   # version stays at the previous tag
esac

VERSION="${MAJOR}.${MINOR}.${PATCH}"
VERSION_CODE=$((MAJOR * 100000 + MINOR * 1000 + PATCH))

# Width convention keeps versionCode unambiguous: major<=99, minor<=99, patch<=999.
if [ "$MAJOR" -gt 99 ] || [ "$MINOR" -gt 99 ] || [ "$PATCH" -gt 999 ]; then
  echo "error: ${VERSION} exceeds the width convention (major<=99, minor<=99, patch<=999)" >&2
  exit 1
fi

# --- 5. Grouped release notes ------------------------------------------------
{
  echo "## ${VERSION}"
  echo ""
  if [ -n "$BREAKING_NOTES" ]; then
    echo "### ⚠️ Breaking Changes"
    printf '%s' "$BREAKING_NOTES"
    echo ""
  fi
  if [ -n "$FEAT_NOTES" ]; then
    echo "### ✨ Features"
    printf '%s' "$FEAT_NOTES"
    echo ""
  fi
  if [ -n "$FIX_NOTES" ]; then
    echo "### 🐛 Fixes"
    printf '%s' "$FIX_NOTES"
    echo ""
  fi
  if [ -n "$PERF_NOTES" ]; then
    echo "### ⚡ Performance"
    printf '%s' "$PERF_NOTES"
    echo ""
  fi
  if [ "$LEVEL" != "none" ]; then
    echo "**Full changelog:** ${PREV_TAG}...v${VERSION}"
  fi
} > "$NOTES_FILE"

# --- 6. Play Store "what's new" draft (<= 500 chars) -------------------------
# A starting point only — worth a human edit before store submission.
{
  printf '%s' "$FEAT_NOTES" | sed -E 's/ \([0-9a-f]+\)$//' | sed 's/^- /• /'
  printf '%s' "$FIX_NOTES"  | sed -E 's/ \([0-9a-f]+\)$//' | sed 's/^- /• /'
} | head -c "$WHATSNEW_LIMIT" > "$WHATSNEW_FILE"

# --- 7. Emit outputs ---------------------------------------------------------
emit() {
  echo "$1"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "$1" >> "$GITHUB_OUTPUT"
  fi
}
emit "prev_tag=${PREV_TAG}"
emit "level=${LEVEL}"
emit "version=${VERSION}"
emit "version_code=${VERSION_CODE}"

if [ "$LEVEL" = "none" ]; then
  echo "No release-worthy commits since ${PREV_TAG}; nothing to release." >&2
fi
