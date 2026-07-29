# 이벤트 소스 사용량 청구 마이크로서비스 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: use `executing-plans` inline task-by-task. The approved user constraint forbids subagent dispatch for this worktree. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** PostgreSQL소유 상태와 Kafka최소 1회 전달 전반에 걸쳐  #552/#553 청구 동작을 유지하는 5개의 독립적으로 배포 가능한 Java 25개 Spring Boot 서비스를 구축합니다.

**아키텍처:** 미터, 사용량, 청구, 송장 및 쿼리는 독립적인 PostgreSQL 데이터베이스가 있는 물리적 형제 Gradle 모듈입니다. 생산자는 로컬 상태와 Exposed 발신함을 원자적으로 유지합니다. 소비자는 Kafka 진행을 커밋하기 전에 서비스 로컬 Exposed 받은 편지함, 집계 버전 정책 및 격리를 사용합니다. 구성 모듈은 프로덕션 런타임 코드를 소유하지 않으며 5개의 애플리케이션 컨텍스트와 1개의 Kafka 브로커 전반에 걸쳐 블랙박스 동작을 확인합니다.

**기술 스택:** Kotlin 2.4, Java 25, Spring Boot 4 MVC, Spring Kafka 4, PostgreSQL, JetBrains Exposed, `bluetape4k-exposed-jdbc`, `bluetape4k-kafka4`, Bluetape logging/validation/UUID/Micrometer/JUnit/assertions/Testcontainers, Jackson 3, Gradle/Kover/Detekt.

---

## 배송 규칙

- 루트 `bluetape4k-dependencies` BOM은 유일한 Bluetape 버전 권한입니다. 개인 없음
  Bluetape BOM 또는 버전 핀이 추가되었습니다.
- 모든 구체적인 지속성 클래스는 로컬 `ExposedJdbcRepository` 기반을 구현합니다. Exposed DAO/DSL,
  `SchemaUtils`, 서비스 포트 및 저장소는 프로덕션 및 설비의 유일한 데이터베이스 경로입니다.
- `JdbcTemplate`, `java.sql.*`, `DriverManager`, `PreparedStatement`, `Statement`, `Transaction.exec`,
  `Connection` 및 원시 마이그레이션 SQL은 6개의 새 모듈 디렉토리 모두에서 금지됩니다.
- Kafka은 운송입니다. 소유 서비스의 PostgreSQL은 정확성 권한입니다. 아니요 XA, 공유 데이터베이스,
  서비스 간 테이블 읽기 또는 종단 간 정확히 한 번 클레임이 도입되었습니다.
- 컨테이너 테스트는 `--max-workers=1`을 사용하여 단일 Gradle 프로세스에서 실행됩니다. 기본 테스트에서는 다음을 제외합니다.
  `integration` 태그이며 컨테이너가 없는 상태로 유지되어야 합니다.
- 각 커밋은 저장소 Lore 커밋 프로토콜을 따릅니다. 여기에 PR을 생성하거나 푸시하거나 병합하지 마세요.
  구현 계획; 각각은 별도의 사용자 게이트입니다.

## 모듈 및 패키지 맵

| Gradle 프로젝트 | 패키지 루트 | 책임 |
|---|---|---|
| `:commerce-usage-billing-meter-service` | `io.bluetape4k.workshop.commerce.usagebilling.meter` | meter/price 권한 및 보낼 편지함 |
| `:commerce-usage-billing-usage-service` | `io.bluetape4k.workshop.commerce.usagebilling.usage` | 사용법 receipt/acceptance 및 보낼 편지함 |
| `:commerce-usage-billing-billing-service` | `io.bluetape4k.workshop.commerce.usagebilling.billing` | 가격 증거, 등급, 불변 charge/adjustment 권한 |
| `:commerce-usage-billing-invoice-service` | `io.bluetape4k.workshop.commerce.usagebilling.invoice` | 불변 invoice/correction 문서 권한 |
| `:commerce-usage-billing-query-service` | `io.bluetape4k.workshop.commerce.usagebilling.query` | customer/operator 읽기 모델, 체크포인트, 검역 가시성 |
| `:commerce-usage-billing-microservices-composition-tests` | `io.bluetape4k.workshop.commerce.usagebilling.composition` | 테스트 전용 HTTP/event Fixture 및 Kafka/PostgreSQL 구성 검증 |

각 런타임 모듈은 봉투 디코더, table/entity/repository 유형, DTO, 구성 및
애플리케이션 메인 클래스. 구성 모듈은 JSON 샘플과 HTTP 시나리오 검증문을 공유할 수 있지만
프로덕션 jar을 게시하거나 서비스의 런타임 종속성이 아니어야 합니다.

## 작업 1: 6개의 모듈을 등록하고 소비자 빌드 표면을 잠급니다.

**파일:**
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/build.gradle.kts`
- 생성: `commerce/usage-billing-microservices-composition-tests/build.gradle.kts`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/resources/junit-platform.properties`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/resources/logback-test.xml`
- 생성: `commerce/usage-billing-microservices-composition-tests/src/test/resources/junit-platform.properties`
- 수정: `README.md`, `README.ko.md`, `commerce/README.md`, `commerce/README.ko.md`

- [ ] **1단계: 새 프로젝트가 없는지 확인합니다.**

  실행: `./gradlew projects --console=plain | rg 'usage-billing-(meter|usage|billing|invoice|query)-service'`

  예상: directories/build 파일이 존재하기 전에 일치하는 항목이 없습니다.

- [ ] **2단계: Java 25개 Spring Boot 모듈 빌드 파일을 추가합니다.**

  모든 런타임 빌드에는 Kotlin Spring, Spring Boot, Detekt, Kover가 적용됩니다. Java/Kotlin 25로 설정; 구성하다
  `springBoot.mainClass`; `compileOnly`/`runtimeOnly`에서 `testImplementation`을 확장합니다. 수입만 가능
  버전이 없는 카탈로그 별칭.  #553 `verifyExecution`을 재사용하고, 뮤텍스를 테스트하고, 비어 있지 않은 XML를 테스트하고,
  `test`/`integrationTest` 분리. 컴포지션 빌드는 테스트 전용이며 5가지 서비스 모두에 따라 다릅니다.
  테스트 런타임용 프로젝트이며 `integration` 태그가 지정된 `integrationTest` 작업이 있습니다.

  ```kotlin
  java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
  kotlin { jvmToolchain(25); compilerOptions.jvmTarget.set(JvmTarget.JVM_25) }

  tasks.test {
      useJava25Runtime()
      useJUnitPlatform { excludeTags("integration") }
  }
  ```

- [ ] **3단계: 최소한의 리소스와 README 등록을 추가합니다.**

  각 런타임 모듈은 명명된 서비스와 함께 `application.yml`을 가져오고 테스트에서 자동 시작 작업자를 비활성화합니다.
  PostgreSQL 데이터 소스 자리 표시자, Kafka 소비자 `enable-auto-commit=false` 및 불변
  `@ConfigurationProperties` 기본값입니다. 루트 및 상거래 English/Korean README는 그룹을 다음과 같이 식별합니다.
  "5개의 독립적으로 배포 가능한 Spring Boot 서비스; PostgreSQL + Kafka (Testcontainers)".

- [ ] **4단계: 프로젝트 그래프 및 빈 테스트 계약 증명**

  실행: `./gradlew projects :commerce-usage-billing-meter-service:test :commerce-usage-billing-microservices-composition-tests:test --console=plain`

  예상: 6개 프로젝트 경로가 모두 해결됩니다. 각 런타임 모듈에는 Java 25개의 툴체인 구성이 있으며
  컴퍼지션 프로젝트는 `integrationTest` 작업을 해결합니다.

- [ ] **5단계: 격리된 모듈 경계를 커밋합니다.**

  커밋 의도: `Make billing service ownership a build-time boundary`.

## 작업 2: 테스트 전용 블랙박스 계약 및 서비스 부팅 스캐폴딩 정의

**파일:**
- 생성: `commerce/usage-billing-microservices-composition-tests/src/testFixtures/kotlin/.../UsageBillingMicroserviceContract.kt`
- 생성: `commerce/usage-billing-microservices-composition-tests/src/testFixtures/kotlin/.../UsageBillingScenario.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../*Application.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../ApplicationArchitectureTest.kt`

- [ ] **1단계: 실패한 계약 형태 테스트 작성.**

  ```kotlin
  interface UsageBillingMicroserviceContract {
      fun activatePrice(tenantId: String, meterCode: String, amount: BigDecimal): ContractHttpResult
      fun ingestUsage(tenantId: String, sourceEventId: String, occurredAt: Instant): ContractHttpResult
      fun closePeriod(tenantId: String, periodId: UUID): ContractHttpResult
      fun issueInvoice(tenantId: String, periodId: UUID): ContractHttpResult
      fun postCorrection(tenantId: String, sourceEventId: String): ContractHttpResult
      fun totals(tenantId: String): ContractBillingTotals
  }

  data class ContractHttpResult(val status: Int, val headers: Map<String, String>, val body: String)
  data class ContractBillingTotals(val chargeTotal: BigDecimal, val adjustmentTotal: BigDecimal)
  ```

- [ ] **2단계: 계약 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-microservices-composition-tests:test --tests '*Contract*' --console=plain`

  예상: FAIL 왜냐하면 설비 및 서비스 애플리케이션이 존재하지 않기 때문입니다.

- [ ] **3단계: 5개의 애플리케이션 메인 및 패키지 방향 가드를 추가합니다.**

  각 애플리케이션에는 자체 `@SpringBootApplication` 스캔 루트만 있습니다. 경비원은 다른 제품의 수입을 거부합니다.
  서비스 패키지, 구성 패키지, Spring/Exposed/Jackson 순수 도메인 패키지 `!!`, `println`,
  광범위한 예외 삼키기 및 원시 데이터베이스 API.

  ```kotlin
  @SpringBootApplication
  class MeterServiceApplication

  fun main(args: Array<String>) = runApplication<MeterServiceApplication>(*args)
  ```

- [ ] **4단계: 경계 컴파일 증명.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test --console=plain`

  예상: 런타임 모듈당 하나 이상의 아키텍처 테스트가 있는 PASS.

- [ ] **5단계: 계약 전용 공유 규칙을 커밋합니다.**

  커밋 의도: `Keep integration compatibility separate from service internals`.

## 작업 3: 독립적으로 버전이 지정된 통합 엔벨로프 구현

**파일:**
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../integration/IntegrationEnvelope.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../integration/EnvelopeCodecRegistry.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../integration/EnvelopeCodecRegistryTest.kt`
- 생성: `commerce/usage-billing-microservices-composition-tests/src/testFixtures/resources/contracts/{v1,v2}/*.json`

- [ ] **1단계: 먼저 서비스별 디코더 테스트를 작성합니다.**

  유효한 v1, 호환 가능한 v2 추가 필드, 공백 tenant/type, 유효하지 않은 UUID, 음수 집계 포함
  버전, 다이제스트 불일치, 알 수 없는 선택 필드, 알 수 없는 필수 스키마. 마지막 경우는 반드시
  `null`이 아닌 형식화된 `UnsupportedEnvelopeVersion`을 반환합니다.

  ```kotlin
  @Test
  fun `unknown mandatory schema is rejected without decoding payload`() {
      assertFailsWith<UnsupportedEnvelopeVersion> {
          registry.decode(fixture("usage-accepted-v99.json"))
      }
  }
  ```

- [ ] **2단계: 한 서비스의 RED 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-billing-service:test --tests '*EnvelopeCodecRegistryTest' --console=plain`

  예상: 코덱 레지스트리가 없기 때문에 FAIL입니다.

- [ ] **3단계: 로컬 envelope/codec 구현을 추가합니다.**

  `eventId`, `eventType`, `schemaVersion`, tenant/aggregate identity/version와 함께 서비스 로컬 유형을 사용합니다.
  causation/correlation, 생산자, 타임스탬프, 페이로드 및 SHA-256 페이로드 다이제스트. 디코드 검증
  도메인 매핑보다 우선합니다. 레지스트리 키는 명시적인 `(eventType, schemaVersion)` 쌍입니다. 첨가제 v2
  서비스-로컬 호환성 어댑터를 통해 디코딩됩니다.

- [ ] **4단계: Kafka 키 및 JSON 호환성 어설션을 추가합니다.**

  `partitionKey()`은(는) 정확히 `"$tenantId|$aggregateType|$aggregateId"`를 반환해야 합니다. 계약 체결 검증문
  다섯 가지 서비스 모두 구독한 이벤트 유형만 구문 분석할 수 있으며 공유 런타임 클래스는 필요하지 않습니다.

- [ ] **5단계: 모든 봉투 테스트 및 감지를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test detekt --console=plain`

  예상: PASS.

- [ ] **6단계: 독립적인 봉투 진화를 커밋합니다.**

  커밋 의도: `Version service messages without a shared runtime model`.

## 작업 4: Exposed 전용 지속성 기반 및 데이터베이스 아키텍처 가드 추가

**파일:**
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../persistence/*ExposedJdbcRepository.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../persistence/*Tables.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../persistence/*Entities.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../persistence/RepositoryArchitectureTest.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../persistence/*DatabaseFixture.kt`

- [ ] **1단계: 저장소 및 원시-API 가드를 작성합니다.**

  ```kotlin
  @Test
  fun `every concrete repository implements ExposedJdbcRepository`() {
      repositories.all(ExposedJdbcRepository::class.java::isAssignableFrom) shouldBe true
  }

  @Test
  fun `persistence sources contain no raw sql or jdbc escape hatch`() {
      forbiddenDatabaseTokens().forEach { token ->
          kotlinSourcesUnder(moduleRoot).any { it.readText().contains(token) } shouldBe false
      }
  }
  ```

  금지 목록에는 `JdbcTemplate`, `DriverManager`, `java.sql.`, `PreparedStatement`,
  `createStatement`, `Transaction.exec`, `exec(`, `Connection`.

- [ ] **2단계: 측정기 저장소 가드를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test --tests '*RepositoryArchitectureTest' --console=plain`

  예상: FAIL 저장소 base/types이(가) 존재하기 전입니다.

- [ ] **3단계: Exposed를 통해 각 로컬 베이스와 테이블을 추가합니다.**

  ```kotlin
  abstract class MeterExposedJdbcRepository<E : Entity<ID>, ID : Any>(domainClass: Class<E>) :
      ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
          ExposedEntityInformationImpl(domainClass),
      )
  ```

  Exposed 테이블을 통해 audit/tenant 열을 정의합니다. 비즈니스 ID, 이벤트 ID에 고유 인덱스를 사용합니다.
  그리고 스트림 버전. 설비 스키마 설정은 `SchemaUtils.create`을 사용하고 정리는 repository/table DSL를 사용합니다.
  오직.

- [ ] **4단계: 아키텍처 및 PostgreSQL 고정 장치 시작 증명.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test --console=plain`

  예상: PASS; 기본 테스트는 컨테이너를 시작하지 않습니다.

- [ ] **5단계: 지속성 경계를 커밋합니다.**

  커밋 의도: `Keep service databases behind Exposed repository contracts`.

## 작업 5: 빌드 미터 권한, 명령 수신 및 로컬 트랜잭션 발신함

**파일:**
- 생성: `commerce/usage-billing-meter-service/src/main/kotlin/.../domain/MeterCommands.kt`
- 생성: `commerce/usage-billing-meter-service/src/main/kotlin/.../domain/MeterEvents.kt`
- 생성: `commerce/usage-billing-meter-service/src/main/kotlin/.../application/MeterCommandService.kt`
- 생성: `commerce/usage-billing-meter-service/src/main/kotlin/.../persistence/{Meter,PriceVersion,CommandReceipt,Outbox}Repository.kt`
- 생성: `commerce/usage-billing-meter-service/src/main/kotlin/.../web/MeterController.kt`
- 생성: `commerce/usage-billing-meter-service/src/test/kotlin/.../{application,persistence,web}/Meter*Test.kt`

- [ ] **1단계: 실패한 측정기 명령 테스트를 작성합니다.**

  가격 활성화가 불변 버전과 `PriceActivated` 발신함 행을 원자적으로 생성한다는 것을 증명하세요. 같은
  멱등성 key/payload은 터미널 HTTP 응답을 재생합니다. 동일한 key/different 지문이 409를 반환합니다.
  거래가 실패하면 가격 버전이나 보낼 편지함 행이 남지 않습니다.

- [ ] **2단계: RED HTTP 및 트랜잭션 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test --tests '*MeterCommandServiceTest' --tests '*MeterControllerTest' --console=plain`

  예상: FAIL 명령 서비스와 엔드포인트가 없기 때문입니다.

- [ ] **3단계: 변경할 수 없는 미터 명령 경로를 구현합니다.**

  하나의 `SpringTransactionManager` 트랜잭션에서 acquire/fence 로컬 명령 영수증, 가격 추가
  버전, 봉투 다이제스트 및 파티션 키를 사용하여 `PENDING` 발신함 이벤트를 작성한 다음 터미널을 유지합니다.
  영수증. 조건부 업데이트에는 영수증 소유자 토큰 및 상태가 포함됩니다. 기존 가격 버전은 절대
  업데이트 또는 삭제되었습니다.

- [ ] **4단계: PostgreSQL 원자성 증명을 추가합니다.**

  `MeterOutboxPostgresIntegrationTest`을 `integration`로 태그 지정; 가격 사이에 통제된 실패를 주입
  쓰기 및 수신이 완료되고 롤백 후 price/outbox 개수가 모두 0으로 유지된다고 검증문합니다.

- [ ] **5단계: 집중 경로와 통합 경로를 확인합니다.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-meter-service:integrationTest --max-workers=1 --console=plain`

  예상: 두 작업 모두에서 비어 있지 않은 XML이 있는 PASS입니다.

- [ ] **6단계: 현지 가격 권한 커밋.**

  커밋 의도: `Make price activation durable before asynchronous publication`.

## 작업 6: 사용량 멱등성, 가격 증거 받은 편지함 및 사용량 보낸 편지함 구축

**파일:**
- 생성: `commerce/usage-billing-usage-service/src/main/kotlin/.../domain/{UsageCommands,UsageEvents}.kt`
- 생성: `commerce/usage-billing-usage-service/src/main/kotlin/.../application/{UsageCommandService,PriceEvidenceService}.kt`
- 생성: `commerce/usage-billing-usage-service/src/main/kotlin/.../persistence/{Usage,PriceEvidence,CommandReceipt,Inbox,Outbox}Repository.kt`
- 생성: `commerce/usage-billing-usage-service/src/main/kotlin/.../web/UsageController.kt`
- 생성: `commerce/usage-billing-usage-service/src/test/kotlin/.../{application,persistence,web}/*Test.kt`

- [ ] **1단계: 실패한 사용 행동 테스트 작성.**

  커버 승인 소스 이벤트, 동일 소스 이벤트 중복, 충돌 소스 이벤트, HTTP 멱등성
  replay/conflict, 명시적 도메인 rejection/defer 정책에 대한 가격 증거 누락 및 `UsageAccepted`
  동일한 커밋의 보낼 편지함 행.

- [ ] **2단계: RED 사용법 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-usage-service:test --tests '*UsageCommandServiceTest' --console=plain`

  예상: acceptance/repository 포트가 없기 때문에 FAIL입니다.

- [ ] **3단계: 로컬 사용 상태 및 인바운드 가격 증거를 구현합니다.**

  `PriceEvidenceService`은 `(tenantId,eventId)`에 고유한 로컬 받은 편지함을 통해 삽입하고 다이제스트를 확인하며,
  불변의 가격 증거를 저장합니다. `UsageCommandService`은 소스 고유성과 로컬 명령을 소유합니다.
  영수증; 사용량 데이터베이스 증거만 읽고 미터 테이블은 읽지 않습니다.

- [ ] **4단계: 중복을 추가하고 지속성 테스트를 다시 시작합니다.**

  새로운 애플리케이션 컨텍스트는 지속된 터미널 receipt/inbox 행을 읽고 두 번째를 생성하지 않아야 합니다.
  사용법 event/outbox 행. Exposed 저장소 검사만 사용하십시오.

- [ ] **5단계: 사용량 확인.**

  실행: `./gradlew :commerce-usage-billing-usage-service:test :commerce-usage-billing-usage-service:integrationTest --max-workers=1 --console=plain`

  예상: PASS.

- [ ] **6단계: 지속 가능한 사용 승인을 커밋합니다.**

  커밋 의도: `Make usage retries safe before they reach billing`.

## 작업 7: Billing price/usage 받은 편지함 정책 및 추가 전용 평가 권한 구축

**파일:**
- 생성: `commerce/usage-billing-billing-service/src/main/kotlin/.../domain/{BillingPeriod,Charge,Adjustment}*.kt`
- 생성: `commerce/usage-billing-billing-service/src/main/kotlin/.../application/{BillingInboxService,BillingCloseService,CorrectionService}.kt`
- 생성: `commerce/usage-billing-billing-service/src/main/kotlin/.../persistence/{PricingEvidence,UsageInbox,BillingPeriod,Charge,Adjustment,Outbox}Repository.kt`
- 생성: `commerce/usage-billing-billing-service/src/main/kotlin/.../worker/DeferredInboxWorker.kt`
- 생성: `commerce/usage-billing-billing-service/src/test/kotlin/.../{application,persistence,worker}/*Test.kt`

- [ ] **1단계: 실패한 집계 버전 정책 테스트를 작성합니다.**

  표지는 다음 버전 예정 -> `APPLIED`; 하위 version/same 다이제스트 -> `DUPLICATE`; 같은 이벤트 ID/different
  다이제스트 -> `QUARANTINED`; 향후 버전 -> `DEFERRED`; 가격 증거 누락 -> `DEFERRED`; 다시 해 보다
  예산 소진 -> `QUARANTINED` 안정된 이유.

- [ ] **2단계: 정책 RED 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-billing-service:test --tests '*BillingInboxServiceTest' --console=plain`

  예상: 받은편지함 상태 유형 및 서비스가 존재하지 않기 때문에 FAIL입니다.

- [ ] **3단계: 로컬 받은 편지함 claim/CAS 및 추가 전용 요금을 구현합니다.**

  행 상태, 청구 소유자 토큰, 청구 기한, 예상 집계 버전 및 조건부 Exposed 사용
  술어를 업데이트합니다. `APPLIED` 사용 이벤트는 하나의 불변 요금과 하나의 `ChargeRated` 발신함을 생성합니다.
  받은 편지함 completion/checkpoint과 동일한 거래에서 이벤트가 진행됩니다. 일반 update/delete 메소드
  charge/adjustment 저장소가 차단되었습니다.

- [ ] **4단계: 보상으로 수정을 구현합니다.**

  `CorrectionService`은 원래 현지 청구 출처를 찾고 debit/credit `AdjustmentPosted`을 추가합니다.
  하나의 보낼 편지함 이벤트를 대기열에 추가합니다. 사용량, 요금, 사전 조정 또는 송장 테이블은 업데이트되지 않습니다.

- [ ] **5단계: PostgreSQL race/recovery 테스트를 추가합니다.**

  PostgreSQL에 대해 `MultithreadingTester`을 사용하여 하나의 받은 편지함 이벤트에 대한 두 개의 클레임을 경쟁합니다. 하나가 적용되었다고 검증문
  효과. 청구되었지만 만료된 행으로 다시 시작하고 새 소유자가 해당 행을 한 번 완료했다고 검증문합니다. 확인
  지연된 행은 predecessor/pricing 증거가 승인된 후에만 적용됩니다.

- [ ] **6단계: 청구 확인.**

  실행: `./gradlew :commerce-usage-billing-billing-service:test :commerce-usage-billing-billing-service:integrationTest --max-workers=1 --console=plain`

  예상: PASS.

- [ ] **7단계: 추가 전용 금융 권한을 커밋합니다.**

  커밋 의도: `Preserve rated charges across delayed billing events`.

## 작업 8: 송장 소비자 및 변경 불가능한 문서 계보 구축

**파일:**
- 생성: `commerce/usage-billing-invoice-service/src/main/kotlin/.../domain/{Invoice,InvoiceLine,InvoiceCorrection}.kt`
- 생성: `commerce/usage-billing-invoice-service/src/main/kotlin/.../application/InvoiceInboxService.kt`
- 생성: `commerce/usage-billing-invoice-service/src/main/kotlin/.../persistence/{Invoice,InvoiceLine,Inbox,Outbox}Repository.kt`
- 생성: `commerce/usage-billing-invoice-service/src/main/kotlin/.../web/InvoiceController.kt`
- 생성: `commerce/usage-billing-invoice-service/src/test/kotlin/.../*Invoice*Test.kt`

- [ ] **1단계: 실패한 송장 테스트 작성.**

  표지 `ChargeRated`은(는) 청구 이벤트 출처가 포함된 하나의 송장 라인을 생성하고, 중복 납품은 생성합니다.
  추가 라인 없음, `AdjustmentPosted` 새 수정 document/line 생성 및 최종 송장
  공개 저장소 방법을 통해 행을 업데이트하거나 삭제할 수 없습니다.

- [ ] **2단계: 송장 RED 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-invoice-service:test --tests '*InvoiceInboxServiceTest' --console=plain`

  예상: 소비자와 저장소가 없기 때문에 FAIL입니다.

- [ ] **3단계: 송장 로컬 inbox/effect 거래를 구현합니다.**

  청구 이벤트 디코드, insert/claim 받은 편지함, 변경 불가능한 invoice/document 계보 작성 및 선택 사항
  `InvoiceIssued`/`InvoiceCorrectionIssued` 하나의 Exposed 거래에서 보낼 편지함 이벤트가 발생한 후 받은 편지함을 완료합니다.
  청구 데이터베이스를 읽거나 청구 금액을 다시 계산하지 마십시오.

- [ ] **4단계: 테넌트 추가 및 수정 HTTP 테스트.**

  고객 송장 쿼리에는 일치하는 인증된 테넌트가 필요합니다. 운전자 수정 가시성이 필요함
  `ROLE_OPERATOR`. 원본 청구서가 다음 이후에도 변경되지 않고 byte-for-byte/value-for-value 유지된다고 검증문
  보정.

- [ ] **5단계: 송장을 확인합니다.**

  실행: `./gradlew :commerce-usage-billing-invoice-service:test :commerce-usage-billing-invoice-service:integrationTest --max-workers=1 --console=plain`

  예상: PASS.

- [ ] **6단계: 변경 불가능한 송장 소비를 커밋합니다.**

  커밋 의도: `Materialize invoices without taking billing authority`.

## 작업 9: 쿼리 예측, 운영자 복구, 메트릭 및 보안 구축

**파일:**
- 생성: `commerce/usage-billing-query-service/src/main/kotlin/.../application/{QueryInboxService,ProjectionRebuildService,QuarantineRedriveService}.kt`
- 생성: `commerce/usage-billing-query-service/src/main/kotlin/.../persistence/{ReadModel,Inbox,Checkpoint,Quarantine}Repository.kt`
- 생성: `commerce/usage-billing-query-service/src/main/kotlin/.../config/{QueryProperties,QueryMetrics,QueryHealthIndicator,SecurityConfiguration}.kt`
- 생성: `commerce/usage-billing-query-service/src/main/kotlin/.../web/{QueryController,OperatorRecoveryController}.kt`
- 생성: `commerce/usage-billing-query-service/src/test/kotlin/.../{application,config,web}/*Test.kt`

- [ ] **1단계: 실패한 Query/operator 테스트를 작성합니다.**

  커버 프로젝션 중복 제거, 프로젝션별 체크포인트 진행은 읽기 모델 돌연변이, 프로젝션 지연,
  알 수 없는 봉투 격리, 재드라이브 감사, 고객 테넌트 거부, 운영자 역할 거부 및
  카디널리티가 낮은 측정항목 태그.

- [ ] **2단계: 쿼리 RED 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-query-service:test --tests '*QueryInboxServiceTest' --tests '*OperatorRecoveryControllerTest' --console=plain`

  예상: FAIL 쿼리 projection/recovery 엔드포인트가 없기 때문입니다.

- [ ] **3단계: 투영 및 연산자 경계를 구현합니다.**

  `QueryInboxService`은(는) 받은 편지함 결과, 로컬 프로젝션 변형 및 체크포인트를 하나로 유지합니다.
  Exposed 거래. `OperatorRecoveryController`은 읽기 전용 backlog/oldest-age/reason 요약을 노출합니다.
  행위자, 시도, old/new 상태 및 상관관계 ID를 기록하는 명시적인 재드라이브 명령이 있습니다. 그것
  재무 상태를 변경할 수 없습니다.

- [ ] **4단계: Micrometer/health/configuration 테스트를 추가합니다.**

  설계에서 정확한 미터 이름과 태그 키를 확인하십시오. tenant/event/payload 태그를 거부합니다. 건강
  격리 또는 가장 오래된 백로그가 구성된 경계를 넘을 때 표시기 보고서 성능이 저하됨
  소비자 상쇄를 재정적 진실로 취급합니다.

- [ ] **5단계: 쿼리를 확인합니다.**

  실행: `./gradlew :commerce-usage-billing-query-service:test :commerce-usage-billing-query-service:integrationTest --max-workers=1 --console=plain`

  예상: PASS.

- [ ] **6단계: 관찰 가능한 복구 경로를 커밋합니다.**

  커밋 의도: `Expose asynchronous billing recovery without cross-service writes`.

## 작업 10: 잘못된 EOS 클레임 없이 로컬 발신함 게시자와 Kafka 수신기 연결

**파일:**
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice}-service/src/main/kotlin/.../messaging/OutboxPublisher.kt`
- 생성: `commerce/usage-billing-{usage,billing,invoice,query}-service/src/main/kotlin/.../messaging/*KafkaListener.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../config/KafkaMessagingConfiguration.kt`
- 생성: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../messaging/*Test.kt`

- [ ] **1단계: 실패한 메시징 상태 머신 테스트를 작성합니다.**

  `PENDING -> CLAIMED -> PUBLISHED` 테스트, 전송 실패 -> `RETRY_WAIT`, 청구 만료 -> 재청구,
  소진 재시도 -> `QUARANTINED`, 게시 표시 전 브로커 승인 후 충돌 시뮬레이션
  -> 두 번째 전송 및 지속성 있는 받은 편지함 결과 이후에만 청취자가 확인합니다.

- [ ] **2단계: Meter outbox RED 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test --tests '*OutboxPublisherTest' --console=plain`

  예상: 게시자 상태 시스템이 없기 때문에 FAIL입니다.

- [ ] **3단계: 보낼 편지함 게시자 구현.**

  Exposed 조건부 update/lease 필드를 사용하여 제한된 순서의 페이지를 요청하고 다음과 같이 보냅니다.
  `KafkaTemplate<String, String>` `envelope.partitionKey()`을 사용하여 조건부로 게시됨으로 표시합니다.
  생산자와 PostgreSQL을 XA과 같은 트랜잭션으로 포장하지 말고 이를 정확하게 설명하지 마세요.
  한 번. Kafka 전송 성공은 금융 권한으로 지속되지 않습니다.

- [ ] **4단계: 리스너 및 오류 매핑 구현.**

  `enable-auto-commit=false` 구성, 리스너 전달 녹음 및 manual/container-managed 오프셋
  로컬 받은 편지함 서비스가 지속 가능한 terminal/deferred/quarantine 결과를 ​​반환한 후에만 진행됩니다.
  재전송에 대한 일시적인 database/broker 실패 발생; 영구 디코드 실패가 지속됩니다.
  그러면 격리가 정상적으로 돌아옵니다. Kafka `Consumer` position/offset mutation API를 직접 호출하지 마세요.

- [ ] **5단계: 리스너 구성 및 모듈 테스트를 확인합니다.**

  실행: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test --console=plain`

  예상: PASS.

- [ ] **6단계: 전송 경계 커밋.**

  커밋 의도: `Treat Kafka delivery as replayable transport rather than financial proof`.

## 작업 11: 컴포지션 픽스처 및 필수 Kafka/PostgreSQL 시나리오 구현

**파일:**
- 생성: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../fixture/UsageBillingMicroserviceFixture.kt`
- 생성: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../fixture/KafkaFailureController.kt`
- 생성: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../UsageBillingMicroserviceCompositionIntegrationTest.kt`
- 생성: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../{Duplicate,Ordering,Poison,Restart,SchemaEvolution,Outage,TenantIsolation,Parity,Correction}IntegrationTest.kt`

- [ ] **1단계: 첫 번째 교차 서비스 RED 시나리오를 작성합니다.**

  ```kotlin
  @Tag("integration")
  @Test
  fun `committed price survives publish failure and is delivered after recovery`() = runSuspendIO {
      fixture.blockTopic("meter.events.v1")
      fixture.activatePrice(tenant, meterCode, amount).status shouldBe HttpStatus.CREATED
      fixture.outboxBacklog("meter") shouldBe 1
      fixture.unblockTopic("meter.events.v1")
      await untilAsserted { fixture.priceEvidence(tenant, meterCode) shouldNotBe null }
  }
  ```

- [ ] **2단계: 컴포지션 RED 테스트를 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-microservices-composition-tests:integrationTest --tests '*CompositionIntegrationTest' --no-build-cache --max-workers=1 --console=plain`

  예상: fixture/service 토폴로지가 없기 때문에 FAIL입니다.

- [ ] **3단계: 격리된 인프라 및 컨텍스트를 시작합니다.**

  Fixture는 하나의 `KafkaContainer` 및 5개의 서비스별 `PostgreSQLServer` 실행기를 시작한 다음 시작됩니다.
  고유한 데이터 소스 URL, 소비자 그룹 ID 및 임의의 HTTP 포트가 있는 5개의 애플리케이션 컨텍스트.
  Kafka 관리자 API를 통해 주제를 생성하고 seeds/observes은 HTTP/service ports/Exposed를 통해서만 생성합니다.
  저장소. JDBC 연결을 얻지 못하거나 다른 서비스 데이터베이스에 액세스하지 않습니다.

- [ ] **4단계: 필요한 모든 통합 증명을 추가합니다.**

  다음에 대해 별도의 태그가 지정된 테스트를 구현합니다.

  1. 게시 실패 후 나중에 전달됩니다.
  2. 중복 레코드 전달은 하나의 받은 편지함 outcome/financial 효과를 제공합니다.
  3. delayed/reordered 이벤트가 `DEFERRED` 다음에 `APPLIED` 다음에 발생하거나 명시적으로 격리됩니다.
  4. 포이즌 이벤트는 독립적인 집계가 진행되고 운영자가 재드라이브 감사하는 동안 하나의 집계를 격리합니다.
  5. 애플리케이션 컨텍스트 재시작 복구 만료 claim/inbox/outbox/checkpoint;
  6. v1/v2 호환성 및 지원되지 않는 필수 버전 격리;
  7. Kafka 중단 backlog/recovery 커밋된 명령이 손실되지 않습니다.
  8. 교차 테넌트 command/event/query 거부;
  9. #552/#553 블랙박스 합계, 송장 및 조정 패리티;
  10. 서비스 간 수정은 원본 charge/invoice을 불변으로 유지하고 정확히 하나의 보상 결과를 추가합니다.

- [ ] **5단계: 제한된 동시성 및 정리 어설션을 추가합니다.**

  모든 테스트는 결정론적 테스트 clock/IDs, 제한된 Awaitility, `finally` 컨텍스트 닫기 및 어설션을 사용합니다.
  유출된 청구 outbox/inbox 행이 없습니다. Race 테스트는 임시 실행기가 아닌 `MultithreadingTester`을 사용합니다.

- [ ] **6단계: 새로운 구성 확인을 실행합니다.**

  실행: `./gradlew :commerce-usage-billing-microservices-composition-tests:cleanIntegrationTest :commerce-usage-billing-microservices-composition-tests:integrationTest --no-build-cache --max-workers=1 --console=plain`

  예상: 10개의 명명된 시나리오 클래스가 모두 있고 비어 있지 않은 JUnit XML이 있는 PASS.

- [ ] **7단계: 분산 정확성 증명을 커밋합니다.**

  커밋 의도: `Prove billing outcomes survive asynchronous service failures`.

## 작업 12: 다이어그램, README 의사 결정 가이드 및 생성된 시각적 검증 추가

**파일:**
- 생성: `commerce/usage-billing-microservices/README.md`
- 생성: `commerce/usage-billing-microservices/README.ko.md`
- 생성: `scripts/generate-usage-billing-microservices-diagrams.mjs`
- 생성: `scripts/validate-usage-billing-microservices-readme.mjs`
- 생성: `docs/images/readme-diagrams/usage-billing-microservices-{architecture,outbox-inbox-state,delivery,poison-recovery,correction,extraction}-01.{svg,png}`
- 수정: `README.md`, `README.ko.md`, `commerce/README.md`, `commerce/README.ko.md`

- [ ] **1단계: 실패한 README 유효성 검사기 사례를 작성합니다.**

  두 로케일 파일, 여섯 개의 다이어그램 모두, 서비스 소유권 테이블, no-XA/no-exactly-once 문이 필요합니다.
  모듈식 단일체 비교, extraction/rollback 경로, 실행 명령 및 모든 module/workflow 링크.

- [ ] **2단계: 유효성 검사기 RED 실행.**

  실행: `node scripts/validate-usage-billing-microservices-readme.mjs`

  예상: README 및 자산이 없기 때문에 FAIL입니다.

- [ ] **3단계: `bluetape-diagram`부터 SVG 및 CairoSVG PNG 소스를 생성합니다.**

  생성기는 직접 endpoint/tangent 화살표 다각형, 둥근 직교 연결선을 방출합니다.
  커넥터 겹침, CairoSVG 변경되는 유니코드 문자 없음, 내장된 글꼴 및 안정적인 뷰박스.
  아키텍처 라벨은 영어로 유지됩니다. 한국어 README에서 다이어그램을 설명합니다.

- [ ] **4단계: 결정 및 운영 가이드를 작성합니다.**

   #552, #553 또는 #555가 언제 적합한지 설명하세요. Kafka이 최소 한 번 전송되는 이유는 무엇입니까? 각 서비스의 DB을 명시하세요.
  권한; 문서 duplicate/delay/poison/redrive 동작; 단계적 Meter/Usage 제공 -> 청구 ->
  Invoice/Query 추출, 패리티 드레인 기준 및 경로 전용 롤백.

- [ ] **5단계: 전체 diagram/readme QA 실행.**

  실행: `node scripts/generate-usage-billing-microservices-diagrams.mjs && node scripts/validate-usage-billing-microservices-readme.mjs && ./scripts/smoke-validate.sh diagram-qa`

  예상: SVG 구조 감사용 PASS, 커넥터 비겹침, 직접 헤드 형상, SVG/PNG 화살표
  방향 패리티, 전체 크기 PNG 육안 검사 매니페스트, README link/locale 확인.

- [ ] **6단계: 실행 가능한 운영 문서를 커밋합니다.**

  커밋 의도: `Explain service recovery before readers operate the example`.

## 작업 13: smoke/full 워크플로, Kover 아티팩트, 오래된 검사, 강의 및 리뷰 등록

**파일:**
- 수정: `scripts/smoke-validate.sh`
- 수정: `.github/workflows/Examples.yml`
- 수정: `.github/workflows/nightly.yml`
- 생성: `docs/lessons/2026-07-22-issue-555-usage-billing-microservices.md`
- 생성: `docs/review/2026-07-22-issue-555-usage-billing-microservices-plan-review.md`
- 생성: `docs/review/2026-07-22-issue-555-usage-billing-microservices-implementation-review.md`

- [ ] **1단계: 실패한 workflow/static 어설션을 작성합니다.**

  README 유효성 검사기 또는 전용 노드 테스트를 확장하여 5개의 기본 `:test` 작업이 모두
  연기 레인, 구성 `:integrationTest` 및 `:koverXmlReport`이 순차 컨테이너에 나타납니다.
  야간 차선 및 예상되는 XML/Kover 경로는 아티팩트 verification/upload 블록에 존재합니다.

- [ ] **2단계: 워크플로 가드 RED 실행.**

  실행: `node scripts/validate-usage-billing-microservices-readme.mjs && ./scripts/smoke-validate.sh stale-check`

  예상: 모든 필수 등록이 추가될 때까지 FAIL.

- [ ] **3단계: smoke/full 등록을 업데이트합니다.**

  예제 연기 및 구성 integration/Kover에 5개의 런타임 `:test` 작업을 컨테이너에 추가하고
  야간 순차 명령. 정확한 result/report 경로, 설명 주석, `commerce` 연기 추가
  그룹 명령 및 오래된 검색 기대치를 확인합니다. Testcontainers 테스트를 매일 수행하지 마세요.
  연기.

- [ ] **4단계: 수업 내용을 기록하고 6개 렌즈 구현 검토를 수행합니다.**

  한국어 수업에서는 지역 권한, 중복 경계, 중독, 롤백 규율, Exposed만 다룹니다.
  고정 규칙 및 다이어그램 렌더러 증명. 구현 검토에서는 정확한 최종 헤드를 기록하고,
  6가지 검토 렌즈, 테스트 commands/counts, 다이어그램 증거 및 해결되지 않은 위험이 모두 포함됩니다.

- [ ] **5단계: 영향을 받은 workflow/docs 검사를 실행합니다.**

  실행: `./scripts/smoke-validate.sh commerce && ./scripts/smoke-validate.sh stale-check && git diff --check`

  예상: PASS에는 새로운 모듈 이름이 모두 포함되고 오래된 링크는 없습니다.

- [ ] **6단계: 저장소 통합 체인을 커밋합니다.**

  커밋 의도: `Keep distributed billing validation visible in repository automation`.

## 작업 14: 최종 검증, 위험 검토 및 전달 증거

**파일:**
- 수정: `docs/review/2026-07-22-issue-555-usage-billing-microservices-implementation-review.md`
- 수정: `docs/lessons/2026-07-22-issue-555-usage-billing-microservices.md`

- [ ] **1단계: 새로운 대상 모듈 확인을 실행합니다.**

  달리다:

  ```bash
  ./gradlew \
    :commerce-usage-billing-meter-service:cleanTest :commerce-usage-billing-meter-service:test \
    :commerce-usage-billing-usage-service:cleanTest :commerce-usage-billing-usage-service:test \
    :commerce-usage-billing-billing-service:cleanTest :commerce-usage-billing-billing-service:test \
    :commerce-usage-billing-invoice-service:cleanTest :commerce-usage-billing-invoice-service:test \
    :commerce-usage-billing-query-service:cleanTest :commerce-usage-billing-query-service:test \
    --no-build-cache --console=plain
  ```

  예상: PASS; 실행된 테스트 횟수를 기록하고 기본 Testcontainers 시작을 0으로 설정합니다.

- [ ] **2단계: 새로운 순차 구성 및 보고서 확인을 실행합니다.**

  달리다:

  ```bash
  ./gradlew \
    :commerce-usage-billing-microservices-composition-tests:cleanIntegrationTest \
    :commerce-usage-billing-microservices-composition-tests:integrationTest \
    :commerce-usage-billing-microservices-composition-tests:koverXmlReport \
    --no-build-cache --max-workers=1 --console=plain
  ```

  예상: PASS; 비어 있지 않은 `build/test-results/integrationTest/*.xml`을 검증문하고
  `build/reports/kover/report.xml`.

- [ ] **3단계: 정적 및 시각적 완료 확인을 실행합니다.**

  달리다:

  ```bash
  ./gradlew detekt detektTest --console=plain
  ./scripts/smoke-validate.sh commerce
  ./scripts/smoke-validate.sh stale-check
  ./scripts/smoke-validate.sh diagram-qa
  node scripts/validate-usage-billing-microservices-readme.mjs
  git diff --check
  ```

  예상: PASS.

- [ ] **4단계: 최종 6개 렌즈 인라인 검토를 수행합니다.**

  성능(제한된 page/lag), 안정성(restart/claim/retry), 보안에 대한 정확한 최종 헤드를 다시 확인합니다.
  (tenant/operator/telemetry), Operator/Ops (quarantine/redrive/metrics), developer/API (module/envelope
  compatibility/Exposed 가드) 및 user/caller(idempotency/totals/correction). P0/P1/P2을 녹음하고
  배송 전에 P0/P1마다 수정합니다.

- [ ] **5단계: 모든 확인이 통과된 후에만 최종 로컬 커밋을 생성합니다.**

  커밋 의도: `Demonstrate recoverable billing delivery across service boundaries`.

  지식 예고편에는 no-XA 제약, 거부된 공유 database/EOS 대안, 검증의 이름을 지정해야 합니다.
  명령 및 실행되지 않은 환경 종속 검사.

## 자체 검토 계획

| 디자인 요구 사항 | 계획된 과제 |
|---|---|
| 독립적 배포 가능 서비스 및 DB 소유권 | 작업 1-2, 4-9 |
| Exposed/JDBC-only 저장소 및 설비 | 작업 4와 모든 서비스의 아키텍처 보호 |
| 봉투 진화 및 집계 파티션 키 | 작업 3 |
| 로컬 발신함 및 최소 1회 중복 처리 | 작업 5-10 |
| delay/reorder/poison/restart 정책 | 작업 7, 9-11 |
| 불변의 재정 교정 | 작업 7-8, 11 |
| 연산자 metrics/recovery/security | 작업 9 |
| Testcontainers 필수 시나리오 매트릭스 | 작업 11 |
| diagrams/README/extraction 안내 | 작업 12 |
| 워크플로, 매트릭스, 오래된 검사, lesson/reviews | 작업 13-14 |

금지된 마커 스캔은 적중을 생성하지 않아야 하며 지정되지 않은 동작은 남아 있어서는 안 됩니다. 이름을 입력하세요
작업 3-10에서 소개된 내용은 작업 11-14에서도 일관되게 사용됩니다.
