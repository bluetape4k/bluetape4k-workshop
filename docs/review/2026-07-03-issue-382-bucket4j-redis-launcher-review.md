# Issue #382 Bucket4j Redis Launcher Review

Date: 2026-07-03
Scope: Issue #382, `bucker4j-bluetape4k-webflux` Redis Testcontainers bootstrap.

## Reviewed Diff

- `ratelimit/bucker4j-bluetape4k-webflux/src/main/kotlin/io/bluetape4k/workshop/bucket4j/config/TestRedisConfig.kt`
- `ratelimit/bucker4j-bluetape4k-webflux/src/main/kotlin/io/bluetape4k/workshop/bucket4j/filter/AsyncUserRateLimitWebFilter.kt`
- `ratelimit/bucker4j-bluetape4k-webflux/src/main/kotlin/io/bluetape4k/workshop/bucket4j/filter/UserRateLimitWebFilter.kt`
- `ratelimit/bucker4j-bluetape4k-webflux/README.md`
- `ratelimit/bucker4j-bluetape4k-webflux/README.ko.md`

## 7-Tier Findings

| Tier | Lens | Verdict | Evidence |
|---|---|---|---|
| 1 | Security | PASS | No authentication, authorization, request-body parsing, or secret handling changed. Redis connection properties still come from generated Testcontainers values. |
| 2 | Architecture | PASS | The module now follows the repository singleton launcher pattern by using `RedisServer.Launcher.redis`, matching sibling Bucket4j examples. No dependency or Spring topology change. |
| 3 | Concurrency / Lifecycle | PASS | Removed manual container start and `ShutdownQueue` registration from runtime bootstrap. Container lifecycle is delegated to the bluetape4k Testcontainers launcher singleton. |
| 4 | Code Quality / Correctness | PASS | Removed fixed default-port coupling and corrected trace logs to print the extracted key instead of the companion/object receiver. |
| 5 | Tests | PASS | Targeted compile and Redis-backed module tests passed with the real Gradle project path `:bucker4j-bluetape4k-webflux`. |
| 6 | Performance / Operations | PASS | Shared reusable launcher avoids unnecessary per-bootstrap Redis container construction and removes default-port collision risk. |
| 7 | Documentation / Evidence | PASS | README and README.ko explain the launcher-based local/test Redis bootstrap and use the actual registered Gradle project path. |

## P0/P1 Gate

- P0: 0
- P1: 0
- P2/P3: Remaining direct `RedisServer(...)` usages outside this module are either failure-isolation tests or separate follow-up candidates, not part of issue #382.

## Validation Evidence

- Baseline before issue work: `/tmp/issue382-baseline-build.log` — `BUILD SUCCESSFUL in 1m 43s`.
- Project path check: `/tmp/issue382-projects.log` — confirmed `:bucker4j-bluetape4k-webflux`.
- Guard scan: `/tmp/issue382-guard-scan.log` — no matches for `RedisServer(`, `ShutdownQueue`, `useDefaultPort`, or `Extracted key=$this`.
- Affected compile: `/tmp/issue382-affected-compile.log` — `BUILD SUCCESSFUL in 2s`.
- Redis-backed module test: `/tmp/issue382-targeted-test.log` — `BUILD SUCCESSFUL in 11s`, `6 tests executed`, `2 skipped`.
- README parity: `/tmp/issue382-readme-parity.log` — `failures: 0`.
- README language: `/tmp/issue382-readme-language.log` — `offenders: 0`, `totalHits: 0`.
- Full build after work: `/tmp/issue382-full-build.log` — `BUILD SUCCESSFUL in 1m 35s`.
- `git diff --check`: `/tmp/issue382-diff-check.log` — PASS.
- CodeReviewGraph: repository registered but worktree graph was empty (`Files: 0`, `Last updated: never`), so review fell back to source diff, bluetape4k Testcontainers source, compile, tests, and README validators.
