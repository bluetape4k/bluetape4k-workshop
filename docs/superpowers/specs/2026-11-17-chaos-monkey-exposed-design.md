# Chaos Monkey 학생 저장소 Exposed 전환 설계

## 문제와 목표

`spring-boot/chaos-monkey`의 `StudentJdbcRepository`는 일반적인 학생 CRUD를
`JdbcTemplate`과 인라인 SQL 문자열로 구현한다. 이 모듈은 예제 저장소이므로,
동일한 JDBC 접근을 Exposed DSL로 표현해 테이블·조회·변경 경계를 코드에서
확인할 수 있게 한다.

이번 변경의 목표는 다음과 같다.

- `StudentJdbcRepository`에서 `JdbcTemplate`과 raw SQL을 제거한다.
- 기존 `Student` 모델, repository 메서드 시그니처, 입력 검증, H2 테이블 이름과
  컬럼 이름을 유지한다.
- Spring Boot가 만든 `DataSource`를 명시적인 Exposed `Database` 빈에 연결하고,
  각 repository 작업을 `transaction(db)` 안에서 실행한다.
- 영문·한국어 README가 실제 persistence 경계를 설명하도록 맞춘다.

## 범위와 제외

### 포함

- `StudentTable` 정의와 `StudentJdbcRepository`의 Exposed DSL CRUD
- `DataSource`와 Exposed `Database`를 연결하는 Spring 설정
- repository의 H2 기반 CRUD 회귀 테스트
- Exposed가 생성하는 quoted identifier와 맞추기 위한 H2 `schema.sql`의
  `name` 컬럼 quoting 조정
- Chaos Monkey README 두 로케일의 구현 설명과 Gradle task 경로
- 모듈 의존성에 기존 catalog alias 기반의 Exposed JDBC 모듈 추가

### 제외

- `schema.sql`과 `data.sql`의 H2 bootstrap 방식 교체(단, `name` 식별자 quoting
  조정은 Exposed/H2 호환을 위해 포함)
- `commerce`·`operations`의 PostgreSQL 제어/마이그레이션 SQL
- R2DBC, Vert.x SQL client, Redisson 등 기술 시연 목적의 다른 예제
- 중앙 `bluetape4k-dependencies` catalog 변경
- PR 생성, merge, branch 삭제, release 또는 외부 시스템 변경

## 현재 근거

- `StudentJdbcRepository`는 `JdbcTemplate.query/update`로 `student` 테이블을
  직접 읽고 쓴다.
- `schema.sql`은 `student(id, name, passport_number)`를 생성하며 `id`는
  애플리케이션이 전달하는 정수 primary key다.
- H2는 unquoted `name`을 대문자 `NAME`으로 저장하지만 Exposed는 예약어 충돌을
  피하려고 `"name"`을 생성하므로 bootstrap schema도 동일하게 quoting해야 한다.
- `spring-boot/multi-tenant-data-isolation`과 `exposed/mvc-virtualthread`는
  `org.jetbrains.exposed.v1.*` DSL과 명시적인 `Database` 연결을 사용한다.
- workshop 루트는 `bluetape4k-dependencies` BOM을 적용하고, catalog의
  `libs.exposed.core`·`libs.exposed.jdbc` alias를 제공한다.
- 현재 `StudentService`에는 여러 repository 호출을 묶는 `@Transactional` 경계가
  없으므로 각 repository 메서드가 소유하는 transaction을 사용한다. Spring
  transaction manager와의 다중 호출 atomicity는 이번 좁은 예제 전환의
  비목표다.

## 선택한 구조

1. `StudentTable : Table("student")`에 `id`, `name`, `passportNumber` 컬럼을
   H2 schema의 실제 이름과 동일하게 정의한다.
2. `ExposedDatabaseConfig`가 Spring `DataSource`를 받아
   `Database.connect(dataSource)`를 한 번 생성해 bean으로 제공한다.
3. `StudentJdbcRepository`가 `Database`를 주입받고, 모든 DSL 작업을
   `transaction(db)` 안에서 수행한다. `ResultRow` 변환은 private mapper로
   한 곳에 둔다.
4. 기존의 양수 ID·비어 있지 않은 문자열 검증은 repository 진입점에 그대로
   남긴다. CRUD 반환값은 기존 JDBC update count와 동일하게 유지한다.
5. H2 bootstrap schema의 `name` 컬럼은 `"name"`으로 선언해 Exposed가 생성하는
   quoted identifier와 일치시킨다. 테스트는 별도 H2 memory database와
   `SchemaUtils.create(StudentTable)`를
   사용해 실제 repository entrypoint를 호출한다. 운영 bootstrap SQL의
   동작은 기존 Spring Boot controller test가 계속 확인한다.

## 대안과 거부 이유

| 대안 | 판단 |
|---|---|
| `JdbcTemplate` 유지 | raw SQL 제거라는 변경 목적을 충족하지 못한다. |
| Exposed Spring Boot starter의 암묵적 `Database` 자동 구성만 사용 | 예제에서 연결 생명주기와 테스트용 database 선택이 드러나지 않아 학습 경계가 흐려진다. |
| 명시적 `Database` bean + Exposed DSL | 기존 Spring `DataSource`를 재사용하면서 연결 경계를 소스에서 확인할 수 있어 선택한다. |

## 실패 모드와 대응

- `DataSource` 연결이 없으면 `Database` bean 생성 또는 첫 transaction에서
  실패한다. 모듈 context test가 이를 조기에 드러낸다.
- 테이블/컬럼 이름이 `schema.sql`과 달라지면 H2 CRUD가 실패한다. table
  정의와 repository test가 동일한 실제 컬럼명을 사용한다.
- H2 예약어/식별자 case가 달라지면 Spring context가 시작되지 않는다. schema와
  Exposed table의 quoted identifier를 함께 검증한다.
- `findById`가 없는 행을 조회하면 기존과 같이 `null`을 반환한다.
- insert/update/delete가 영향을 준 행 수를 0으로 반환하는 경우도 기존 JDBC
  계약대로 그대로 반환한다.
- 입력 검증이 누락되면 기존 `require*` 예외 계약이 바뀌므로, 회귀 테스트에서
  양수 ID와 non-blank 필드를 함께 확인한다.

## 수용 기준

- repository 소스에 `JdbcTemplate`, `RowMapper`, raw SQL 문자열이 없다.
- `findAll`, `findById`, `insert`, `update`, `deleteById`가 기존 모델과 반환
  계약을 유지하며 Exposed DSL transaction으로 동작한다.
- 현재 caller가 요구하지 않는 Spring `@Transactional` 다중 호출 경계는
  범위 밖임을 문서화하고, 예외 타입 변환 호환성은 보장하지 않는다.
- repository H2 CRUD 테스트와 기존 controller 테스트가 통과한다.
- Chaos Monkey README 두 로케일이 실제 class 이름인
  `StudentJdbcRepository`의 Exposed DSL 경계를 설명하고 실제 project path
  task를 사용한다.
- 변경된 Kotlin 코드에 unresolved import/deprecation 진단이 없고,
  `git diff --check`가 통과한다.

## 완료 정의

RED 테스트 실패를 확인한 뒤 최소 구현으로 GREEN을 만들고, 모듈 test·compile
및 static 검사를 실행한다. 최종 diff에는 이 설계 범위 밖의 파일이 없어야
하며, PR/merge는 별도 사용자 요청이 없으므로 수행하지 않는다.

## 설계 문서 검토

- SPW-01: 현재 repository, schema, sibling Exposed patterns와 대상 독자를
  고정했다.
- SPW-02: 범위, 구조, 대안, 실패 모드, 수용 기준, DoD를 포함했다.
- SPW-03: 한국어 기술 문체와 API·명령어·식별자 보존을 검토했다.
- SPW-04: local source anchors와 README/module build 계약을 대조했다.
- SPW-05: 문서를 처음부터 끝까지 읽어 구조와 명령어를 재확인했다.
