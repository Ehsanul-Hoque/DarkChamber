#!/usr/bin/env bash
#
# test-check-formatting.sh — fixtures for check-formatting.sh. Each case builds a throwaway git
# repository in a given state of staged and unstaged changes, runs the checker in it with a stub in
# place of Gradle, and compares two things with what the rule says: whether the commit is let through,
# and whether the checks ran at all. Spotless and detekt read the working tree, so the rule is to run
# them only when the commit stages Kotlin or the checks' own configuration, and to refuse when what
# they would read differs from what is being committed: a staged Kotlin file with unstaged changes,
# or any change to the configuration that is not staged.
#
# Usage: test-check-formatting.sh        (exits non-zero if any case disagrees)
#
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-formatting.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Stands in for ./gradlew: records that it ran, then exits as the case says.
STUB="$WORK/gradle-stub"
cat > "$STUB" <<'EOF'
#!/usr/bin/env bash
echo ran >> "$STUB_LOG"
exit "${STUB_EXIT:-0}"
EOF
chmod +x "$STUB"

FAILED=0
CASES=0

# A repository with two committed Kotlin files, a build script, a README and the files that configure
# the checks (editorconfig, detekt config, a module's baseline, the version catalog, build logic), ready
# for a case to change.
fresh_repo() {
  local repo="$WORK/repo$CASES"
  mkdir -p "$repo/src" "$repo/config/detekt" "$repo/mod" "$repo/gradle" "$repo/build-logic"
  git -C "$repo" init -q
  git -C "$repo" config user.email fixtures@example.invalid
  git -C "$repo" config user.name fixtures
  printf 'fun a() = 1\n' > "$repo/src/A.kt"
  printf 'fun b() = 2\n' > "$repo/src/B.kt"
  printf 'plugins {}\n' > "$repo/build.gradle.kts"
  printf 'readme\n' > "$repo/README.md"
  printf 'root = true\n' > "$repo/.editorconfig"
  printf 'style: {}\n' > "$repo/config/detekt/detekt.yml"
  printf '<SmellBaseline/>\n' > "$repo/mod/detekt-baseline.xml"
  printf '[versions]\n' > "$repo/gradle/libs.versions.toml"
  printf 'notes\n' > "$repo/build-logic/README.md"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m base
  echo "$repo"
}

# expect <want: pass|fail> <want gradle: ran|skipped> <name> <stub exit> <setup commands, run in the repo>
expect() {
  local want="$1" wantRun="$2" name="$3" stubExit="$4" setup="$5"
  CASES=$((CASES + 1))
  local repo log out got gotRun
  repo="$(fresh_repo)"
  log="$WORK/log$CASES"
  out="$WORK/out$CASES"
  : > "$log"
  (cd "$repo" && eval "$setup")
  if (cd "$repo" && CHECK_FORMATTING_GRADLE="$STUB" STUB_LOG="$log" STUB_EXIT="$stubExit" \
    bash "$CHECK" > "$out" 2>&1); then got=pass; else got=fail; fi
  if [ -s "$log" ]; then gotRun=ran; else gotRun=skipped; fi
  if [ "$got" != "$want" ] || [ "$gotRun" != "$wantRun" ]; then
    echo "✗ $name: expected $want with Gradle $wantRun, got $got with Gradle $gotRun" >&2
    sed 's/^/    /' "$out" >&2
    FAILED=$((FAILED + 1))
  fi
  LAST_OUT="$out"
}

# --- nothing to check ------------------------------------------------------------------------------
expect pass skipped "nothing staged" 0 ':'
expect pass skipped "only a non-Kotlin file staged" 0 \
  'printf "more\n" >> README.md && git add README.md'
expect pass skipped "a staged deletion is nothing to format" 0 \
  'git rm -q src/A.kt'

# --- staged Kotlin, fully staged: the checks run, and their verdict is the hook's ------------------
expect pass ran "a staged Kotlin file the checks accept" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt'
expect fail ran "a staged Kotlin file the checks refuse" 1 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt'
expect pass ran "a staged build script counts as Kotlin" 0 \
  'printf "plugins { }\n" > build.gradle.kts && git add build.gradle.kts'
expect pass ran "a new Kotlin file, staged" 0 \
  'printf "fun c() = 4\n" > src/C.kt && git add src/C.kt'
expect pass ran "a staged Kotlin script that is not a build script" 0 \
  'printf "println(1)\n" > src/tool.kts && git add src/tool.kts'
expect pass ran "an unstaged change to a file the commit does not stage" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && printf "fun b() = 5\n" > src/B.kt'

# --- staged Kotlin that is not what is on disk: refused, the checks not run -------------------------
expect fail skipped "a staged Kotlin file with unstaged changes" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && printf "fun a() = 6\n" > src/A.kt'
if ! grep -q "src/A.kt" "$LAST_OUT"; then
  echo "✗ the refusal names the partially staged file" >&2
  FAILED=$((FAILED + 1))
fi
expect fail skipped "a staged Kotlin file deleted from the working tree since" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && rm src/A.kt'

# --- the checks' configuration, staged: it changes what passes, so the checks run -------------------
expect pass ran "only a detekt baseline staged" 0 \
  'printf "<SmellBaseline></SmellBaseline>\n" > mod/detekt-baseline.xml && git add mod/detekt-baseline.xml'
expect fail ran "only a detekt baseline staged, the checks refusing" 1 \
  'printf "<SmellBaseline></SmellBaseline>\n" > mod/detekt-baseline.xml && git add mod/detekt-baseline.xml'
expect pass ran "only the editorconfig staged" 0 \
  'printf "max_line_length = 200\n" >> .editorconfig && git add .editorconfig'
expect pass ran "only the detekt config staged" 0 \
  'printf "complexity: {}\n" >> config/detekt/detekt.yml && git add config/detekt/detekt.yml'
expect pass ran "only the version catalog staged" 0 \
  'printf "ktlint = \"1\"\n" >> gradle/libs.versions.toml && git add gradle/libs.versions.toml'
expect pass ran "only a file under build-logic staged" 0 \
  'printf "more\n" >> build-logic/README.md && git add build-logic/README.md'
expect pass ran "a staged deletion of a baseline is checked" 0 \
  'git rm -q mod/detekt-baseline.xml'

# --- the checks' configuration, not as committed: refused, the checks not run -----------------------
expect fail skipped "a staged Kotlin file beside an unstaged editorconfig change" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && printf "max_line_length = 200\n" >> .editorconfig'
if ! grep -q "\.editorconfig" "$LAST_OUT"; then
  echo "✗ the refusal names the unstaged configuration file" >&2
  FAILED=$((FAILED + 1))
fi
expect fail skipped "a staged Kotlin file beside an unstaged baseline change" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && printf "<x/>\n" > mod/detekt-baseline.xml'
expect fail skipped "a staged Kotlin file beside an unstaged build script change" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && printf "plugins { }\n" > build.gradle.kts'
expect fail skipped "a staged Kotlin file beside a new, untracked baseline" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && mkdir -p mod2 && printf "<x/>\n" > mod2/detekt-baseline.xml'
expect fail skipped "a staged baseline changed again since" 0 \
  'printf "<a/>\n" > mod/detekt-baseline.xml && git add mod/detekt-baseline.xml && printf "<b/>\n" > mod/detekt-baseline.xml'

# --- outside the rule: nothing it reads, or nothing to check ----------------------------------------
expect pass ran "a staged Kotlin file beside an untracked file the checks do not read" 0 \
  'printf "fun a() = 3\n" > src/A.kt && git add src/A.kt && printf "x\n" > notes.txt'
expect pass skipped "an unstaged configuration change when nothing to check is staged" 0 \
  'printf "more\n" >> README.md && git add README.md && printf "max_line_length = 200\n" >> .editorconfig'

if [ "$FAILED" -ne 0 ]; then
  echo "$FAILED of $CASES cases disagree with the rule" >&2
  exit 1
fi
echo "check-formatting.sh: all $CASES cases agree with the rule"
