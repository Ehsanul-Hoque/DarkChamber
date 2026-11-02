#!/usr/bin/env bash
#
# check-commits.sh — verify commit messages follow Conventional Commits, with a scope from
# config/commit-scopes.txt. Node-free. CI runs the range mode over a PR; the tracked commit-msg hook
# (.githooks/commit-msg) runs the message-file mode, so a skipped hook changes nothing.
#
# Usage: check-commits.sh <base-sha> <head-sha>      every commit in the range (CI)
#        check-commits.sh --message-file <path>      one message, as git hands it to commit-msg
#
set -euo pipefail

PATTERN='^(feat|fix|perf|refactor|docs|test|chore|ci|build|style)(\([a-z0-9._-]+\))?!?: .+'
SCOPES_FILE="$(cd "$(dirname "$0")/.." && pwd)/config/commit-scopes.txt"
SCOPES="$(grep -v '^[[:space:]]*#' "$SCOPES_FILE" | grep -v '^[[:space:]]*$')"

explain() {
  echo "" >&2
  echo "Commit messages must follow Conventional Commits:" >&2
  echo "  type(scope)?: description   — types: feat fix perf refactor docs test chore ci build style" >&2
  echo "  scopes (config/commit-scopes.txt), or none:" >&2
  printf '%s\n' "$SCOPES" | paste -sd ' ' - | fold -s -w 96 | sed 's/^/    /' >&2
}

# True when the subject matches the pattern and its scope, if it has one, is listed.
subject_ok() {
  local subject="$1" scope
  printf '%s' "$subject" | grep -qE "$PATTERN" || return 1
  scope="$(printf '%s' "$subject" | sed -nE 's/^[a-z]+\(([^)]*)\).*/\1/p')"
  [ -z "$scope" ] || printf '%s\n' "$SCOPES" | grep -qxF "$scope"
}

if [ "${1:-}" = "--message-file" ]; then
  FILE="${2:?usage: check-commits.sh --message-file <path>}"
  # The subject is the first line that is neither blank nor one of git's '#' comment lines.
  subject="$(grep -v '^#' "$FILE" | grep -v '^[[:space:]]*$' | head -n 1 || true)"
  # Subjects git writes itself pass through: a merge (the range mode skips merges too), and a
  # fixup/squash/amend, which must be squashed away before a push — the range check refuses it.
  if printf '%s' "$subject" | grep -qE "^(Merge (branch|remote-tracking branch|tag|commit|pull request) |(fixup|squash|amend)! )"; then
    exit 0
  fi
  if ! subject_ok "$subject"; then
    echo "✗ $subject" >&2
    explain
    exit 1
  fi
  exit 0
fi

BASE="${1:?usage: check-commits.sh <base-sha> <head-sha>}"
HEAD="${2:?usage: check-commits.sh <base-sha> <head-sha>}"
FAIL=0

while IFS= read -r sha; do
  [ -z "$sha" ] && continue
  subject="$(git log -1 --format='%s' "$sha")"
  if ! subject_ok "$subject"; then
    echo "✗ $sha  $subject" >&2
    FAIL=1
  fi
done < <(git log "${BASE}..${HEAD}" --no-merges --format='%H')

if [ "$FAIL" -ne 0 ]; then
  explain
  exit 1
fi

echo "All commit messages OK"
