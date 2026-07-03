# Issue 384 Stale Check Project Count Review

## Scope

- Issue: #384 `Refresh stale-check Gradle project count baseline`.
- Work type: build/governance validation cleanup.
- Diff scope: `scripts/smoke-validate.sh` plus review/lesson artifacts.
- Baseline local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 42s`.

## Decision

The current `develop` project count is already `100`, not the stale issue-body
snapshot of `106`. The false-warning risk still exists because `stale-check`
used a hard-coded `expected=100`.

The script now derives the default baseline from the current Gradle project
graph and only compares against a fixed value when `EXPECTED_GRADLE_PROJECTS` is
explicitly provided.

## Validation Evidence

| Scope | Command | Evidence | Result |
|---|---|---|---|
| Repo | Baseline local build | `BUILD SUCCESSFUL in 1m 42s`; log `/tmp/issue384-baseline-build.log` | PASS |
| Repo | Post-work local build | `BUILD SUCCESSFUL in 2m 22s`; log `/tmp/issue384-full-build.log` | PASS |
| smoke script | Syntax | `bash -n scripts/smoke-validate.sh` | PASS |
| stale-check default | `./scripts/smoke-validate.sh stale-check` | `Active modules: 100 (expected: current Gradle project graph)`; no stale refs; no broken image links | PASS |
| stale-check explicit baseline | `EXPECTED_GRADLE_PROJECTS=100 ./scripts/smoke-validate.sh stale-check` | `Active modules: 100 (expected: 100)`; no stale refs; no broken image links | PASS |
| Repo | Whitespace | `git diff --check` | PASS |

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| Security | PASS | Shell validation only; no credentials, network, or secret paths changed. |
| Stability | PASS | Default path no longer emits false warnings when module count changes intentionally. |
| Performance | PASS | Reuses the existing `./gradlew projects` query; no extra scans added. |
| Operator/Ops | PASS | Maintains optional fixed-baseline mode via `EXPECTED_GRADLE_PROJECTS`. |
| Developer/API | PASS | Existing command name and stale README/image checks are preserved. |
| User/Reader | PASS | No README or public example behavior changed. |
| Evidence | PASS | Baseline build, shell syntax, stale-check variants, diff check, and post-work full build passed. |

## Findings

- P0/P1: 0.
- P2: None.
- P3: If CI later needs a hard project-count drift gate, set `EXPECTED_GRADLE_PROJECTS` in that CI step instead of hard-coding the local default.
