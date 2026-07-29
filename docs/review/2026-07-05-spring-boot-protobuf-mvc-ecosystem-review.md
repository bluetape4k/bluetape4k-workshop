# spring-boot-protobuf-mvc 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-boot-protobuf-mvc`
브랜치: `refactor/spring-boot-protobuf-mvc-ecosystem-patterns`

## 범위

이 리뷰와 정리는 protobuf MVC workshop 예제의 Kotlin 스타일, bluetape4k 검증 헬퍼 재사용, 불변성, Spring test injection 정리에 초점을 맞췄다.

## 검토한 변경

- 생태계 검증 헬퍼를 쓰기 위해 `bluetape4k-core`를 추가했다.
- `CourseRepository`의 raw positive ID 처리를 `requirePositiveNumber`로 바꿨다.
- repository/config sample collection을 mutable map/list에서 immutable `Map`과 `listOf`/`mapOf` 초기화로 바꿨다.
- test fixture가 dependency를 소유하는 위치의 Spring test `uninitialized()` field injection을 constructor injection으로 바꿨다.
- `KLogging` / `KLoggingChannel` companion object 공백을 정규화했다.

## 근거

- `repo-status`: feature worktree에서 tracked 변경 경로 8개를 확인했다.
- `repo-diff`: review artifact 생성 전 8개 파일, 18 insertions, 16 deletions를 확인했다.
- CodeGraph `detect_changes_tool`: 변경 파일 8개를 분석했다. 이 workshop 모듈에서는 function/class node나 affected flow를 제공하지 못해 source diff와 타깃 Gradle 근거로 대체 검토했다.
- hard-smell scan: 모듈에서 `Thread.sleep`, `!!`, `companion object:`, raw JUnit assertion, kotlin.test assertion이 발견되지 않았다.
- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-boot-protobuf-mvc:test --console=plain --max-workers=1`: PASS, `SUCCESS: Executed 9 tests in 3.1s`, `BUILD SUCCESSFUL in 18s`.

## 7-Tier 리뷰

| Tier | 판정 | 근거 |
|---|---|---|
| Tier 1 - Security | PASS | positive caller input validation은 이제 `requirePositiveNumber`를 사용한다. 새 trust boundary, auth, SQL, secret handling은 없다. |
| Tier 2 - Architecture | PASS | repository API 형태는 같고 backing collection 계약만 immutable로 정리했다. |
| Tier 3 - API/Docs | PASS | example 동작과 README-facing protobuf workflow는 변경 없다. 공개 API 문서 변경은 필요하지 않았다. |
| Tier 4 - Correctness | PASS | invalid course ID는 lookup 전에 생태계 검증 헬퍼로 실패한다. 타깃 테스트가 통과했다. |
| Tier 5 - 테스트 | PASS | constructor injection으로 `uninitialized()` test fixture state를 제거하면서 기존 coverage를 유지했다. |
| Tier 6 - Performance/Stability | PASS | immutable fixture collection은 우발적 mutation 위험을 낮춘다. hot path나 blocking 동작 변경은 없다. |
| Tier 7 - Evidence/Release | PASS | review artifact, hard-smell scan, diff check, 타깃 모듈 테스트 근거를 기록했다. |

## P0/P1 게이트

- P0: 0
- P1: 0
- P2/P3: 없음

## 메모

새 dependency는 sibling workshop 예제에서 이미 검증 헬퍼용으로 쓰는 생태계 core 모듈이다. third-party helper는 추가하지 않았다.
