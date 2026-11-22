#!/usr/bin/env bash
#
# check-doc-comments.sh — refuse a doc comment cut off its declaration.
#
# A doc comment directly followed by another (blank lines between or not) means one of them has lost
# the declaration it was written for — usually because something was inserted between a function and
# its comment, which then describes the wrong function. There is no exception: a comment about the
# file as a whole sits above the `package` line, where the package and imports stand between it and the
# first declaration's own comment.
#
# The tracked pre-commit hook (.githooks/pre-commit) runs this on the staged Kotlin files; CI runs it
# over every tracked one, so a skipped hook changes nothing.
#
# Usage: check-doc-comments.sh [file.kt ...]     with no files, every tracked .kt file
#
set -euo pipefail

if [ "$#" -eq 0 ]; then
  ROOT="$(cd "$(dirname "$0")/.." && pwd)"
  cd "$ROOT"
  set -- $(git ls-files '*.kt')
fi
[ "$#" -gt 0 ] || exit 0

awk '
  FNR == 1 { inDoc = 0; inBlock = 0; pending = 0 }
  {
    t = $0
    sub(/^[ \t]+/, "", t)

    if (inDoc) {
      if (index(t, "*/")) { inDoc = 0; pending = 1 }
      next
    }
    if (inBlock) {
      if (index(t, "*/")) inBlock = 0
      next
    }
    if (t == "") next

    # A doc comment: report it when the one before it ended with nothing but blank lines since.
    if (substr(t, 1, 3) == "/**" && substr(t, 1, 4) != "/**/") {
      if (pending) {
        printf "%s:%d: a doc comment directly followed by another (line %d) — one of them has lost its declaration\n", FILENAME, startLine, FNR
        bad = 1
      }
      startLine = FNR
      if (index(substr(t, 4), "*/")) pending = 1; else { inDoc = 1; pending = 0 }
      next
    }

    # Anything else — code, a package or import line, a line or block comment — ends the run.
    pending = 0
    if (substr(t, 1, 2) == "/*" && !index(substr(t, 3), "*/")) inBlock = 1
  }
  END { exit bad }
' "$@" || {
  echo "" >&2
  echo "Put each doc comment back on the declaration it describes, or delete it if it is a stale copy." >&2
  echo "A comment about the whole file goes above its package line." >&2
  exit 1
}
