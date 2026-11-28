#!/usr/bin/env bash
#
# check-formatting.sh — before a commit, run the formatting and static-analysis gates CI runs
# (`spotlessCheck detekt`), when the commit stages Kotlin.
#
# Spotless and detekt read the working tree, not what is staged. So a staged Kotlin file that also
# has unstaged changes is refused before anything runs: the checks would read a file other than the
# one being committed, and could pass it on a fix that is not in the commit. With that ruled out,
# every staged file is on disk as it will be committed; a file the commit does not touch can only
# make the checks fail, never let a staged file through.
#
# The tracked pre-commit hook (.githooks/pre-commit) runs this; CI runs the checks themselves on
# every pull request, so a skipped hook changes nothing. Coverage is not checked here: it needs the
# whole test suite, and a part-done change has no coverage worth gating.
#
# CHECK_FORMATTING_GRADLE overrides the Gradle command (the fixtures put a stub there).
#
# Usage: check-formatting.sh        (from anywhere inside the repository)
#
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

STAGED="$(git diff --cached --name-only --diff-filter=ACMR -- '*.kt' '*.kts')"
[ -n "$STAGED" ] || exit 0

# Staged files whose working copy differs from what is staged (edited again, or deleted, since).
PARTIAL=""
while IFS= read -r path; do
  if ! git diff --quiet -- "$path"; then
    PARTIAL="$PARTIAL  $path"$'\n'
  fi
done <<< "$STAGED"

if [ -n "$PARTIAL" ]; then
  {
    echo "These staged Kotlin files also have changes that are not staged:"
    printf '%s' "$PARTIAL"
    echo ""
    echo "Formatting and static analysis read the files on disk, so they cannot check what this commit holds."
    echo "Stage the rest of each file (git add), or set it aside (git stash push --keep-index), and commit again."
  } >&2
  exit 1
fi

GRADLE="${CHECK_FORMATTING_GRADLE:-$ROOT/gradlew}"
if ! "$GRADLE" -p "$ROOT" spotlessCheck detekt -q; then
  {
    echo ""
    echo "Formatting or static analysis refused this commit (the same checks as CI's quality job)."
    echo "./gradlew spotlessApply fixes what ktlint can; the rest is named above."
  } >&2
  exit 1
fi
