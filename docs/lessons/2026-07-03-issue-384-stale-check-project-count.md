# Issue 384 Stale Check Project Count

## Context

`./scripts/smoke-validate.sh stale-check` had a hard-coded Gradle project count
baseline. The issue snapshot said the repository had drifted to 106 active
projects, but current `develop` reports 100 active projects.

## Decision

Use the current Gradle project graph as the default baseline and keep fixed
baseline comparison as an explicit opt-in:

```bash
EXPECTED_GRADLE_PROJECTS=100 ./scripts/smoke-validate.sh stale-check
```

This avoids false warnings after intentional module additions while preserving a
manual/CI hook for strict count drift checks.

## Verification

- Baseline full local build passed before edits.
- Default stale-check passed with `Active modules: 100 (expected: current Gradle project graph)`.
- Explicit baseline stale-check passed with `Active modules: 100 (expected: 100)`.
- No stale README refs or broken image links were reported.
- Post-work full build passed with `BUILD SUCCESSFUL in 2m 22s`.
