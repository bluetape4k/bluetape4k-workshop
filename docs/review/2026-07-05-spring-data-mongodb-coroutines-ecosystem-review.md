# Spring Data MongoDB Coroutines 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-data-mongodb-coroutines`

## 범위

Kotlin 스타일, bluetape4k 생태계 정렬, Spring injection pattern, 직렬화 규칙, 예제 동작 보존을 기준으로 7-Tier 리뷰를 수행했다. Spring Data MongoDB coroutine/reactive 예제, Serializable data class 규칙, test 동작 보존을 함께 확인했다.

## 발견 사항

- P0 findings: 0.
- P1 findings: 0.
- Serializable `Person` sample document에 `serialVersionUID`를 추가했다.
- test 전용 `!!` precondition을 `checkNotNull`로 바꿔 값 누락 실패를 명시적으로 보고하도록 했다.
- 모듈 전반의 compact `companion object:` declaration을 정규화했다.
- Mongo DSL query, reactive repository 동작, tailable cursor semantics, default property mapping 동작은 변경하지 않았다.

## 근거

- GNO orientation: repository-wide workshop ecosystem review와 관련 Spring Data note를 확인했다.
- CodeGraph impact lookup을 모듈 entry point에 대해 시도했지만 이 workshop 모듈에 맞는 graph node가 없었다. 최신 source를 직접 읽어 검토했다.
- hard-smell scan: 모듈에 `Thread.sleep`, `!!`, `lateinit`, `uninitialized()`, compact `companion object:`, raw JUnit/kotlin assertion, direct `GenericContainer`, deprecated `SqlExpressionBuilder.eq`, 우발적인 광범위 spacing rewrite가 남지 않았다.

## 검증

- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-data-mongodb-coroutines:test --console=plain --max-workers=1`: PASS, 30 tests, `BUILD SUCCESSFUL in 26s`.

## DoD 상태

- 7-Tier 리뷰를 완료했다.
- `$bluetape4k-code-patterns`와 Kotlin style drift를 정리했다.
- 동작을 보존하는 생태계 정리를 적용했다.
- 타깃 모듈 검증이 통과했다.
