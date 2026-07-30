#!/usr/bin/env bash
#
# check-commits.sh — verify every commit in a range follows Conventional Commits.
# Node-free replacement for commitlint, used by CI on PRs (re-checking what the
# local Lefthook hook already checked, so bypassed hooks change nothing).
#
# Usage: check-commits.sh <base-sha> <head-sha>
#
set -euo pipefail

BASE="${1:?usage: check-commits.sh <base-sha> <head-sha>}"
HEAD="${2:?usage: check-commits.sh <base-sha> <head-sha>}"

PATTERN='^(feat|fix|perf|refactor|docs|test|chore|ci|build|style)(\([a-z0-9._-]+\))?!?: .+'
FAIL=0

while IFS= read -r sha; do
  [ -z "$sha" ] && continue
  subject="$(git log -1 --format='%s' "$sha")"
  if ! printf '%s' "$subject" | grep -qE "$PATTERN"; then
    echo "✗ $sha  $subject" >&2
    FAIL=1
  fi
done < <(git log "${BASE}..${HEAD}" --no-merges --format='%H')

if [ "$FAIL" -ne 0 ]; then
  echo "" >&2
  echo "Commit messages must follow Conventional Commits:" >&2
  echo "  type(scope)?: description   — types: feat fix perf refactor docs test chore ci build style" >&2
  exit 1
fi

echo "All commit messages OK"
