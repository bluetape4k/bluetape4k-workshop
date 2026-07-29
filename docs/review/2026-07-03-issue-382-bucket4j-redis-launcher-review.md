# Issue #382 Bucket4j Redis Launcher Review

날짜: 2026-07-03
범위: Issue #382, `bucker4j-bluetape4k-webflux` Redis Testcontainers bootstrap.

## Reviewed Diff

- `ratelimit/bucker4j-bluetape4k-webflux/src/main/kotlin/io/bluetape4k/workshop/bucket4j/config/TestRedisConfig.kt`
- `ratelimit/bucker4j-bluetape4k-webflux/src/main/kotlin/io/bluetape4k/workshop/bucket4j/filter/AsyncUserRateLimitWebFilter.kt`
- `ratelimit/bucker4j-bluetape4k-webflux/src/main/kotlin/io/bluetape4k/workshop/bucket4j/filter/UserRateLimitWebFilter.kt`
- `ratelimit/bucker4j-bluetape4k-webflux/README.md`
- `ratelimit/bucker4j-bluetape4k-webflux/README.ko.md`

## 7-Tier Findings

| Tier | Lens | 판정 | 근거 |
|---|---|---|---|
| 1 | Security | PASS | authentication, authorization, request-body parsing, secret handling은 변경되지 않았다. Redis connection property는 여전히 generated Testcontainers value에서 온다. |
| 2 | Architecture | PASS | module은 이제 sibling Bucket4j example과 맞게 `RedisServer.Launcher.redis`를 사용하여 repository singleton launcher pattern을 따른다. dependency나 Spring topology 변경은 없다. |
| 3 | Concurrency / Lifecycle | PASS | runtime bootstrap에서 manual container start와 `ShutdownQueue` registration을 제거했다. Container lifecycle은 bluetape4k Testcontainers launcher singleton에 위임된다. |
| 4 | Code Quality / Correctness | PASS | fixed default-port coupling을 제거하고, trace log가 companion/object receiver 대신 extracted key를 출력하도록 수정했다. |
| 5 | Tests | PASS | targeted compile과 Redis-backed module test가 실제 Gradle project path `:bucker4j-bluetape4k-webflux`로 통과했다. |
| 6 | Performance / Operations | PASS | shared reusable launcher는 불필요한 per-bootstrap Redis container 생성을 피하고 default-port collision risk를 제거한다. |
| 7 | Documentation / Evidence | PASS | README와 README.ko는 launcher 기반 local/test Redis bootstrap을 설명하며 실제 registered Gradle project path를 사용한다. |

## P0/P1 Gate

- P0: 0
- P1: 0
- P2/P3: 이 module 밖에 남은 direct `RedisServer(...)` usage는 failure-isolation test이거나 별도 follow-up candidate이며 issue #382 범위가 아니다.

## 검토 메모

이 review는 Redis bootstrap ownership을 module-local 수동 container 관리에서 repository singleton launcher pattern으로 옮긴 것이 핵심이다. 그 결과 shutdown 책임과 port 선택이 launcher에 모이고, README의 local/test Redis 설명도 실제 Gradle project path와 맞게 정렬된다.

## 검증 근거

- 작업 전 baseline: `/tmp/issue382-baseline-build.log` — `BUILD SUCCESSFUL in 1m 43s`.
- Project path check: `/tmp/issue382-projects.log` — `:bucker4j-bluetape4k-webflux` 확인.
- Guard scan: `/tmp/issue382-guard-scan.log` — `RedisServer(`, `ShutdownQueue`, `useDefaultPort`, `Extracted key=$this` match 없음.
- Affected compile: `/tmp/issue382-affected-compile.log` — `BUILD SUCCESSFUL in 2s`.
- Redis-backed module test: `/tmp/issue382-targeted-test.log` — `BUILD SUCCESSFUL in 11s`, `6 tests executed`, `2 skipped`.
- README parity: `/tmp/issue382-readme-parity.log` — `failures: 0`.
- README language: `/tmp/issue382-readme-language.log` — `offenders: 0`, `totalHits: 0`.
- 작업 후 full build: `/tmp/issue382-full-build.log` — `BUILD SUCCESSFUL in 1m 35s`.
- `git diff --check`: `/tmp/issue382-diff-check.log` — PASS.
- CodeReviewGraph: repository는 등록되어 있었지만 worktree graph가 비어 있었다(`Files: 0`, `Last updated: never`). 따라서 review는 source diff, bluetape4k Testcontainers source, compile, test, README validator로 fallback했다.
