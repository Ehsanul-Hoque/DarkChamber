# Git Workflow

> Part of the [Engineering Guide](./README.md). Hooks, commit conventions, branching.

> **A note on "phases" and internal increments.** [`../../ROADMAP.md`](../../ROADMAP.md) plans the
> product in **phases** — that's the unit of record in the repo and in commit history. Day-to-day the
> work is carved into smaller internal build increments; those live outside the committed tree and are
> not something a contributor needs. If you see an old reference to one (e.g. in git history), read it
> as "an early build increment within a phase" — nothing more.

## 1. Hooks — Lefthook

**Lefthook** (single binary, no Node runtime) runs local hooks; installed via `lefthook install` (wrapped as `./gradlew installHooks`). Commit-message validation uses a small shell regex — no Node dependency:

```yaml
# lefthook.yml
pre-commit:
  parallel: true
  commands:
    ktlint:
      glob: "*.{kt,kts}"
      run: ./gradlew ktlintCheck --daemon
    detekt:
      glob: "*.{kt,kts}"
      run: ./gradlew detekt --daemon

commit-msg:
  commands:
    conventional:
      run: |
        grep -qE '^(feat|fix|perf|refactor|docs|test|chore|ci|build|style)(\([a-z0-9-]+\))?!?: .+' "$1" \
          || { echo "commit message must follow Conventional Commits"; exit 1; }
```

Hooks are convenience, not the gate — CI re-checks everything on the PR, so bypassed hooks change nothing.

## 2. Commit conventions

**Conventional Commits, enforced per-commit** (not just PR title — we merge, not squash, so each commit feeds version analysis).

| Prefix                                                                   | Meaning            | Version impact              |
|--------------------------------------------------------------------------|--------------------|-----------------------------|
| `feat:`                                                                  | new feature        | **minor**                   |
| `fix:`                                                                   | bug fix            | **patch**                   |
| `perf:`                                                                  | performance fix    | **patch**                   |
| `feat!:` / `BREAKING CHANGE:` footer                                     | breaking           | **major** (minor while 0.x) |
| `refactor:` / `docs:` / `test:` / `chore:` / `ci:` / `build:` / `style:` | no behavior change | none                        |

Scopes are free-form but conventional per module: `feat(camera): …`, `fix(crypto): …`. A `fix(crypto)` or anything touching `core:crypto` additionally requires the security-review label on its PR (§3).

## 3. Branching & PRs

- **`main` is the only long-lived branch.** No `develop`. Releases are cut from `main` on demand ([`RELEASE.md`](./RELEASE.md)); merging and releasing are separate events.
- Feature branches → PR into `main`.
- **`main` protection:** no direct pushes, no force-push; PRs require passing checks + at least one approving review; **merge commits, not squash**, so conventional commits survive for version analysis.
- Because releases are tag-based, CI never pushes commits to `main` — branch protection needs no bot bypass.
- **Security-sensitive PRs** (touching `core:crypto`, `core:storage`, the format, key lifecycle, or lock/session logic): labeled `security-review`, require the maintainer's explicit review (self-review checklist while the team is one person — better than nothing, and the habit scales), and must state in the description which [`THREAT_MODEL.md`](../THREAT_MODEL.md) invariants they touch.
- External contributions follow the same rules; first-time contributors get CI runs gated on maintainer approval (default GitHub setting — keep it, it protects secrets).
