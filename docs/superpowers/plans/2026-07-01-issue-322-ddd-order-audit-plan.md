# DDD 주문 감사 워크숍 실시 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** Spring Modulith 발행 행 및 JaVers history/diff 쿼리를 사용하여 학습자 대상 PostgreSQL 지원 DDD 집계 수명 주기 예제인 `:spring-modulith-ddd-order-audit`을 빌드합니다.

**아키텍처:** order 명령 트랜잭션은 집계를 유지하고 PostgreSQL에 Spring Modulith 게시 행을 원자적으로 등록합니다. `@ApplicationModuleListener`은 커밋 후 이행을 처리하는 반면, 인메모리 JaVers 감사는 성공적인 트랜잭션 커밋 후에만 기록되므로 롤백 테스트에서 잘못된 감사 기록이 남지 않습니다.

**기술 스택:** Kotlin 2.3, Java 21, Spring Boot 4, Spring Data JPA, Spring Modulith 2.1, JaVers, `bluetape4k-testcontainers` `PostgreSQLServer.Launcher.postgres`, JUnit 5, MockK, bluetape4k 어설션, 생성됨 SVG/PNG README 다이어그램.

---

## 파일 구조

- `spring-modulith/ddd-order-audit/build.gradle.kts`: Gradle 모듈 종속성과 Spring Boot 메인 클래스를 생성합니다.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/DddOrderAuditApplication.kt` 생성: 애플리케이션 진입점.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderDomain.kt` 만들기: 값 개체, 명령, 집계, 도메인 이벤트.
- 낙관적 잠금을 사용하여 `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderEntity.kt`: JPA 엔터티 매핑을 만듭니다.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderJpaRepository.kt` 생성: Spring Data 저장소.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderAuditService.kt` 생성: 커밋 후 JaVers commit/query 경계.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderCommandService.kt` 생성: 트랜잭션 place/approve 사용 사례 및 Spring 이벤트 게시.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/fulfillment/FulfillmentReservation.kt` 생성: 이행 예약 JPA 엔터티.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/fulfillment/FulfillmentReservationRepository.kt` 생성: Spring Data 저장소.
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/fulfillment/FulfillmentReservationHandler.kt`: `@ApplicationModuleListener` 및 결정적 오류 스위치를 만듭니다.
- `spring-modulith/ddd-order-audit/src/main/resources/application.yml`: JPA 스키마, Modulith 이벤트 게시, 로깅 기본값을 생성합니다.
- `spring-modulith/ddd-order-audit/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/AbstractDddOrderAuditTest.kt`: PostgreSQL Testcontainers 설정 및 정리 도우미를 만듭니다.
- `spring-modulith/ddd-order-audit/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/` 아래에 테스트를 만듭니다.
- `spring-modulith/ddd-order-audit/src/test/resources/junit-platform.properties` 및 `logback-test.xml`를 생성합니다.
- `spring-modulith/ddd-order-audit/README.md` 및 `README.ko.md`를 생성합니다.
- `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-architecture-01.{svg,png}`를 생성합니다.
- `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-sequence-01.{svg,png}`를 생성합니다.
- `gradle/libs.versions.toml` 수정: Gradle 종속성 해결을 통해 아티팩트가 루트 BOM를 통해 존재함을 확인한 경우에만 `bluetape4k-javers-ddd = { module = "io.github.bluetape4k.javers:javers-ddd" }`를 추가합니다.
- 루트 `README.md`, `README.ko.md`, `AGENTS.md`, `.github/workflows/Examples.yml` 및 `scripts/smoke-validate.sh`를 수정합니다.

## 작업 1: 모듈 뼈대 및 종속성 해결

**파일:**
- 생성: `spring-modulith/ddd-order-audit/build.gradle.kts`
- 생성: `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/DddOrderAuditApplication.kt`
- 생성: `spring-modulith/ddd-order-audit/src/main/resources/application.yml`
- 수정: `gradle/libs.versions.toml`

- [ ] **1단계: 별칭을 추가하기 전에 `javers-ddd` 아티팩트 해결**

달리다:

```bash
./gradlew dependencyInsight --configuration runtimeClasspath --dependency io.github.bluetape4k.javers:javers-ddd --console=plain
```

예상: Gradle는 루트 `bluetape4k-dependencies` BOM에서 `io.github.bluetape4k.javers:javers-ddd`을 확인합니다. 해결되지 않으면 `bluetape4k-javers-core`와 로컬 커밋 후 감사 코드를 사용하고 커밋하기 전에 구현 참고 사항에 해당 폴백을 기록하세요.

- [ ] **2단계: 모듈 빌드 파일 추가**

`spring-modulith/ddd-order-audit/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.noarg)
    alias(libs.plugins.kotlin.jpa)
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.spring.modulith.ddd.audit.DddOrderAuditApplicationKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(libs.spring.modulith.starter.jpa)
    testImplementation(libs.spring.modulith.starter.test)

    implementation(libs.jakarta.persistence.api)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.hibernate)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.javers.core)
    implementation(libs.javers.core)

    implementation(libs.spring.boot.autoconfigure.lib)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    developmentOnly(libs.spring.boot.devtools)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa.lib)
    implementation(libs.spring.boot.starter.validation)
    runtimeOnly(libs.postgresql.driver)

    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
```

- [ ] **3단계: 애플리케이션 진입점 및 구성 추가**

`DddOrderAuditApplication.kt`:

```kotlin
package io.bluetape4k.workshop.spring.modulith.ddd.audit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DddOrderAuditApplication

fun main(args: Array<String>) {
    runApplication<DddOrderAuditApplication>(*args)
}
```

`application.yml`:

```yaml
spring:
  application:
    name: ddd-order-audit
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
    properties:
      hibernate.format_sql: true
  modulith:
    events:
      republish-outstanding-events-on-restart: false
logging:
  level:
    org.springframework.modulith.events: INFO
```

- [ ] **4단계: 프로젝트 검색 확인**

달리다:

```bash
./gradlew projects --console=plain
```

예상: 출력에는 `Project ':spring-modulith-ddd-order-audit'`이 포함됩니다.

- [ ] **5단계: 커밋**

```bash
git add gradle/libs.versions.toml spring-modulith/ddd-order-audit
git commit -m "feat: add DDD order audit module skeleton"
```

## 작업 2: 도메인 모델 및 집계 테스트

**파일:**
- 생성: `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderDomain.kt`
- 생성: `spring-modulith/ddd-order-audit/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderDomainTest.kt`

- [ ] **1단계: 실패한 집계 테스트 작성**

`OrderDomainTest.kt`은 다음을 포함해야 합니다.

```kotlin
@Test
fun `rejects empty order lines`() {
    assertFailsWith<IllegalArgumentException> {
        Order.place(PlaceOrderCommand(CustomerId("customer-1"), emptyList()))
    }
}

@Test
fun `approve creates new aggregate and domain event`() {
    val order = Order.place(validPlaceOrderCommand())
    val approved = order.approve(ApproveOrderCommand(order.id))

    approved.status shouldBeEqualTo OrderStatus.APPROVED
    approved.events.single() shouldBeInstanceOf OrderApproved::class
    order.status shouldBeEqualTo OrderStatus.PLACED
}

@Test
fun `rejects repeated approve`() {
    val approved = Order.place(validPlaceOrderCommand()).approveForTest()

    assertFailsWith<IllegalStateException> {
        approved.approve(ApproveOrderCommand(approved.id))
    }
}
```

- [ ] **2단계: 변경할 수 없는 도메인 모델 구현**

`OrderDomain.kt`은(는) 직렬화 가능한 value/data 클래스, 새 인스턴스를 반환하는 명령 메서드, 안전한 페이로드가 있는 이벤트를 정의해야 합니다.

```kotlin
data class OrderId(val value: String) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

data class CustomerId(val value: String) : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

data class Money(val amount: BigDecimal, val currency: String = "USD") : Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}

data class Order(
    override val id: OrderId,
    val customerId: CustomerId,
    val lines: List<OrderLine>,
    val status: OrderStatus,
    val version: Long = 0,
    val events: List<DomainEvent> = emptyList(),
) : AggregateRoot<OrderId>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun restore(
            id: OrderId,
            customerId: CustomerId,
            lines: List<OrderLine>,
            status: OrderStatus,
            version: Long,
        ): Order = Order(id, customerId, lines, status, version)
    }
}

data class OrderApproved(
    override val aggregateId: String,
    override val occurredOn: Instant = Instant.now(),
) : DomainEvent, Serializable {
    companion object { private const val serialVersionUID: Long = 1L }
}
```

이벤트는 전체 집계가 아닌 `aggregateId`을 전달해야 합니다.

- [ ] **3단계: 도메인 테스트 실행**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*OrderDomainTest' --console=plain
```

예상: 집계 불변 테스트가 통과되었습니다.

- [ ] **4단계: 커밋**

```bash
git add spring-modulith/ddd-order-audit/src/main/kotlin spring-modulith/ddd-order-audit/src/test/kotlin
git commit -m "feat: model audited order aggregate"
```

## 작업 3: PostgreSQL 지속성 및 트랜잭션 게시

**파일:**
- 생성: `orders/OrderEntity.kt`
- 생성: `orders/OrderJpaRepository.kt`
- 생성: `orders/OrderCommandService.kt`
- 생성: `AbstractDddOrderAuditTest.kt`
- 생성: `OrderCommandServiceTest.kt`

- [ ] **1단계: 실패한 PostgreSQL 지원 서비스 테스트 쓰기**

테스트 베이스는 확인된 도우미를 사용해야 합니다.

```kotlin
companion object : KLogging() {
    val postgres = PostgreSQLServer.Launcher.postgres

    @JvmStatic
    @DynamicPropertySource
    fun postgresProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { postgres.jdbcUrl!! }
        registry.add("spring.datasource.username") { postgres.username!! }
        registry.add("spring.datasource.password") { postgres.password!! }
    }
}
```

테스트는 다음을 검증문해야 합니다.

- 주문을 하면 주문 행이 생성됩니다.
- 주문을 승인하면 동일한 트랜잭션에 `OrderApproved` 게시 행이 등록됩니다.
- 트랜잭션 롤백에는 주문 행과 게시 행이 남지 않습니다.
- 반복 승인이 거부되거나 중복된 유효 승인이 생성되지 않습니다.

- [ ] **2단계: JPA 엔터티 및 저장소 구현**

`OrderEntity`에는 다음이 포함되어야 합니다.

```kotlin
@Entity
@Table(name = "orders")
class OrderEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: String = "",

    @Column(name = "customer_id", nullable = false)
    var customerId: String = "",

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: OrderStatus = OrderStatus.PLACED,
) {
    fun toDomain(lines: List<OrderLine>): Order =
        Order.restore(
            id = OrderId(id),
            customerId = CustomerId(customerId),
            lines = lines,
            status = status,
            version = version,
        )
}
```

바인딩된 Spring Data/JPA API만 사용하세요. SQL/JPQL 문자열을 연결하지 마세요.

- [ ] **3단계: 명령 서비스 구현**

`OrderCommandService`은(는) `@Transactional`이어야 하며 주문을 유지한 다음 트랜잭션이 완료되기 전에 `ApplicationEventPublisher.publishEvent(OrderApproved(aggregateId = order.id.value))`를 호출하세요. 이를 통해 Spring Modulith는 리스너 부작용이 커밋 후에도 유지되는 동안 주문 행으로 발행 행을 작성할 수 있습니다.

- [ ] **4단계: 지속성 테스트 실행**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*OrderCommandServiceTest' --console=plain --max-workers=1
```

예상: PostgreSQL 테스트 컨테이너는 `PostgreSQLServer.Launcher.postgres`을 통해 시작됩니다. 모든 서비스 테스트를 통과했습니다.

- [ ] **5단계: 커밋**

```bash
git add spring-modulith/ddd-order-audit
git commit -m "feat: persist orders with transactional Modulith publications"
```

## 작업 4: 이행 리스너, 실패 및 재생

**파일:**
- 생성: `fulfillment/FulfillmentReservation.kt`
- 생성: `fulfillment/FulfillmentReservationRepository.kt`
- 생성: `fulfillment/FulfillmentReservationHandler.kt`
- 생성: `FulfillmentPublicationTest.kt`

- [ ] **1단계: 리스너 테스트 작성**

테스트에서는 Spring Modulith 2.1 API를 사용해야 합니다.

```kotlin
@Autowired lateinit var incompletePublications: IncompleteEventPublications
@Autowired lateinit var failedPublications: FailedEventPublications
@Autowired lateinit var eventPublicationRepository: EventPublicationRepository
```

검증문:

- 성공적인 승인은 결국 정확히 하나의 예약을 생성합니다.
- 구성된 핸들러 실패로 인해 `EventPublication.Status.FAILED` 또는 불완전한 게시 증거가 남습니다.
- `failedPublications.resubmit(ResubmissionOptions.defaults().withMaxInFlight(1).withBatchSize(1))` 또는 `incompletePublications.resubmitIncompletePublications { it.event is OrderApproved }`은 실패 스위치가 비활성화된 후 예약을 생성합니다.
- 중복 재생은 `orderId`이 고유하므로 중복 예약을 생성하지 않습니다.

- [ ] **2단계: 핸들러 구현**

`FulfillmentReservationHandler`:

```kotlin
@Component
class FulfillmentReservationHandler(
    private val reservations: FulfillmentReservationRepository,
    private val failureSwitch: FulfillmentFailureSwitch,
) {
    @ApplicationModuleListener
    fun on(event: OrderApproved) {
        if (failureSwitch.failNext(event.aggregateId)) {
            throw IllegalStateException("fulfillment failed for orderId=${event.aggregateId}")
        }
        reservations.save(FulfillmentReservation(orderId = event.aggregateId))
    }
}
```

예외 메시지에는 `orderId`만 포함되어야 합니다. 이벤트 본문이나 집계 스냅샷을 덤프하면 안 됩니다.

- [ ] **3단계: 리스너 테스트 실행**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*FulfillmentPublicationTest' --console=plain --max-workers=1
```

예상: 청취자 성공, 실패 증거 및 중복 예약 없이 재생 패스.

- [ ] **4단계: 커밋**

```bash
git add spring-modulith/ddd-order-audit
git commit -m "feat: handle Modulith fulfillment publications"
```

## 작업 5: JaVers 커밋 후 감사 및 쿼리 테스트

**파일:**
- 생성: `orders/OrderAuditService.kt`
- 생성: `OrderAuditServiceTest.kt`

- [ ] **1단계: 감사 테스트 작성**

테스트는 다음을 검증문해야 합니다.

- 주문을 하면 커밋 후 하나의 JaVers 스냅샷이 기록됩니다.
- 승인하면 두 번째 스냅샷과 유용한 차이점이 기록됩니다.
- 롤백은 실패한 명령에 대해 JaVers snapshot/diff을 남기지 않습니다.
- 감사 DTO는 합성 ids/status/amounts만 노출합니다.

- [ ] **2단계: 커밋 후 감사 경계 구현**

`TransactionSynchronizationManager.registerSynchronization` 사용:

```kotlin
fun commitAfterTransaction(author: String, order: Order, properties: Map<String, String>) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                javers.commit(author, order, properties)
            }
        })
    } else {
        javers.commit(author, order, properties)
    }
}
```

이렇게 하면 인메모리 JaVers이 롤백된 명령을 기록하는 것을 방지할 수 있습니다.

- [ ] **3단계: 감사 테스트 실행**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*OrderAuditServiceTest' --console=plain --max-workers=1
```

예상: 스냅샷, 차이점 및 감사 없는 롤백 테스트를 통과했습니다.

- [ ] **4단계: 커밋**

```bash
git add spring-modulith/ddd-order-audit
git commit -m "feat: record rollback-safe JaVers order audit"
```

## 작업 6: 문서 및 다이어그램

**파일:**
- 생성: `spring-modulith/ddd-order-audit/README.md`
- 생성: `spring-modulith/ddd-order-audit/README.ko.md`
- 생성: repo 패턴에서 사용되는 다이어그램 source/generator 파일.
- 생성: `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-architecture-01.{svg,png}`
- 생성: `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-sequence-01.{svg,png}`

- [ ] **1단계: 문서를 편집하기 전에 다이어그램 및 블로그 기술 로드**

`bluetape4k-diagram` 및 `bluetape4k-blog` 스킬 파일을 완전히 읽으세요. 모범 사례 색상 팔레트, 투명한 대체 블록, 중앙에 있는 카드 텍스트, 일치하는 arrowhead/line 색상, 둥근 직교 커넥터, 공식 아이콘, SVG+PNG 패리티 및 전체 크기 PNG 시각 검사를 포함한 전체 sequence/architecture 체크리스트를 적용합니다.

- [ ] **2단계: README 콘텐츠 작성**

README에는 다음이 포함되어야 합니다.

- 언어 스위치.
- 아키텍처 및 시퀀스 PNG는 SVG 형제와 함께 포함됩니다.
- PostgreSQL/Testcontainers 전제조건 및 명령.
- 도메인 이벤트, Spring Modulith 게시, 트랜잭션 발신함 및 JaVers 감사를 비교하는 표입니다.
- audit/event 페이로드는 내구성 있는 레코드이므로 비밀을 포함해서는 안 된다는 경고입니다.
- 정확한 검증 명령:

```bash
./gradlew :spring-modulith-ddd-order-audit:test --console=plain --max-workers=1
```

- [ ] **3단계: 다이어그램 생성 및 시각적 QA 실행**

`bluetape4k-diagram`에서 선택한 다이어그램 생성기 명령을 실행한 후 다음을 수행합니다.

```bash
./scripts/smoke-validate.sh diagram-qa
```

예상되는:

- SVG XML 유효성 검사가 통과되었습니다.
- PNG 파일은 SVG 화살표 방향을 렌더링하고 일치시킵니다.
- 전체 크기 PNG 육안 검사를 통해 라벨 중복 없음, 투명한 대체 블록, 중앙 카드, 올바른 레이어 그룹화, 공식 PostgreSQL 아이콘 및 둥근 직교 커넥터를 확인합니다.

- [ ] **4단계: 커밋**

```bash
git add spring-modulith/ddd-order-audit/README.md spring-modulith/ddd-order-audit/README.ko.md docs/images/readme-diagrams
git commit -m "docs: explain DDD order audit workshop flow"
```

## 작업 7: 리포지토리 등록 및 CI

**파일:**
- 수정: `README.md`
- 수정: `README.ko.md`
- 수정: `AGENTS.md`
- 수정: `.github/workflows/Examples.yml`
- 수정: `scripts/smoke-validate.sh`

- [ ] **1단계: 루트 모듈 테이블 업데이트**

Architecture Extensions 아래에 다음 영어 행을 추가합니다.

```markdown
| Advanced | [`spring-modulith-ddd-order-audit`](spring-modulith/ddd-order-audit/) | `javers`, `testcontainers` | PostgreSQL (TC) | DDD aggregate lifecycle with Modulith publication rows and JaVers audit history |
```

이에 상응하는 한국어를 추가합니다.

```markdown
| Advanced | [`spring-modulith-ddd-order-audit`](spring-modulith/ddd-order-audit/) | `javers`, `testcontainers` | PostgreSQL (TC) | Modulith publication row와 JaVers audit history로 배우는 DDD aggregate lifecycle |
```

- [ ] **2단계: CI/smoke 등록 업데이트**

`.github/workflows/Examples.yml` 경로 필터와 `container-examples` command/artifacts을 `spring-modulith/ddd-order-audit`로 업데이트하세요.

`scripts/smoke-validate.sh` 업데이트:

- `:spring-modulith-ddd-order-audit:test`을 `data-access-full`에 추가합니다.
- `expected=91`을 `expected=92`로 변경하세요.

- [ ] **3단계: 등록 확인**

```bash
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
actionlint .github/workflows/Examples.yml
```

예상: 프로젝트 수는 92개, 오래된 README 참조 없음, 워크플로 구문이 통과됩니다.

- [ ] **4단계: 커밋**

```bash
git add README.md README.ko.md AGENTS.md .github/workflows/Examples.yml scripts/smoke-validate.sh
git commit -m "build: register DDD order audit workshop"
```

## 작업 8: 최종 확인, 검토, PR 및 병합 게이트

**파일:**
- 변경된 모든 파일.

- [ ] **1단계: Kotlin 확인 실행**

```bash
./gradlew :spring-modulith-ddd-order-audit:compileKotlin :spring-modulith-ddd-order-audit:compileTestKotlin --warning-mode all --console=plain
./gradlew :spring-modulith-ddd-order-audit:test --warning-mode all --console=plain --max-workers=1
```

예상: 컴파일 오류 없음, 해결되지 않은 지원 중단 경고 없음, 모든 테스트 통과.

- [ ] **2단계: 저장소 확인 실행**

```bash
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh data-access-full
./scripts/smoke-validate.sh diagram-qa
actionlint .github/workflows/Examples.yml
git diff --check
```

예상: 모든 명령이 통과됩니다. `data-access-full`이(가) 로컬 실행에 너무 느린 경우 최소한 새 모듈 테스트를 실행하고 PR 앞의 간격을 기록하십시오.

- [ ] **3단계: 열기 전에 PR 본문 메타데이터를 검토하세요**

이슈 #322 메타데이터 사용:

- 담당자: `debop`
- 이정표: `1.3.1`
- 라벨: `documentation`, `enhancement`, `difficulty:advanced`, `area:data-access`, `area:serialization-messaging`, `area:architecture-extension`

PR 본문의 마지막 섹션은 정확히 다음과 같아야 합니다.

```markdown
## DoD Status
```

- [ ] **4단계: PR을 열고 라이브 메타데이터 확인**

```bash
gh pr create --base develop --head feat/issue-322-ddd-order-audit --assignee debop --title "feat: add DDD order audit workshop" --body-file /tmp/issue-322-pr.md
gh pr view --json number,title,assignees,milestone,labels,body
```

예상: 라이브 PR 메타데이터 미러 이슈 #322 및 PR 본문이 `## DoD Status`으로 끝납니다.

## 자체 검토

- 사양 적용 범위: 모듈, PostgreSQL Testcontainers, 트랜잭션 결합 게시 행, 커밋 후 리스너, 롤백 안전 JaVers, 재생, 문서, 다이어그램, CI 및 PR 메타데이터가 포함됩니다.
- 자리 표시자 검사: 이 계획은 정확한 경로, 명령 및 API 이름을 사용합니다. 유일한 조건 분기는 필수 명령과 기록된 결정 지점이 있는 `javers-ddd`에 대한 명시적인 종속성 해결 폴백입니다.
- 유형 일관성: 패키지 접두사는 `io.bluetape4k.workshop.spring.modulith.ddd.audit`입니다. 도메인 유형은 `OrderId`, `CustomerId`, `Money`, `Order`, `OrderPlaced` 및 `OrderApproved`을 일관되게 사용합니다.
