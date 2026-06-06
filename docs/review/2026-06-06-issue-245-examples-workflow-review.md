# Issue 245 Examples Workflow Review

## Scope

- `.github/workflows/Examples.yml`
- `docs/lessons/2026-06-06-issue-245-examples-weekly.md`

## Review Result

- P0: 0
- P1: 0
- P2: 0

## Findings

No blocking findings.

## Evidence

- The workflow has one weekly schedule: `0 22 * * 0`.
- `workflow_dispatch`, push path filters, and pull request path filters are
  present.
- The selected module list is explicit in the workflow comments and Gradle
  command lines.
- H2/default smoke examples and Testcontainers-backed examples are separated.
- Testcontainers-backed modules run in one Gradle invocation with
  `--max-workers=1`.
- Both example lanes upload test result artifacts.
- `actionlint .github/workflows/Examples.yml` passed.
- `git diff --check` passed.

## Residual Risk

GitHub Actions checks still need to complete on the PR before merge. The
container-backed lane can be slower than the smoke lane because it starts
PostgreSQL, Redis, and Kafka examples sequentially.
