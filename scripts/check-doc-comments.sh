#!/usr/bin/env bash
#
# check-doc-comments.sh — refuse a doc comment cut off its declaration.
#
# A doc comment directly followed by another (blank lines between or not) means one of them has lost
# the declaration it was written for — usually because something was inserted between a function and
# its comment, which then describes the wrong function. The one legitimate case is a file's leading
# comment: the first doc comment at the top level, before any declaration, describes the file rather
# than what follows it, so the first declaration's own comment may come straight after it.
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
  FNR == 1 {
    declared = 0      # a declaration (anything but package, import, @file: and comments) seen yet
    docs = 0          # doc comments seen so far in this file
    inDoc = 0; inBlock = 0
    pending = 0       # a doc comment just ended, and nothing but blank lines has followed it
    pendingLeading = 0
  }
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

    if (substr(t, 1, 3) == "/**" && substr(t, 1, 4) != "/**/") {
      if (pending && !pendingLeading) {
        printf "%s:%d: a doc comment directly followed by another (line %d) — one of them has lost its declaration\n", FILENAME, startLine, FNR
        bad = 1
      }
      docs++
      startLine = FNR
      pendingLeading = (!declared && docs == 1)
      if (index(substr(t, 4), "*/")) pending = 1; else { inDoc = 1; pending = 0 }
      next
    }

    pending = 0
    if (substr(t, 1, 2) == "/*") {
      if (!index(substr(t, 3), "*/")) inBlock = 1
      next
    }
    if (t ~ /^(package|import)[ \t]/ || t ~ /^@file:/ || substr(t, 1, 2) == "//") next
    declared = 1
  }
  END { exit bad }
' "$@" || {
  echo "" >&2
  echo "Put each doc comment back on the declaration it describes, or delete it if it is a stale copy." >&2
  echo "Only a file's leading comment (top level, before any declaration) may be followed by another." >&2
  exit 1
}
