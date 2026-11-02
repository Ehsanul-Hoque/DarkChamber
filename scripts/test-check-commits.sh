#!/usr/bin/env bash
#
# test-check-commits.sh — fixtures for check-commits.sh. Each case writes a commit message to a
# file, runs the checker on it, and compares the exit status with what the rule says it must be.
#
# Usage: test-check-commits.sh        (exits non-zero if any case disagrees)
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-commits.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
CASES=0

# expect <want: pass|fail> <name> <message, as git would write it to the file>
expect() {
  local want="$1" name="$2" message="$3"
  local file="$WORK/msg"
  printf '%s\n' "$message" > "$file"
  CASES=$((CASES + 1))
  if bash "$CHECK" --message-file "$file" > /dev/null 2>&1; then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "✗ $name: expected $want, got $got" >&2
    FAILED=$((FAILED + 1))
  fi
}

# --- the subject pattern -------------------------------------------------------------------------
expect pass "a valid scoped subject"          "feat(core-vault): shred many items at once"
expect pass "a valid subject with no scope"   "docs: the viewer as built"
expect pass "a breaking change marker"        "feat(core-db)!: schema v2"
expect fail "an unknown type"                 "feature(core-vault): shred many items"
expect fail "no space after the colon"        "fix(core-db):zero deleted rows"
expect fail "an empty description"            "fix(core-db): "
expect fail "git's revert subject"            'Revert "feat(app): open the viewer"'
expect fail "a revert type"                   "revert(app): open the viewer"

# --- the file as git writes it ------------------------------------------------------------------
expect pass "a body and git's comment block" "$(printf '%s\n' \
  "fix(core-db): zero deleted catalog rows" "" "Why it matters." \
  "# Please enter the commit message for your changes." "# On branch topic")"
expect fail "a bad subject above a comment block" "$(printf '%s\n' \
  "Zero deleted catalog rows" "# Please enter the commit message for your changes.")"
expect pass "comments and blank lines before the subject" "$(printf '%s\n' \
  "# a template comment" "" "docs: the viewer as built")"
expect fail "nothing but comments"            "# Please enter the commit message for your changes."
# What git writes itself: let through here (CI's range mode skips merges, and a fixup must be
# squashed before it is pushed, where the range check would refuse it).
expect pass "a merge commit"                  "Merge branch 'main' into topic"
expect pass "a fixup"                         "fixup! feat(core-vault): shred many items at once"
expect pass "a squash"                        "squash! feat(core-vault): shred many items at once"
expect pass "an amend"                        "amend! feat(core-vault): shred many items at once"

# --- the scope allowlist (config/commit-scopes.txt) --------------------------------------------
expect fail "an upper-case module scope"      "test(androidApp): remove the driver's lock files"
expect fail "a module named the old way"      "fix(android): make the bar icons visible"
expect fail "a well-formed unknown scope"     "feat(viewer): open an image"
expect fail "a scope that is only a prefix"   "feat(core): scaffold the modules"
# Every listed scope is accepted — read from the file itself, so the two cannot drift.
while IFS= read -r scope; do
  case "$scope" in ''|'#'*) continue ;; esac
  expect pass "listed scope '$scope'" "chore($scope): a change"
done < "$HERE/../config/commit-scopes.txt"

# --- the range mode CI runs ---------------------------------------------------------------------
# A throwaway repository: one base commit, then the subjects under test, each case on its own
# branch so a range holds exactly the commits named.
REPO="$WORK/repo"
git init -q "$REPO"
git -C "$REPO" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "chore: base"
BASE_SHA="$(git -C "$REPO" rev-parse HEAD)"

# expect_range <want: pass|fail> <name> <subject>...
expect_range() {
  local want="$1" name="$2"; shift 2
  git -C "$REPO" checkout -q --detach "$BASE_SHA"
  local subject
  for subject in "$@"; do
    git -C "$REPO" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "$subject"
  done
  CASES=$((CASES + 1))
  if (cd "$REPO" && bash "$CHECK" "$BASE_SHA" HEAD > /dev/null 2>&1); then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "✗ range: $name: expected $want, got $got" >&2
    FAILED=$((FAILED + 1))
  fi
}

expect_range pass "valid listed subjects"   "feat(core-vault): one" "docs: two"
expect_range fail "an unlisted scope"       "feat(core-vault): one" "fix(android): two"
expect_range fail "a fixup left in a push"  "feat(core-vault): one" "fixup! feat(core-vault): one"

# A merge in the range is skipped, whatever its subject.
git -C "$REPO" checkout -q --detach "$BASE_SHA"
git -C "$REPO" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "docs: side"
SIDE="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" checkout -q --detach "$BASE_SHA"
git -C "$REPO" -c user.name=t -c user.email=t@t commit -q --allow-empty -m "docs: main"
git -C "$REPO" -c user.name=t -c user.email=t@t merge -q --no-ff --no-edit -m "Merge a thing" "$SIDE"
CASES=$((CASES + 1))
if ! (cd "$REPO" && bash "$CHECK" "$BASE_SHA" HEAD > /dev/null 2>&1); then
  echo "✗ range: a merge commit is skipped: expected pass, got fail" >&2
  FAILED=$((FAILED + 1))
fi

if [ "$FAILED" -ne 0 ]; then
  echo "$FAILED of $CASES cases failed" >&2
  exit 1
fi
echo "All $CASES cases OK"
