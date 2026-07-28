# spring-boot-stomp-websocket 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-boot-stomp-websocket`
브랜치: `refactor/spring-boot-stomp-websocket-ecosystem-patterns`

## 범위

이 리뷰와 정리는 Kotlin 스타일, bluetape4k validation/assertion 재사용, serializable message model, Spring WebSocket integration test 정리에 초점을 맞췄다.

## 검토한 변경

- STOMP message DTO에 명시적 `serialVersionUID` 상수를 추가했다.
- `GreetingController`의 ad hoc blank-name 처리를 `requireNotBlank`로 바꿨다.
- greeting path에서 controller `Thread.sleep(...)`을 제거했다.
- test `!!` 추출을 bluetape4k `shouldNotBeNull`로 바꿨다.
- STOMP test message converter에서 주입된 `JsonMapper`를 재사용했다.
- `KLogging` companion object 공백과 작은 Kotlin formatting을 정규화했다.

## 근거

- `repo-status`: feature worktree에서 tracked 변경 경로 9개를 확인했다.
- `repo-diff`: review artifact 생성 전 9개 파일, 31 insertions, 22 deletions를 확인했다.
- CodeGraph `detect_changes_tool`: 변경 파일 9개를 분석했다. 이 workshop 모듈에서는 function/class node나 affected flow를 제공하지 못해 source diff와 타깃 Gradle 근거로 대체 검토했다.
- hard-smell scan: 모듈에서 `Thread.sleep`, `!!`, `companion object:`, raw JUnit assertion, kotlin.test assertion이 발견되지 않았다.
- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-boot-stomp-websocket:test --console=plain --max-workers=1`: PASS, `SUCCESS: Executed 2 tests in 3s`, `BUILD SUCCESSFUL in 8s`.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| Tier 1 - Security | PASS | WebSocket input name validation은 이제 `requireNotBlank`를 사용한다. HTML escaping은 유지했다. |
| Tier 2 - Architecture | PASS | STOMP endpoint, broker, test transport 계약은 변경 없다. |
| Tier 3 - API/Docs | PASS | DTO wire shape와 example README-facing 동작은 변경 없다. README update는 필요하지 않았다. |
| Tier 4 - Correctness | PASS | greeting 동작은 blocking/coroutine integration test가 계속 커버한다. |
| Tier 5 - 테스트 | PASS | 테스트는 이제 `!!`를 피하고 주입된 Jackson mapper를 재사용한다. |
| Tier 6 - Performance/Stability | PASS | message handling path에서 인위적인 controller sleep을 제거했다. |
| Tier 7 - Evidence/Release | PASS | review artifact, hard-smell scan, diff check, 타깃 모듈 테스트 근거를 기록했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- P2/P3: 없음

## 메모

validation과 assertion은 bluetape4k 생태계 헬퍼를 사용한다. 새 third-party dependency나 infrastructure fixture는 추가하지 않았다.
