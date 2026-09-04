# Issue #882 Spring Data Exposed QBE와 FluentQuery

## Context

Spring Data Exposed 2.0.0에 coroutine-native Query by Example(QBE)와
FluentQuery가 추가되었지만 `spring-data/r2dbc-webflux-exposed`는 명시적 CRUD
repository만 사용하고 있었다. 기존 CRUD API를 보존하면서 matcher, 정렬,
projection, page, count, exists와 `Flow` 취소 경계를 한 consumer 예제로
고정할 필요가 있었다.

## Decision or Finding

- `UserQueryByExampleRepository`를 기존 `UserExposedRepository`와 분리하고
  `ExposedR2dbcQueryByExampleRepository<UserRecord, Int>`를 사용한다.
- Spring Boot 4.1의 기본 Spring Data R2DBC repository 자동설정을 제외하고
  `@EnableExposedR2dbcRepositories`만 활성화해 같은 interface가 두 factory에
  등록되지 않도록 한다.
- `loginPrefix`는 `STARTING` matcher, `email`은 exact matcher로 컴파일한다.
  upstream compiler가 ignore-case를 지원하지 않으므로 예제에서 해당 옵션을
  사용하지 않는다.
- FluentQuery는 `UserSummary(name, login)` projection과 `PageRequest`를 조합하고,
  `count`와 `exists`를 별도 SQL terminal로 실행한다. 기존 전체 UserRecord 응답과
  email/avatar 노출은 그대로 두지 않는다.
- QBE terminal은 repository factory가 suspend transaction을 소유한다. PostgreSQL
  driver에서 `findAll` Flow를 조기 취소하는 테스트는 Exposed transaction context를
  유지한 범위에서 collect해 connection 반환과 후속 `exists`를 확인한다.

## Outcome

`/api/users/qbe` annotation controller와 `/users/qbe` functional route를 추가했다.
두 경로는 동일한 service를 호출하며 page 범위(0 이상, size 1~100)를 검증하고,
projection 결과와 `count`, `exists`, `hasNext`를 JSON으로 반환한다. versionless
`exposed-spring-boot-r2dbc` alias를 root `bluetape4k-dependencies:2.0.0` BOM에
연결했다. README 두 언어, coverage matrix, workflow, stale guard, lesson/review,
stack receipt를 함께 갱신했다.

## Verification

- RED 단계에서 구현 전 `compileTestKotlin`이 repository/QBE API 미해결로 실패하는
  것을 확인했다.
- `UserQueryByExampleRepositoryTest`: 3개 테스트 통과
- `UserControllerTest`와 `UserHandlerIT`: 36개 테스트 통과
- `:spring-data-r2dbc-webflux-exposed:compileKotlin :...:compileTestKotlin` 통과
- 모듈 전체 test는 73개 통과했다. 기존 다이얼렉트 하네스가 전역 기본 DB를
  변경하는 경계를 `AbstractWebfluxR2dbcExposedApplicationTest`의 `@BeforeEach`에서
  주입된 PostgreSQL DB로 복원해 QBE HTTP/terminal을 안정화했다.
- 모듈 build와 root detekt가 통과했다. stale-check, 변경 README parity/language,
  actionlint, JSON parse, `git diff --check`도 통과했다.
- 전체 README parity의 기존 optimization 3개 실패는 이번 변경 범위 밖이며, 변경한
  root·module README는 개별 parity 검증 대상으로 유지한다.

## Future Guidance

다음 QBE consumer도 명시적 CRUD repository와 QBE repository를 분리하고, projection에
필요한 property만 `project`로 선언한다. matcher가 지원하지 않는 ignore-case나
동적 SQL 문자열을 예제에 추가하지 않는다. `Flow`는 cold 특성을 유지하고 bounded
consumer는 `take`로 취소하며, PostgreSQL/R2DBC pool에서 취소 직후 후속 terminal이
실행되는지 회귀 테스트로 남긴다. upstream Flow context 구현이 개선되면 이 예제의
transaction-scoped collection workaround를 다시 검토한다.
