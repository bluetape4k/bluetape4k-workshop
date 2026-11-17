# Chaos Monkey 학생 저장소 Exposed 전환 구현 계획

> **For agentic workers:** 이 계획은 현재 세션의 `refactor/chaos-monkey-exposed` worktree에서 단계별로 실행한다. 각 단계는 RED/GREEN 증거를 남긴 뒤 다음 단계로 이동한다.

**목표:** Chaos Monkey 예제의 Student CRUD를 `JdbcTemplate` raw SQL에서 Exposed DSL로 전환하고 기존 API·H2 bootstrap 계약을 유지한다.

**구조:** Spring Boot가 제공하는 `DataSource`를 명시적인 `Database` bean으로 연결한다. `StudentTable`은 기존 H2 `student` 스키마를 그대로 표현하고, repository는 `transaction(db)` 안에서 DSL CRUD를 수행한다.

**기술 스택:** Kotlin 2.4, Spring Boot 4, Exposed v1 JDBC DSL, H2, JUnit 5, bluetape4k assertions.

---

## 대상 파일과 책임

- 수정: `spring-boot/chaos-monkey/build.gradle.kts` — 기존 catalog alias 기반 Exposed core/JDBC 의존성 추가.
- 생성: `spring-boot/chaos-monkey/src/main/kotlin/io/bluetape4k/workshop/chaos/config/ExposedDatabaseConfig.kt` — Spring `DataSource`에서 `Database` bean 생성.
- 생성: `spring-boot/chaos-monkey/src/main/kotlin/io/bluetape4k/workshop/chaos/model/StudentTable.kt` — `student` 테이블과 실제 컬럼 정의.
- 수정: `spring-boot/chaos-monkey/src/main/kotlin/io/bluetape4k/workshop/chaos/repository/StudentJdbcRepository.kt` — `Database` 주입과 Exposed DSL CRUD.
- 생성: `spring-boot/chaos-monkey/src/test/kotlin/io/bluetape4k/workshop/chaos/repository/StudentJdbcRepositoryTest.kt` — 독립 H2 database의 CRUD·검증 회귀 테스트.
- 수정: `spring-boot/chaos-monkey/README.md`, `README.ko.md` — persistence 경계와 실제 Gradle task 경로 동기화.

`schema.sql`과 `data.sql`은 Spring Boot bootstrap 계약이므로 수정하지 않는다.

## Task 1: 의존성과 RED 테스트 작성

**파일:**

- Modify: `spring-boot/chaos-monkey/build.gradle.kts`
- Create: `spring-boot/chaos-monkey/src/test/kotlin/io/bluetape4k/workshop/chaos/repository/StudentJdbcRepositoryTest.kt`

- [ ] **Step 1: Exposed alias를 의존성에 추가한다.**

  `dependencies`의 bluetape4k 영역에 다음을 추가한다.

  ```kotlin
  implementation(libs.exposed.core)
  implementation(libs.exposed.jdbc)
  ```

  별도 버전이나 개별 bluetape4k BOM을 선언하지 않는다. Spring JDBC starter는
  `DataSource` 자동 구성을 위해 유지한다.

- [ ] **Step 2: 최종 repository 계약을 호출하는 실패 테스트를 작성한다.**

  테스트는 `Database.connect("jdbc:h2:mem:student-repository-<uuid>;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "org.h2.Driver")`로 database를 만들고 `SchemaUtils.create(StudentTable)` 후 `StudentJdbcRepository(database)`를 호출한다. 다음을 검증한다.

  ```kotlin
  @Test
  fun `CRUD preserves student contract`() {
      val inserted = repository.insert(Student(10003, "Ada", "P1234567"))
      inserted shouldBeEqualTo 1
      repository.findById(10003).shouldNotBeNull().name shouldBeEqualTo "Ada"

      repository.update(Student(10003, "Grace", "P7654321")) shouldBeEqualTo 1
      repository.findById(10003).shouldNotBeNull().passportNumber shouldBeEqualTo "P7654321"

      repository.deleteById(10003) shouldBeEqualTo 1
      repository.findById(10003).shouldBeNull()
  }
  ```

  입력 검증 테스트는 `assertFailsWith<IllegalArgumentException>`으로 양수 ID와
  non-blank 필드 계약을 잠근다. 테스트는 `io.bluetape4k.assertions` matcher만
  사용하고 `!!`·safe-call assertion을 사용하지 않는다.

- [ ] **Step 3: RED를 확인한다.**

  Run:

  ```bash
  ./gradlew :spring-boot-chaos-monkey:test --tests '*StudentJdbcRepositoryTest'
  ```

  Expected: `StudentTable`/`Database` 기반 최종 계약이 아직 구현되지 않아
  compile 또는 test 단계에서 실패한다. 실패 로그를 기록하고 구현 단계로 이동한다.

## Task 2: Exposed table·Spring Database wiring 구현

**파일:**

- Create: `spring-boot/chaos-monkey/src/main/kotlin/io/bluetape4k/workshop/chaos/model/StudentTable.kt`
- Create: `spring-boot/chaos-monkey/src/main/kotlin/io/bluetape4k/workshop/chaos/config/ExposedDatabaseConfig.kt`

- [ ] **Step 1: 실제 H2 schema와 일치하는 table을 추가한다.**

  ```kotlin
  object StudentTable: Table("student") {
      val id = integer("id")
      val name = varchar("name", 255)
      val passportNumber = varchar("passport_number", 255)

      override val primaryKey = PrimaryKey(id)
  }
  ```

  `id`는 자동 증가가 아닌 애플리케이션 제공 정수이므로 `integer`를 사용한다.

- [ ] **Step 2: DataSource를 Exposed Database bean으로 연결한다.**

  ```kotlin
  @Configuration(proxyBeanMethods = false)
  class ExposedDatabaseConfig {
      @Bean
      fun database(dataSource: DataSource): Database = Database.connect(dataSource)
  }
  ```

  H2 URL·사용자·driver를 다시 만들지 않고 Spring Boot가 만든 `DataSource`를
  그대로 재사용한다.

## Task 3: repository를 최소 Exposed DSL 구현으로 전환

**파일:** `spring-boot/chaos-monkey/src/main/kotlin/io/bluetape4k/workshop/chaos/repository/StudentJdbcRepository.kt`

- [ ] **Step 1: JdbcTemplate/RowMapper/raw SQL 의존성을 제거한다.**

  생성자 타입을 `Database`로 바꾸고 `ResultRow` mapper를 private 함수로 둔다.
  `findAll`은 `StudentTable.selectAll()`, `findById`는 `where { StudentTable.id eq studentId }`,
  변경 작업은 `insert`, `update`, `deleteWhere`를 사용한다.

- [ ] **Step 2: 기존 입력 검증과 반환값을 보존한다.**

  `requirePositiveNumber`와 `requireNotBlank` 호출을 DSL transaction 바깥의
  진입 검증으로 유지한다. `update`·`deleteById`·`insert`는 영향 행 수를
  반환하고, 없는 `findById`는 `null`을 반환한다.

- [ ] **Step 3: GREEN을 확인한다.**

  Run:

  ```bash
  ./gradlew :spring-boot-chaos-monkey:test --tests '*StudentJdbcRepositoryTest'
  ```

  Expected: CRUD와 validation 테스트가 모두 PASS한다.

## Task 4: 모듈 wiring·문서 검증

**파일:**

- Modify: `spring-boot/chaos-monkey/README.md`
- Modify: `spring-boot/chaos-monkey/README.ko.md`

- [ ] **Step 1: README 두 로케일의 persistence 설명을 갱신한다.**

  `StudentJdbcRepository`를 `StudentExposedRepository`로 잘못 이름 붙이지 않고
  실제 public class인 `StudentJdbcRepository`를 유지하되, 구현이 Exposed DSL과
  H2 `student` table을 사용한다고 설명한다. 영문은 영어, 한국어는 한국어
  기술 문체로 작성한다.

- [ ] **Step 2: 실제 Gradle project path를 사용한다.**

  실행 예시를 `:spring-boot-chaos-monkey:bootRun`과
  `:spring-boot-chaos-monkey:test`로 맞춘다.

- [ ] **Step 3: 문서 read-back과 자연스러움을 확인한다.**

  두 README의 링크·표·code fence·API 경로를 다시 읽고, 변경된 한국어 README에
  대해 다음을 실행한다.

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs spring-boot/chaos-monkey/README.ko.md
  ```

  기존 용어 충돌이 나오면 문맥을 확인한 뒤 필요한 항목만 수정한다.

## Task 5: 대상 모듈 검증

- [ ] **Step 1: targeted test를 fresh 상태로 실행한다.**

  ```bash
  ./gradlew :spring-boot-chaos-monkey:cleanTest :spring-boot-chaos-monkey:test --no-build-cache
  ```

- [ ] **Step 2: compile, static, diff 검사를 실행한다.**

  ```bash
  ./gradlew :spring-boot-chaos-monkey:compileKotlin :spring-boot-chaos-monkey:detekt
  git diff --check
  ```

  `detekt` task가 모듈에 없으면 root `./gradlew detekt` 결과로 대체하지 않고,
  task 목록을 확인해 실제 모듈 static task를 실행한다.

- [ ] **Step 3: 기존 Spring Boot controller test를 함께 확인한다.**

  ```bash
  ./gradlew :spring-boot-chaos-monkey:test --tests '*StudentControllerTest'
  ```

  Expected: 기존 2개 controller test와 repository CRUD test가 모두 PASS한다.

## Task 6: 최종 범위·리뷰·커밋

- [ ] **Step 1: 변경 범위를 점검한다.**

  `git status --short`, `git diff --stat`, `git diff --check`로 대상 파일 외
  변경이 없는지 확인하고 `rg`로 repository에 `JdbcTemplate`, `RowMapper`,
  raw SQL이 남지 않았는지 확인한다.

- [ ] **Step 2: 최종 Kotlin/Exposed checklist를 적용한다.**

  transaction 경계, operator import, `ResultRow` 매핑, validation, README
  locale parity, compile/test diagnostics를 검토한다. P0/P1 이슈가 있으면
  수정 후 affected test를 다시 실행한다.

- [ ] **Step 3: Lore 형식으로 로컬 커밋한다.**

  커밋 메시지는 한국어 intent line과 `Constraint`, `Rejected`, `Confidence`,
  `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailer를 포함한다. PR/merge는
  이번 요청 범위가 아니므로 수행하지 않는다.

## 위험·롤백·검증 연결

- 의존성/컴파일 실패: Task 1 RED 로그를 기준으로 `build.gradle.kts`와 import를
  되돌리고 alias 해석을 재확인한다.
- H2 컬럼 불일치: `schema.sql`과 `StudentTable`을 대조하고 repository test에서
  실제 insert/read를 재실행한다.
- Spring context wiring 실패: `ExposedDatabaseConfig`를 DataSource 주입
  단일 bean으로 유지하고 controller test를 재실행한다.
- 문서 drift: 두 README의 동일한 API·구현 설명과 task path를 diff로 대조한다.

## 계획 검토

- SPW-01: 대상 독자, 현재 source anchors, 명령어와 식별자를 고정했다.
- SPW-02: 파일, 순서, RED/GREEN, 검증, 롤백, PR 제외 경계를 포함했다.
- SPW-03: 한국어 계획의 기술 문체와 code token 보존을 검토했다.
- SPW-04: 설계 문서의 acceptance criteria가 모든 task와 명령어로 연결된다.
- SPW-05: 계획 전체를 read-back해 placeholder와 순서 오류가 없음을 확인했다.
