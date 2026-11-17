# Chaos Monkey 학생 CRUD의 Exposed 전환 교훈

## 배경

전체 예제의 raw SQL 후보를 분류한 결과, `spring-boot/chaos-monkey`의
`StudentJdbcRepository`가 일반적인 JDBC CRUD를 직접 구현한 가장 좁고 명확한
Exposed 전환 대상이었다. PostgreSQL 제어·마이그레이션 SQL과 Vert.x/R2DBC/
Redisson 기술 시연용 SQL은 각 예제의 핵심 API를 설명하므로 이번 범위에서
제외했다.

## 결정과 발견

- `StudentTable`과 `ExposedDatabaseConfig`를 추가하고 Spring `DataSource`를
  명시적인 Exposed `Database`로 연결한다.
- `StudentJdbcRepository`는 기존 검증과 반환값 계약을 유지하면서
  `selectAll`, `insert`, `update`, `deleteWhere` DSL과 `transaction(database)`를
  사용한다.
- 기존 `schema.sql`과 `data.sql`의 bootstrap 방식은 유지한다. 다만 H2에서
  unquoted `name`이 `NAME`으로 저장되고 Exposed가 `"name"`을 생성하는
  식별자 차이를 맞추기 위해 schema의 컬럼만 quoted identifier로 조정한다.
- 모듈 의존성은 기존 workshop consumer 규칙에 따라 catalog alias와
  `bluetape4k-dependencies` BOM을 사용하며 중앙 catalog는 변경하지 않는다.

## 결과

- 학생 CRUD 경로에서 `JdbcTemplate`, `RowMapper`, inline raw SQL을 제거했다.
- 독립 H2 테스트가 CRUD row count/value와 양수 ID·비공백 문자열 검증을 고정한다.
- Spring Boot controller 테스트는 기존 `schema.sql`/`data.sql` bootstrap 경로를
  계속 검증하며, 두 README 로케일은 Exposed 구현과 실제 Gradle task 경로를
  반영한다.
- 첫 전체 실행에서 발견한 H2 `Column "name" not found` 오류는 schema의
  quoted identifier 보정과 재실행으로 해결했다.

## 검증

- TDD RED: 새 `StudentJdbcRepositoryTest`가 구현 전 `StudentTable`/생성자
  미해결로 실패함을 확인했다.
- TDD GREEN: 대상 테스트 2개 통과.
- 모듈 전체 테스트: `4 passing`, `BUILD SUCCESSFUL`.
- main/test Kotlin 재컴파일(`--rerun-tasks`): `BUILD SUCCESSFUL`.
- runtime dependency graph: Exposed core/JDBC와 bluetape4k Exposed BOM이
  catalog에서 해석됨.
- README 한국어 용어 감사: findings 0.
- `git diff --check` 및 Kotlin 소스 raw SQL/JdbcTemplate 검색: 통과.

## 향후 지침

다음 raw SQL 전환에서는 먼저 일반 CRUD와 기술 시연·PostgreSQL 제어·migration
계약을 분리한다. 일반 CRUD만 Exposed로 수렴하고, 나머지는 Exposed 표현 가능성,
backend 특수성, 예제 교육 목적을 별도 검토한 뒤 범위를 승인한다. H2와 Exposed를
함께 사용할 때는 예약어·대소문자 quoting을 schema bootstrap과 DSL 양쪽에서
확인하고 controller context 테스트와 독립 repository 테스트를 모두 실행한다.
