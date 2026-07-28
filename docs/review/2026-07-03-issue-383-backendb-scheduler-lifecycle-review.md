# Issue #383 Review: BackendB Programmatic Scheduler Lifecycle

## 범위

- 이슈: #383 `Close BackendB programmatic scheduler lifecycle`
- Module: `spring-boot/resilience4j-coroutines`
- 변경 build file:
  - `spring-boot/resilience4j-coroutines/build.gradle.kts`
- 변경 production file:
  - `spring-boot/resilience4j-coroutines/src/main/kotlin/io/bluetape4k/workshop/resilience/controller/BackendBController.kt`
- 변경 test file:
  - `spring-boot/resilience4j-coroutines/src/test/kotlin/io/bluetape4k/workshop/resilience/controller/BackendBControllerLifecycleTest.kt`

## Root Cause

`BackendBController`는 `CompletableFuture` endpoint를 위해 programmatic Resilience4j `TimeLimiter`와 `Retry` 사용을 보여 준다. controller는 dedicated `ScheduledExecutorService`를 소유했지만 executor에는 Spring shutdown hook이 없었다. 그 결과 ownership이 모호했고 application context shutdown 사이에 scheduler thread가 leak될 수 있었다.

## Implementation Review

- scheduler는 이제 Spring runtime을 위한 default owned scheduler를 가진 명시적 constructor dependency다.
- default scheduler는 `NamedThreadFactory("backend-b-programmatic-scheduler", isDaemon = true)`를 사용하므로 thread purpose가 보이고, workshop은 anonymous JDK pool 대신 bluetape4k ecosystem infrastructure를 사용한다.
- production code가 `io.bluetape4k.concurrent.NamedThreadFactory`를 import하므로 module은 이제 `implementation(libs.bluetape4k.core)`를 직접 선언한다.
- `@PreDestroy close()`는 owned scheduler를 shutdown하고, bluetape4k lifecycle guidance에 맞게 `runCatching`으로 cleanup failure를 격리한다.
- future decorator path는 여전히 `withTimeLimiter(...)`와 `withRetry(...)`에 같은 scheduler를 사용한다. endpoint behavior는 변경되지 않았다.
- focused lifecycle test는 `TestingExecutors`에서 test scheduler를 주입하고, 초기 running state를 확인하며, `close()`를 두 번 호출하고 idempotent shutdown을 assert한다.

## 7-Tier Review

| Tier | 판정 | 근거 |
|---|---|---|
| 1. Security | PASS | 새 user input, secret, auth, serialization, external trust boundary가 없다. |
| 2. Architecture | PASS | Ownership은 controller에 local하고 explicit하다. 새 global bean이나 module boundary는 없다. |
| 3. Concurrency / Lifecycle | PASS | Scheduler ownership에는 `@PreDestroy`가 있으며 cleanup은 idempotent이고 테스트된다. |
| 4. Code Quality | PASS | 작은 scoped diff, named constant, bluetape4k `NamedThreadFactory`, ad hoc shared utility 없음. |
| 5. Tests | PASS | `BackendBControllerLifecycleTest`가 shutdown을 다루고, targeted TimeLimiter/Future test가 기존 path를 검증한다. |
| 6. Documentation / Learner Clarity | PASS | code가 scheduler purpose를 이름으로 드러낸다. README behavior는 바뀌지 않았으므로 README update는 필요하지 않다. |
| 7. Evidence Integrity | PASS | baseline build, compile, targeted test, full build gate가 아래 log path로 기록되어 있다. |

## P0/P1 Gate

- P0: 0
- P1: 0
- P2/P3: 없음

## 검증 근거

- 수정 전 baseline: `/tmp/issue383-baseline-build.log` — `BUILD SUCCESSFUL in 1m 17s`.
- Affected compile: `/tmp/issue383-affected-compile.log` — `BUILD SUCCESSFUL in 1s`.
- lifecycle cleanup 후 targeted tests: `/tmp/issue383-targeted-tests-2.log` — `BUILD SUCCESSFUL in 24s`, `11 passing`.
- direct dependency declaration 후 targeted compile/tests: `/tmp/issue383-targeted-tests-3.log` — `BUILD SUCCESSFUL in 4s`.
- 작업 후 full build: `/tmp/issue383-full-build.log` — `BUILD SUCCESSFUL in 2m 59s`.
- `git diff --check` — PASS.
- CodeReviewGraph: repository는 등록되어 있었지만 worktree graph가 비어 있었다(`Files: 0`, `Last updated: never`). 따라서 review는 source diff, GNO context, compile, test로 fallback했다.
