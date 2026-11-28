#!/usr/bin/env bash
#
# check-formatting.sh — before a commit, run the formatting and static-analysis gates CI runs
# (`spotlessCheck detekt`), when the commit stages Kotlin or the files that configure those checks.
#
# The configuration is .editorconfig (ktlint's rules), config/detekt/, each module's
# detekt-baseline.xml, build-logic/ (the plugin that wires the checks), the version catalog (their
# versions) and every build script. A commit changing one of them changes what passes, so it is
# checked like Kotlin.
#
# Spotless and detekt read the working tree, not what is staged. So before anything runs, the hook
# refuses when what they would read differs from what is being committed: a staged Kotlin file that
# also has unstaged changes, or any change to the configuration that is not staged (an edit, a
# deletion, a new untracked file) — the checks could otherwise pass the commit on a fix or a rule
# that is not in it. With that ruled out, every file the checks read is either as it will be
# committed or Kotlin the commit does not touch, which can only make them fail.
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

# The files that configure the checks (git pathspecs; a leading * matches at any depth).
CONFIG=(.editorconfig config/detekt '*detekt-baseline.xml' build-logic gradle/libs.versions.toml '*.gradle.kts')

# Kotlin added, changed or renamed (a deleted Kotlin file is nothing to format), and any change to
# the configuration, deletions included.
STAGED_KOTLIN="$(git diff --cached --name-only --diff-filter=ACMR -- '*.kt' '*.kts')"
STAGED_CONFIG="$(git diff --cached --name-only -- "${CONFIG[@]}")"
[ -n "$STAGED_KOTLIN$STAGED_CONFIG" ] || exit 0

# Staged Kotlin whose working copy differs from what is staged (edited again, or deleted, since).
PARTIAL=""
while IFS= read -r path; do
  [ -n "$path" ] || continue
  if ! git diff --quiet -- "$path"; then
    PARTIAL="$PARTIAL  $path"$'\n'
  fi
done <<< "$STAGED_KOTLIN"

# Configuration on disk that is not what is staged: unstaged edits and deletions, and new files git
# does not track yet (a new module's baseline is read the moment it exists).
UNSTAGED_CONFIG="$(git diff --name-only -- "${CONFIG[@]}"; git ls-files --others --exclude-standard -- "${CONFIG[@]}")"

if [ -n "$PARTIAL$UNSTAGED_CONFIG" ]; then
  {
    if [ -n "$PARTIAL" ]; then
      echo "These staged Kotlin files also have changes that are not staged:"
      printf '%s' "$PARTIAL"
    fi
    if [ -n "$UNSTAGED_CONFIG" ]; then
      echo "These files configure the checks and have changes that are not staged:"
      while IFS= read -r path; do
        if [ -n "$path" ]; then echo "  $path"; fi
      done <<< "$UNSTAGED_CONFIG"
    fi
    echo ""
    echo "Formatting and static analysis read the files on disk, so they cannot check what this commit holds."
    echo "Stage the rest of each file (git add), or set it aside (git stash push --keep-index --include-untracked),"
    echo "and commit again."
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
