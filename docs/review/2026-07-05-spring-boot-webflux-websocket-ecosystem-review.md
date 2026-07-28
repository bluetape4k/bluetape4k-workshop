# spring-boot-webflux-websocket 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-boot-webflux-websocket`
브랜치: `refactor/spring-boot-webflux-websocket-ecosystem-patterns`

## 범위

이 리뷰와 정리는 bluetape4k validation helper 재사용, serializable WebFlux streaming model, Kotlin style 일관성에 초점을 맞췄다.

## 검토한 변경

- route duration validation을 위해 `bluetape4k-core`를 직접 사용하도록 추가했다.
- `/quotes/{duration}`을 `requirePositiveNumber`로 검증했다.
- `Command`, `Event`, `Quote`를 명시적 `serialVersionUID`가 있는 serializable model로 만들었다.
- `KLoggingChannel` companion object 공백을 정규화했다.

## 근거

- `repo-status`: feature worktree에서 tracked 변경 경로 9개를 확인했다.
- CodeGraph `detect_changes_tool`: 변경 파일 9개를 분석했다. 이 workshop 모듈에서는 function/class node나 affected flow를 제공하지 못해 source diff와 타깃 Gradle 근거로 대체 검토했다.
- hard-smell scan: `Thread.sleep`, `!!`, `companion object:`, `lateinit`, `uninitialized()`, raw JUnit assertion, kotlin.test assertion이 발견되지 않았다.
- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-boot-webflux-websocket:test --console=plain --max-workers=1`: PASS, `SUCCESS: Executed 3 tests in 2.1s`, `BUILD SUCCESSFUL in 8s`.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| Tier 1 - Security | PASS | route duration input은 이제 positive-number validation을 거친다. 새 external trust boundary는 없다. |
| Tier 2 - Architecture | PASS | WebSocket handler, router, generator, streaming topology는 변경 없다. |
| Tier 3 - API/Docs | PASS | endpoint path와 response wire shape는 변경 없다. 양수가 아닌 duration은 더 이른 단계에서 실패한다. |
| Tier 4 - Correctness | PASS | streaming quote test는 DTO serialization과 validation update 이후에도 통과한다. |
| Tier 5 - 테스트 | PASS | 기존 WebTestClient coverage는 green 상태를 유지한다. |
| Tier 6 - Performance/Stability | PASS | Quote generation, conflation, Flow/Flux 동작은 변경 없다. |
| Tier 7 - Evidence/Release | PASS | review artifact, hard-smell scan, diff check, 타깃 모듈 테스트 근거를 기록했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- P2/P3: 없음

## 메모

static HTML demo에는 JavaScript `var` 선언이 계속 남아 있다. 이는 Kotlin style drift가 아니므로 Kotlin 중심 pass에서 변경하지 않았다.
