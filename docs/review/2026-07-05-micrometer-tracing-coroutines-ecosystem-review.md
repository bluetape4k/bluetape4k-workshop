# micrometer-tracing-coroutines 생태계 리뷰

날짜: 2026-07-05
모듈: `:micrometer-tracing-coroutines`
브랜치: `refactor/micrometer-tracing-coroutines-ecosystem-patterns`

## 범위

- support helper 사용을 위해 direct `bluetape4k-core` dependency를 선언했다.
- sync todo id를 bluetape4k `requirePositiveNumber`로 검증했다.
- 의도적인 sync-boundary blocking demonstration을 유지하되 simulated blocking work에 이름을 붙였다.

## 7-Tier 리뷰

| Tier | 관점 | 판정 | 근거 |
|---|---|---|---|
| 1 | Security/input | PASS | controller와 service는 logging, WebClient call, observation span 전에 non-positive todo id를 거부한다. |
| 2 | Architecture | PASS | sync/coroutine comparison shape, controller/service split, tracing application wiring은 변경 없다. |
| 3 | Coroutines/tracing | PASS | `runBlocking(Dispatchers.VT)`는 sync boundary에만 남아 있고 coroutine service path는 변경하지 않았다. |
| 4 | Code quality | PASS | 반복 sleep literal은 `simulateBlockingWork` 뒤로 중앙화했고 수정된 Kotlin spacing을 정규화했다. |
| 5 | Tests | PASS | `./gradlew :micrometer-tracing-coroutines:test --console=plain --max-workers=1`가 통과했다. |
| 6 | Operations | PASS | Zipkin launcher/test behavior와 runtime configuration은 변경 없다. |
| 7 | 근거/docs | PASS | `git diff --check`가 통과했고 Gradle test output은 11개 test와 의도된 skip 1개를 실행했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- Deferred: commented teaching snippet은 여전히 `Thread.sleep`과 `runBlocking`을 언급한다. active blocking call은 simulated sync work로 이름 붙였다.
