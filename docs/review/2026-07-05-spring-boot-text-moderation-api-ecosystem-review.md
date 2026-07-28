# spring-boot-text-moderation-api 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-boot-text-moderation-api`
브랜치: `refactor/spring-boot-text-moderation-api-ecosystem-patterns`

## 범위

이 리뷰와 정리는 text moderation API workshop 예제의 bluetape4k validation helper 재사용과 Spring test injection 정리에 초점을 맞췄다.

## 검토한 변경

- validation helper를 쓰기 위해 `bluetape4k-core`를 직접 사용하도록 추가했다.
- `maxTextCharacters`를 `requirePositiveNumber`로 검증했다.
- MockMvc `lateinit` field injection을 constructor injection으로 바꿨다.

## 근거

- GNO: 편집 전에 현재 issue #316 design과 lesson을 확인했다.
- `repo-status`: feature worktree에서 tracked 변경 경로 3개를 확인했다.
- CodeGraph `detect_changes_tool`: 변경 파일 3개를 분석했다. 이 workshop 모듈에서는 function/class node나 affected flow를 제공하지 못해 source diff와 타깃 Gradle 근거로 대체 검토했다.
- hard-smell scan: `Thread.sleep`, `!!`, `companion object:`, `lateinit`, `uninitialized()`, raw JUnit assertion, kotlin.test assertion이 발견되지 않았다.
- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-boot-text-moderation-api:test --console=plain --max-workers=1`: PASS, `SUCCESS: Executed 10 tests in 2.4s`, `BUILD SUCCESSFUL in 21s`.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| Tier 1 - Security | PASS | 기존 HTTP trust-boundary 동작은 변경 없다. request-size configuration은 이제 생태계 검증을 거친다. |
| Tier 2 - Architecture | PASS | endpoint, detector, matcher, singleton bean lifecycle 변경은 없다. |
| Tier 3 - API/Docs | PASS | README/API 동작은 변경 없다. user-facing documentation update는 필요하지 않았다. |
| Tier 4 - Correctness | PASS | 기존 success, invalid input, oversized payload, bean reuse 테스트가 통과한다. |
| Tier 5 - 테스트 | PASS | MockMvc fixture는 mutable late init state 대신 constructor injection을 사용한다. |
| Tier 6 - Performance/Stability | PASS | singleton detector/matcher 동작은 보존했다. per-request construction은 추가하지 않았다. |
| Tier 7 - Evidence/Release | PASS | review artifact, GNO evidence, hard-smell scan, diff check, 타깃 테스트 근거를 기록했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- P2/P3: 없음

## 메모

공개 blank-text 오류 메시지는 low-level helper 메시지로 바꾸지 않고 안정적으로 유지했다.
