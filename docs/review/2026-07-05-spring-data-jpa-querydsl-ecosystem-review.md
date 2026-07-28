# Spring Data JPA QueryDSL 생태계 리뷰

날짜: 2026-07-05
모듈: `:spring-data-jpa-querydsl`

## 범위

Kotlin 스타일, bluetape4k 생태계 정렬, Spring injection pattern, 직렬화 규칙, 예제 동작 보존을 기준으로 7-Tier 리뷰를 수행했다. Spring/JPA test injection pattern, Serializable DTO 규칙, QueryDSL 예제 동작 보존을 함께 확인했다.

## 발견 사항

- P0 findings: 0.
- P1 findings: 0.
- test `lateinit`과 placeholder injection을 constructor injection 또는 scoped non-null delegate로 바꿨다.
- `InitMemberService`가 test application bean factory까지 포함해 constructor-injected `EntityManager`를 쓰도록 갱신했다.
- Serializable QueryDSL DTO/VO sample class에 `serialVersionUID`를 추가했다.
- JPA test base에 `@TestInstance(PER_CLASS)`를 추가해 기존 instance `@BeforeAll` 사용과 맞췄다.
- companion object, type declaration, constructor call spacing을 정규화했다.
- QueryDSL projection, generated Q-type constructor shape, sample data size, repository query semantics는 변경하지 않았다.
- 초기 compile failure의 원인은 `InitMemberService` constructor injection 이후 stale 상태가 된 `QueryDslApplication.initMemberService()` no-arg factory였다. bean factory가 이제 `EntityManager`를 받는다.

## 근거

- GNO orientation: repository-wide workshop ecosystem review와 관련 Spring Data note를 확인했다.
- CodeGraph impact lookup을 모듈 entry point에 대해 시도했지만 이 workshop 모듈에 맞는 graph node가 없었다. 최신 source를 직접 읽어 검토했다.
- hard-smell scan: 모듈에 `Thread.sleep`, `!!`, `lateinit`, `uninitialized()`, compact `companion object:`, raw JUnit/kotlin assertion, direct `GenericContainer`, deprecated `SqlExpressionBuilder.eq`, 우발적인 광범위 spacing rewrite가 남지 않았다.

## 검증

- `git diff --check`: PASS.
- `repo-test-summary -- ./gradlew :spring-data-jpa-querydsl:test --console=plain --max-workers=1`: PASS, 47 tests, 1 skipped, `BUILD SUCCESSFUL in 5s`.

## DoD 상태

- 7-Tier 리뷰를 완료했다.
- `$bluetape4k-code-patterns`와 Kotlin style drift를 정리했다.
- 동작을 보존하는 생태계 정리를 적용했다.
- 타깃 모듈 검증이 통과했다.
