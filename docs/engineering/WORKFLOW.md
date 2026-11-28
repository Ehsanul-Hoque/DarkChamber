# Git Workflow

> Part of the [Engineering Guide](./README.md). Hooks, commit conventions, branching.

> **A note on "phases" and internal increments.** [`../../ROADMAP.md`](../../ROADMAP.md) plans the
> product in **phases** — that's the unit of record in the repo and in commit history. Day-to-day the
> work is carved into smaller internal build increments; those live outside the committed tree and are
> not something a contributor needs. If you see an old reference to one (e.g. in git history), read it
> as "an early build increment within a phase" — nothing more.

## 1. Hooks

The hooks are tracked in [`.githooks/`](../../.githooks) — plain shell, no hook manager and nothing to
install beyond git. Point your clone at them once:

```bash
./gradlew installGitHooks      # sets: git config core.hooksPath .githooks
```

**`commit-msg`** refuses a message before the commit exists. It runs exactly the check CI runs —
[`scripts/check-commits.sh`](../../scripts/check-commits.sh) `--message-file` — so the subject pattern and
the scope list in [`config/commit-scopes.txt`](../../config/commit-scopes.txt) (§2) cannot differ between
your machine and the PR. Subjects git writes itself (merges, `fixup!` / `squash!` / `amend!`) pass the
hook; a fixup still has to be squashed before you push, because the PR check refuses it.

A rule of your own can run beside it: make an executable `hooks/commit-msg.local` in your git directory
(`.git/hooks/commit-msg.local` in a plain clone). The shared hook runs it after the shared check, with the
same message file. It lives inside `.git/`, so it is never committed and applies to your clone alone.

**`pre-commit`** refuses a commit that leaves a doc comment cut off its declaration — one doc comment
directly followed by another, which is what an insertion between a function and its comment leaves
behind: the comment then describes the wrong function. It runs
[`scripts/check-doc-comments.sh`](../../scripts/check-doc-comments.sh) on the Kotlin files the commit
stages, as staged. There is no exception: a comment about the whole file goes **above its `package`
line**, where the package and imports stand between it and the first declaration's own comment. Put a
stray comment back on the declaration it describes, or delete it if it is a stale copy.

Each checker has its own fixtures, which CI runs before using it:

```bash
bash scripts/test-check-commits.sh
bash scripts/test-check-doc-comments.sh
bash scripts/test-check-formatting.sh
```

Hooks are convenience, not the gate — CI re-checks every commit on the PR, so a skipped hook
(`--no-verify`) changes nothing.

**`pre-commit` also runs formatting and static analysis** — `./gradlew spotlessCheck detekt`, the checks CI's
`quality` job runs ([`TESTING.md`](./TESTING.md) §5) — when the commit stages a `.kt` or `.kts` file, through
[`scripts/check-formatting.sh`](../../scripts/check-formatting.sh). About a second with Gradle's daemon warm,
longer the first time. Those checks read the files on disk, not what is staged, so a staged Kotlin file that also
has unstaged changes is refused before they run: stage the rest of it (`git add`) or set it aside
(`git stash push --keep-index`), and commit again. Files the commit does not touch can make the checks fail, never
pass. Run `./gradlew spotlessApply` to fix what ktlint can; a finding it cannot fix is fixed by hand, or
suppressed at the site with the reason beside it. Coverage (`koverVerify`) is not checked here — it needs the whole
test suite, and runs in CI.

**Blame past the reformat.** The whole repository was reformatted in one commit that changed formatting only,
listed in [`.git-blame-ignore-revs`](../../.git-blame-ignore-revs). GitHub's blame skips it by itself; for
`git blame` in your clone, once:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

## 2. Commit conventions

**Conventional Commits, enforced per-commit** (not just PR title — we merge, not squash, so each commit feeds version analysis).

| Prefix                                                                   | Meaning            | Version impact              |
|--------------------------------------------------------------------------|--------------------|-----------------------------|
| `feat:`                                                                  | new feature        | **minor**                   |
| `fix:`                                                                   | bug fix            | **patch**                   |
| `perf:`                                                                  | performance fix    | **patch**                   |
| `feat!:` / `BREAKING CHANGE:` footer                                     | breaking           | **major** (minor while 0.x) |
| `refactor:` / `docs:` / `test:` / `chore:` / `ci:` / `build:` / `style:` | no behavior change | none                        |

A scope is optional, and when present it must be listed in
[`config/commit-scopes.txt`](../../config/commit-scopes.txt): a module's Gradle path in kebab case
(`:feature:camera` → `feat(feature-camera): …`, `:core:crypto` → `fix(core-crypto): …`), `ios-app` for the
Xcode host, or one of a few non-module scopes (`build-logic`, `ci`, `deps`, `docs`, `format`, …). Lower case
only. A change that adds a module adds its scope to the list in the same commit. A `fix(core-crypto)` or
anything touching `core:crypto` additionally requires the security-review label on its PR (§3).

## 3. Branching & PRs

- **`main` is the only long-lived branch.** No `develop`. Releases are cut from `main` on demand ([`RELEASE.md`](./RELEASE.md)); merging and releasing are separate events.
- Feature branches → PR into `main`.
- **`main` protection:** no direct pushes, no force-push; PRs require passing checks + at least one approving review; **merge commits, not squash**, so conventional commits survive for version analysis.
- Because releases are tag-based, CI never pushes commits to `main` — branch protection needs no bot bypass.
- **Security-sensitive PRs** (touching `core:crypto`, `core:storage`, the format, key lifecycle, or lock/session logic): labeled `security-review`, require the maintainer's explicit review (self-review checklist while the team is one person — better than nothing, and the habit scales), and must state in the description which [`THREAT_MODEL.md`](../THREAT_MODEL.md) invariants they touch.
- External contributions follow the same rules; first-time contributors get CI runs gated on maintainer approval (default GitHub setting — keep it, it protects secrets).
