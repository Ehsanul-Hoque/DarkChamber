#!/usr/bin/env bash
#
# test-check-doc-comments.sh — fixtures for check-doc-comments.sh. Each case writes a small Kotlin
# file, runs the checker on it, and compares the exit status with what the rule says it must be: a
# doc comment followed directly by another means one of them has lost its declaration — unless the
# first is the file's leading comment, which describes the file rather than what follows it.
#
# Usage: test-check-doc-comments.sh        (exits non-zero if any case disagrees)
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-doc-comments.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
CASES=0

# expect <want: pass|fail> <name> <file contents>
expect() {
  local want="$1" name="$2" source="$3"
  local file="$WORK/Case$CASES.kt"
  printf '%s\n' "$source" > "$file"
  CASES=$((CASES + 1))
  if bash "$CHECK" "$file" > /dev/null 2>&1; then got=pass; else got=fail; fi
  if [ "$got" != "$want" ]; then
    echo "✗ $name: expected $want, got $got" >&2
    FAILED=$((FAILED + 1))
  fi
}

# --- what is allowed ------------------------------------------------------------------------------
expect pass "one doc comment per declaration" '
package a

/** A thing. */
class A {
    /** Does it. */
    fun b() = Unit

    /**
     * Does it again.
     */
    fun c() = Unit
}'

expect pass "a file leading comment, then the first declaration own" '
package a

import b.C

/**
 * What this file is for.
 */

/** The first function. */
fun f() = Unit'

expect pass "a leading comment after a licence block" '
/* Licence text,
 * over two lines. */
package a

/** What this file is for. */

/** The first function. */
fun f() = Unit'

expect pass "two declarations each with a comment, an annotation between" '
class A {
    /** One. */
    @Test
    fun one() = Unit

    /** Two. */
    @Test
    fun two() = Unit
}'

expect pass "a line comment between is not a doc comment" '
class A {
    /** One. */
    // a note
    fun one() = Unit
}'

expect pass "no comments at all" '
package a
fun f() = Unit'

# --- what is refused ------------------------------------------------------------------------------
expect fail "two doc comments stacked inside a class" '
class A {
    /** For one. */
    /** For two. */
    fun two() = Unit
}'

expect fail "stacked with a blank line between" '
class A {
    /**
     * For one.
     */

    /**
     * For two.
     */
    fun two() = Unit
}'

expect fail "stacked above an annotated test" '
class ATest {
    @Test
    fun zero() = Unit

    /** For one. */
    /** For two. */
    @Test
    fun two() = Unit
}'

expect fail "stacked at the top level after a declaration" '
package a

fun f() = Unit

/** For g. */
/** For h. */
fun h() = Unit'

expect fail "a second leading comment is not leading" '
package a

/** What this file is for. */
/** More about it. */
/** The first function. */
fun f() = Unit'

expect fail "stacked after a leading comment and a declaration" '
/** What this file is for. */

fun f() = Unit

/** For g. */

/** For h. */
fun h() = Unit'

echo "$CASES cases, $FAILED failed"
[ "$FAILED" -eq 0 ]
