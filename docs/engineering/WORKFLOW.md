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

The checker has its own fixtures, which CI runs before using it:

```bash
bash scripts/test-check-commits.sh
```

Hooks are convenience, not the gate — CI re-checks every commit on the PR, so a skipped hook
(`--no-verify`) changes nothing. Formatting and static-analysis hooks arrive with that tooling.

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
