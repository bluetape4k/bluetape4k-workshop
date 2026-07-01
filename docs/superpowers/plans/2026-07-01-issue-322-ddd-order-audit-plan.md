# DDD Order Audit Workshop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `:spring-modulith-ddd-order-audit`, a learner-facing PostgreSQL-backed DDD aggregate lifecycle example with Spring Modulith publication rows and JaVers history/diff queries.

**Architecture:** The order command transaction persists the aggregate and registers the Spring Modulith publication row atomically in PostgreSQL. `@ApplicationModuleListener` handles fulfillment after commit, while in-memory JaVers audit is written only after successful transaction commit so rollback tests cannot leave misleading audit history.

**Tech Stack:** Kotlin 2.3, Java 21, Spring Boot 4, Spring Data JPA, Spring Modulith 2.1, JaVers, `bluetape4k-testcontainers` `PostgreSQLServer.Launcher.postgres`, JUnit 5, MockK, bluetape4k assertions, generated SVG/PNG README diagrams.

---

## File Structure

- Create `spring-modulith/ddd-order-audit/build.gradle.kts`: Gradle module dependencies and Spring Boot main class.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/DddOrderAuditApplication.kt`: application entrypoint.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderDomain.kt`: value objects, commands, aggregate, domain events.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderEntity.kt`: JPA entity mapping with optimistic lock.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderJpaRepository.kt`: Spring Data repository.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderAuditService.kt`: after-commit JaVers commit/query boundary.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderCommandService.kt`: transactional place/approve use cases and Spring event publication.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/fulfillment/FulfillmentReservation.kt`: fulfillment reservation JPA entity.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/fulfillment/FulfillmentReservationRepository.kt`: Spring Data repository.
- Create `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/fulfillment/FulfillmentReservationHandler.kt`: `@ApplicationModuleListener` and deterministic failure switch.
- Create `spring-modulith/ddd-order-audit/src/main/resources/application.yml`: JPA schema, Modulith event publication, logging defaults.
- Create `spring-modulith/ddd-order-audit/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/AbstractDddOrderAuditTest.kt`: PostgreSQL Testcontainers setup and cleanup helpers.
- Create tests under `spring-modulith/ddd-order-audit/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/`.
- Create `spring-modulith/ddd-order-audit/src/test/resources/junit-platform.properties` and `logback-test.xml`.
- Create `spring-modulith/ddd-order-audit/README.md` and `README.ko.md`.
- Create `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-architecture-01.{svg,png}`.
- Create `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-sequence-01.{svg,png}`.
- Modify `gradle/libs.versions.toml`: add `bluetape4k-javers-ddd = { module = "io.github.bluetape4k.javers:javers-ddd" }` only if Gradle dependency resolution confirms the artifact exists through the root BOM.
- Modify root `README.md`, `README.ko.md`, `AGENTS.md`, `.github/workflows/Examples.yml`, and `scripts/smoke-validate.sh`.

## Task 1: Module Skeleton And Dependency Resolution

**Files:**
- Create: `spring-modulith/ddd-order-audit/build.gradle.kts`
- Create: `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/DddOrderAuditApplication.kt`
- Create: `spring-modulith/ddd-order-audit/src/main/resources/application.yml`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: Resolve `javers-ddd` artifact before adding the alias**

Run:

```bash
./gradlew dependencyInsight --configuration runtimeClasspath --dependency io.github.bluetape4k.javers:javers-ddd --console=plain
```

Expected: Gradle resolves `io.github.bluetape4k.javers:javers-ddd` from the root `bluetape4k-dependencies` BOM. If it does not resolve, use `bluetape4k-javers-core` plus local after-commit audit code and record that fallback in the implementation notes before committing.

- [ ] **Step 2: Add the module build file**

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

- [ ] **Step 3: Add application entrypoint and configuration**

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

- [ ] **Step 4: Verify project discovery**

Run:

```bash
./gradlew projects --console=plain
```

Expected: output includes `Project ':spring-modulith-ddd-order-audit'`.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml spring-modulith/ddd-order-audit
git commit -m "feat: add DDD order audit module skeleton"
```

## Task 2: Domain Model And Aggregate Tests

**Files:**
- Create: `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderDomain.kt`
- Create: `spring-modulith/ddd-order-audit/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/orders/OrderDomainTest.kt`

- [ ] **Step 1: Write failing aggregate tests**

`OrderDomainTest.kt` must cover:

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

- [ ] **Step 2: Implement immutable domain model**

`OrderDomain.kt` must define serializable value/data classes, command methods returning new instances, and events with safe payloads:

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

The event must carry `aggregateId`, not the full aggregate.

- [ ] **Step 3: Run domain tests**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*OrderDomainTest' --console=plain
```

Expected: aggregate invariant tests pass.

- [ ] **Step 4: Commit**

```bash
git add spring-modulith/ddd-order-audit/src/main/kotlin spring-modulith/ddd-order-audit/src/test/kotlin
git commit -m "feat: model audited order aggregate"
```

## Task 3: PostgreSQL Persistence And Transactional Publication

**Files:**
- Create: `orders/OrderEntity.kt`
- Create: `orders/OrderJpaRepository.kt`
- Create: `orders/OrderCommandService.kt`
- Create: `AbstractDddOrderAuditTest.kt`
- Create: `OrderCommandServiceTest.kt`

- [ ] **Step 1: Write failing PostgreSQL-backed service tests**

The test base must use the confirmed helper:

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

Tests must assert:

- placing an order creates an order row.
- approving an order registers an `OrderApproved` publication row in the same transaction.
- transaction rollback leaves no order row and no publication row.
- repeated approval is rejected or produces no duplicate effective approval.

- [ ] **Step 2: Implement JPA entity and repository**

`OrderEntity` must include:

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

Use bound Spring Data/JPA APIs only; do not concatenate SQL/JPQL strings.

- [ ] **Step 3: Implement command service**

`OrderCommandService` must be `@Transactional`, persist the order, then call `ApplicationEventPublisher.publishEvent(OrderApproved(aggregateId = order.id.value))` before the transaction completes. This lets Spring Modulith write the publication row with the order row while listener side effects stay after-commit.

- [ ] **Step 4: Run persistence tests**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*OrderCommandServiceTest' --console=plain --max-workers=1
```

Expected: PostgreSQL Testcontainer starts through `PostgreSQLServer.Launcher.postgres`; all service tests pass.

- [ ] **Step 5: Commit**

```bash
git add spring-modulith/ddd-order-audit
git commit -m "feat: persist orders with transactional Modulith publications"
```

## Task 4: Fulfillment Listener, Failure, And Replay

**Files:**
- Create: `fulfillment/FulfillmentReservation.kt`
- Create: `fulfillment/FulfillmentReservationRepository.kt`
- Create: `fulfillment/FulfillmentReservationHandler.kt`
- Create: `FulfillmentPublicationTest.kt`

- [ ] **Step 1: Write listener tests**

Tests must use Spring Modulith 2.1 APIs:

```kotlin
@Autowired lateinit var incompletePublications: IncompleteEventPublications
@Autowired lateinit var failedPublications: FailedEventPublications
@Autowired lateinit var eventPublicationRepository: EventPublicationRepository
```

Assertions:

- successful approval eventually creates exactly one reservation.
- configured handler failure leaves `EventPublication.Status.FAILED` or incomplete publication evidence.
- `failedPublications.resubmit(ResubmissionOptions.defaults().withMaxInFlight(1).withBatchSize(1))` or `incompletePublications.resubmitIncompletePublications { it.event is OrderApproved }` creates the reservation after the failure switch is disabled.
- duplicate replay does not create duplicate reservations because `orderId` is unique.

- [ ] **Step 2: Implement handler**

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

The exception message must include only `orderId`; it must not dump the event body or aggregate snapshot.

- [ ] **Step 3: Run listener tests**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*FulfillmentPublicationTest' --console=plain --max-workers=1
```

Expected: listener success, failure evidence, and replay pass without duplicate reservations.

- [ ] **Step 4: Commit**

```bash
git add spring-modulith/ddd-order-audit
git commit -m "feat: handle Modulith fulfillment publications"
```

## Task 5: JaVers After-Commit Audit And Query Tests

**Files:**
- Create: `orders/OrderAuditService.kt`
- Create: `OrderAuditServiceTest.kt`

- [ ] **Step 1: Write audit tests**

Tests must assert:

- placing an order records one JaVers snapshot after commit.
- approving records a second snapshot and a useful diff.
- rollback leaves no JaVers snapshot/diff for the failed command.
- audit DTOs expose synthetic ids/status/amounts only.

- [ ] **Step 2: Implement after-commit audit boundary**

Use `TransactionSynchronizationManager.registerSynchronization`:

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

This prevents in-memory JaVers from recording rolled-back commands.

- [ ] **Step 3: Run audit tests**

```bash
./gradlew :spring-modulith-ddd-order-audit:test --tests '*OrderAuditServiceTest' --console=plain --max-workers=1
```

Expected: snapshot, diff, and rollback-no-audit tests pass.

- [ ] **Step 4: Commit**

```bash
git add spring-modulith/ddd-order-audit
git commit -m "feat: record rollback-safe JaVers order audit"
```

## Task 6: Documentation And Diagrams

**Files:**
- Create: `spring-modulith/ddd-order-audit/README.md`
- Create: `spring-modulith/ddd-order-audit/README.ko.md`
- Create: diagram source/generator files used by the repo pattern.
- Create: `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-architecture-01.{svg,png}`
- Create: `docs/images/readme-diagrams/spring-modulith-ddd-order-audit-readme-sequence-01.{svg,png}`

- [ ] **Step 1: Load diagram and blog skills before editing docs**

Read `bluetape4k-diagram` and `bluetape4k-blog` skill files completely. Apply the full sequence/architecture checklist, including best-practices color palette, transparent alt blocks, centered card text, matching arrowhead/line colors, rounded orthogonal connectors, official icons, SVG+PNG parity, and full-size PNG eye inspection.

- [ ] **Step 2: Write README content**

README must include:

- language switch.
- architecture and sequence PNG embeds with SVG siblings.
- PostgreSQL/Testcontainers prerequisite and command.
- table comparing domain events, Spring Modulith publication, transactional outbox, and JaVers audit.
- warning that audit/event payloads are durable records and must not contain secrets.
- exact validation command:

```bash
./gradlew :spring-modulith-ddd-order-audit:test --console=plain --max-workers=1
```

- [ ] **Step 3: Generate diagrams and run visual QA**

Run the diagram generator command selected by `bluetape4k-diagram`, then:

```bash
./scripts/smoke-validate.sh diagram-qa
```

Expected:

- SVG XML validation passes.
- PNG files render and match SVG arrow directions.
- full-size PNG eye inspection confirms no label overlap, transparent alt blocks, centered cards, correct layer grouping, official PostgreSQL icon, and rounded orthogonal connectors.

- [ ] **Step 4: Commit**

```bash
git add spring-modulith/ddd-order-audit/README.md spring-modulith/ddd-order-audit/README.ko.md docs/images/readme-diagrams
git commit -m "docs: explain DDD order audit workshop flow"
```

## Task 7: Repository Registration And CI

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `AGENTS.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

- [ ] **Step 1: Update root module tables**

Add this English row under Architecture Extensions:

```markdown
| Advanced | [`spring-modulith-ddd-order-audit`](spring-modulith/ddd-order-audit/) | `javers`, `testcontainers` | PostgreSQL (TC) | DDD aggregate lifecycle with Modulith publication rows and JaVers audit history |
```

Add the Korean equivalent:

```markdown
| Advanced | [`spring-modulith-ddd-order-audit`](spring-modulith/ddd-order-audit/) | `javers`, `testcontainers` | PostgreSQL (TC) | Modulith publication row와 JaVers audit history로 배우는 DDD aggregate lifecycle |
```

- [ ] **Step 2: Update CI/smoke registration**

Update `.github/workflows/Examples.yml` path filters and `container-examples` command/artifacts with `spring-modulith/ddd-order-audit`.

Update `scripts/smoke-validate.sh`:

- add `:spring-modulith-ddd-order-audit:test` to `data-access-full`.
- change `expected=91` to `expected=92`.

- [ ] **Step 3: Validate registration**

```bash
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
actionlint .github/workflows/Examples.yml
```

Expected: project count is 92, no stale README refs, workflow syntax passes.

- [ ] **Step 4: Commit**

```bash
git add README.md README.ko.md AGENTS.md .github/workflows/Examples.yml scripts/smoke-validate.sh
git commit -m "build: register DDD order audit workshop"
```

## Task 8: Final Verification, Review, PR, And Merge Gate

**Files:**
- All changed files.

- [ ] **Step 1: Run Kotlin verification**

```bash
./gradlew :spring-modulith-ddd-order-audit:compileKotlin :spring-modulith-ddd-order-audit:compileTestKotlin --warning-mode all --console=plain
./gradlew :spring-modulith-ddd-order-audit:test --warning-mode all --console=plain --max-workers=1
```

Expected: no compile errors, no unresolved deprecation warnings, all tests pass.

- [ ] **Step 2: Run repo verification**

```bash
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh data-access-full
./scripts/smoke-validate.sh diagram-qa
actionlint .github/workflows/Examples.yml
git diff --check
```

Expected: all commands pass. If `data-access-full` is too slow for the local run, run at least the new module test and record the gap before PR.

- [ ] **Step 3: Review PR body metadata before opening**

Use issue #322 metadata:

- assignee: `debop`
- milestone: `1.3.1`
- labels: `documentation`, `enhancement`, `difficulty:advanced`, `area:data-access`, `area:serialization-messaging`, `area:architecture-extension`

PR body final section must be exactly:

```markdown
## DoD Status
```

- [ ] **Step 4: Open PR and verify live metadata**

```bash
gh pr create --base develop --head feat/issue-322-ddd-order-audit --assignee debop --title "feat: add DDD order audit workshop" --body-file /tmp/issue-322-pr.md
gh pr view --json number,title,assignees,milestone,labels,body
```

Expected: live PR metadata mirrors issue #322 and PR body ends with `## DoD Status`.

## Self-Review

- Spec coverage: module, PostgreSQL Testcontainers, transaction-coupled publication row, after-commit listener, rollback-safe JaVers, replay, docs, diagrams, CI, and PR metadata are covered.
- Placeholder scan: this plan uses exact paths, commands, and API names. The only conditional branch is the explicit dependency-resolution fallback for `javers-ddd`, which has a required command and recorded decision point.
- Type consistency: package prefix is `io.bluetape4k.workshop.spring.modulith.ddd.audit`; domain types use `OrderId`, `CustomerId`, `Money`, `Order`, `OrderPlaced`, and `OrderApproved` consistently.
