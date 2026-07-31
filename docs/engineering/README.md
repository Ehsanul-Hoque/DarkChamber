# DarkChamber — Engineering Guide

> How the codebase is built and maintained — as opposed to *what it does* ([`../../ROADMAP.md`](../../ROADMAP.md)), *how it's designed* ([`../ARCHITECTURE.md`](../ARCHITECTURE.md)), or *what it defends against* ([`../THREAT_MODEL.md`](../THREAT_MODEL.md)).

Split by concern; read in order for onboarding:

| Doc                            | Covers                                                                                                                   |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| [`MODULES.md`](./MODULES.md)   | Modularization, api/impl split, feature layers, dependency rules (Gradle + Konsist), convention plugins, version catalog |
| [`PATTERNS.md`](./PATTERNS.md) | Dependency injection, navigation & the session gate, MVI, logging, error handling                                        |
| [`TESTING.md`](./TESTING.md)   | TDD strategy, security-critical test gates (format vectors, migrations), coverage policy, quality tools                  |
| [`WORKFLOW.md`](./WORKFLOW.md) | Git hooks, commit conventions, branching & PR rules                                                                      |
| [`RELEASE.md`](./RELEASE.md)   | CI/CD workflows, tag-based versioning, release scripts, secrets, supply-chain & reproducible builds                      |

Governing principles (the rest is elaboration):

- **Clean architecture** — dependencies point inward; `domain` knows nothing of `data` or `presentation`.
- **Strict module isolation** — enforced at compile time by the Gradle graph and by Konsist, not by convention.
- **Security invariants are tests** — "plaintext never touches shared storage" and "all crypto goes through `core-crypto`" fail the build, not just review.
- **TDD** — tests before implementation; coverage gated in CI; `core-crypto` held to ~100%.
- **Automate the boring, gate the risky** — formatting, linting, architecture rules, versioning, and releases are automated; `main` only advances through reviewed PRs.
- **v1 ships with no `INTERNET` permission** — see [`../ADR/ADR-004.md`](../ADR/ADR-004.md).
