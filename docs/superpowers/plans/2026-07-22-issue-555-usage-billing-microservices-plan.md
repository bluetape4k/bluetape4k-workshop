# Event-sourced Usage Billing Microservices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `executing-plans` inline task-by-task. The approved user constraint forbids subagent dispatch for this worktree. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build five independently deployable Java 25 Spring Boot services that preserve #552/#553 billing behavior across PostgreSQL-owned state and Kafka at-least-once delivery.

**Architecture:** Meter, Usage, Billing, Invoice, and Query are physical sibling Gradle modules with independent PostgreSQL databases. Producers persist local state plus an Exposed outbox atomically; consumers use a service-local Exposed inbox, aggregate version policy, and quarantine before committing Kafka progress. The composition module owns no production runtime code and verifies black-box behavior across five application contexts and one Kafka broker.

**Tech Stack:** Kotlin 2.4, Java 25, Spring Boot 4 MVC, Spring Kafka 4, PostgreSQL, JetBrains Exposed, `bluetape4k-exposed-jdbc`, `bluetape4k-kafka4`, Bluetape logging/validation/UUID/Micrometer/JUnit/assertions/Testcontainers, Jackson 3, Gradle/Kover/Detekt.

---

## Delivery rules

- The root `bluetape4k-dependencies` BOM is the only Bluetape version authority. No individual
  Bluetape BOM or version pin is added.
- Every concrete persistence class implements its local `ExposedJdbcRepository` base. Exposed DAO/DSL,
  `SchemaUtils`, service ports, and repositories are the only database paths in production and fixtures.
- `JdbcTemplate`, `java.sql.*`, `DriverManager`, `PreparedStatement`, `Statement`, `Transaction.exec`,
  `Connection`, and raw migration SQL are forbidden in all six new module directories.
- Kafka is transport. PostgreSQL in the owning service is correctness authority. No XA, shared database,
  cross-service table read, or end-to-end exactly-once claim is introduced.
- Container tests run in a single Gradle process with `--max-workers=1`. Default tests exclude the
  `integration` tag and must remain container-free.
- Each commit follows the repository Lore commit protocol. Do not create a PR, push, or merge in this
  implementation plan; each is a separate user gate.

## Module and package map

| Gradle project | Package root | Responsibility |
|---|---|---|
| `:commerce-usage-billing-meter-service` | `io.bluetape4k.workshop.commerce.usagebilling.meter` | meter/price authority and outbox |
| `:commerce-usage-billing-usage-service` | `io.bluetape4k.workshop.commerce.usagebilling.usage` | usage receipt/acceptance and outbox |
| `:commerce-usage-billing-billing-service` | `io.bluetape4k.workshop.commerce.usagebilling.billing` | price evidence, rating, immutable charge/adjustment authority |
| `:commerce-usage-billing-invoice-service` | `io.bluetape4k.workshop.commerce.usagebilling.invoice` | immutable invoice/correction document authority |
| `:commerce-usage-billing-query-service` | `io.bluetape4k.workshop.commerce.usagebilling.query` | customer/operator read models, checkpoint, quarantine visibility |
| `:commerce-usage-billing-microservices-composition-tests` | `io.bluetape4k.workshop.commerce.usagebilling.composition` | test-only HTTP/event fixture and Kafka/PostgreSQL composition verification |

Each runtime module owns its envelope decoder, table/entity/repository types, DTOs, configuration and
application main class. The composition module may share JSON samples and HTTP scenario assertions but
must not publish a production jar or be a runtime dependency of a service.

## Task 1: Register the six modules and lock the consumer build surface

**Files:**
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/build.gradle.kts`
- Create: `commerce/usage-billing-microservices-composition-tests/build.gradle.kts`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/resources/junit-platform.properties`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/resources/logback-test.xml`
- Create: `commerce/usage-billing-microservices-composition-tests/src/test/resources/junit-platform.properties`
- Modify: `README.md`, `README.ko.md`, `commerce/README.md`, `commerce/README.ko.md`

- [ ] **Step 1: Verify the new projects are absent.**

  Run: `./gradlew projects --console=plain | rg 'usage-billing-(meter|usage|billing|invoice|query)-service'`

  Expected: no match before directories/build files exist.

- [ ] **Step 2: Add Java 25 Spring Boot module build files.**

  Every runtime build applies Kotlin Spring, Spring Boot, Detekt, Kover; sets Java/Kotlin 25; configures
  `springBoot.mainClass`; extends `testImplementation` from `compileOnly`/`runtimeOnly`; imports only
  versionless catalog aliases. Reuse the #553 `verifyExecution`, test mutex, non-empty XML, and
  `test`/`integrationTest` separation. The composition build is test-only, depends on all five service
  projects for test runtime, and has an `integrationTest` task tagged `integration`.

  ```kotlin
  java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
  kotlin { jvmToolchain(25); compilerOptions.jvmTarget.set(JvmTarget.JVM_25) }

  tasks.test {
      useJava25Runtime()
      useJUnitPlatform { excludeTags("integration") }
  }
  ```

- [ ] **Step 3: Add minimal resource and README registration.**

  Each runtime module gets `application.yml` with named service, disabled auto startup workers in tests,
  PostgreSQL datasource placeholders, Kafka consumer `enable-auto-commit=false`, and immutable
  `@ConfigurationProperties` defaults. Root and commerce English/Korean READMEs identify the group as
  “five independently deployable Spring Boot services; PostgreSQL + Kafka (Testcontainers)”.

- [ ] **Step 4: Prove project graph and empty-test contract.**

  Run: `./gradlew projects :commerce-usage-billing-meter-service:test :commerce-usage-billing-microservices-composition-tests:test --console=plain`

  Expected: all six project paths resolve; each runtime module has Java 25 toolchain configuration and
  the composition project resolves its `integrationTest` task.

- [ ] **Step 5: Commit the isolated module boundary.**

  Commit intent: `Make billing service ownership a build-time boundary`.

## Task 2: Define the test-only black-box contract and service boot scaffolding

**Files:**
- Create: `commerce/usage-billing-microservices-composition-tests/src/testFixtures/kotlin/.../UsageBillingMicroserviceContract.kt`
- Create: `commerce/usage-billing-microservices-composition-tests/src/testFixtures/kotlin/.../UsageBillingScenario.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../*Application.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../ApplicationArchitectureTest.kt`

- [ ] **Step 1: Write failing contract shape tests.**

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

- [ ] **Step 2: Run the contract test.**

  Run: `./gradlew :commerce-usage-billing-microservices-composition-tests:test --tests '*Contract*' --console=plain`

  Expected: FAIL because the fixture and service applications do not exist.

- [ ] **Step 3: Add the five application mains and package-direction guards.**

  Each application has only its own `@SpringBootApplication` scan root. Guards reject imports of another
  service package, composition package, Spring/Exposed/Jackson in pure domain packages, `!!`, `println`,
  broad exception swallowing, and raw database APIs.

  ```kotlin
  @SpringBootApplication
  class MeterServiceApplication

  fun main(args: Array<String>) = runApplication<MeterServiceApplication>(*args)
  ```

- [ ] **Step 4: Prove the boundary compiles.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test --console=plain`

  Expected: PASS with at least one architecture test per runtime module.

- [ ] **Step 5: Commit the contract-only sharing rule.**

  Commit intent: `Keep integration compatibility separate from service internals`.

## Task 3: Implement the independently versioned integration envelope

**Files:**
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../integration/IntegrationEnvelope.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../integration/EnvelopeCodecRegistry.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../integration/EnvelopeCodecRegistryTest.kt`
- Create: `commerce/usage-billing-microservices-composition-tests/src/testFixtures/resources/contracts/{v1,v2}/*.json`

- [ ] **Step 1: Write per-service decoder tests first.**

  Cover a valid v1, a compatible v2 additive field, blank tenant/type, invalid UUID, negative aggregate
  version, digest mismatch, unknown optional field, and unknown mandatory schema. The last case must
  return a typed `UnsupportedEnvelopeVersion`, not `null`.

  ```kotlin
  @Test
  fun `unknown mandatory schema is rejected without decoding payload`() {
      assertFailsWith<UnsupportedEnvelopeVersion> {
          registry.decode(fixture("usage-accepted-v99.json"))
      }
  }
  ```

- [ ] **Step 2: Run one service’s RED test.**

  Run: `./gradlew :commerce-usage-billing-billing-service:test --tests '*EnvelopeCodecRegistryTest' --console=plain`

  Expected: FAIL because the codec registry is absent.

- [ ] **Step 3: Add local envelope/codec implementations.**

  Use service-local types with `eventId`, `eventType`, `schemaVersion`, tenant/aggregate identity/version,
  causation/correlation, producer, timestamps, payload and SHA-256 payload digest. Decode validation
  precedes domain mapping. Registry keys are explicit `(eventType, schemaVersion)` pairs; additive v2
  is decoded through a service-local compatibility adapter.

- [ ] **Step 4: Add Kafka key and JSON compatibility assertions.**

  `partitionKey()` must return exactly `"$tenantId|$aggregateType|$aggregateId"`. Contract fixtures assert
  all five services can parse only their subscribed event types and never require a shared runtime class.

- [ ] **Step 5: Run all envelope tests and Detekt.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test detekt --console=plain`

  Expected: PASS.

- [ ] **Step 6: Commit independent envelope evolution.**

  Commit intent: `Version service messages without a shared runtime model`.

## Task 4: Add Exposed-only persistence bases and database architecture guards

**Files:**
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../persistence/*ExposedJdbcRepository.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../persistence/*Tables.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../persistence/*Entities.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../persistence/RepositoryArchitectureTest.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../persistence/*DatabaseFixture.kt`

- [ ] **Step 1: Write repository and raw-API guards.**

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

  The forbidden list includes `JdbcTemplate`, `DriverManager`, `java.sql.`, `PreparedStatement`,
  `createStatement`, `Transaction.exec`, `exec(`, and `Connection`.

- [ ] **Step 2: Run the meter repository guard.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test --tests '*RepositoryArchitectureTest' --console=plain`

  Expected: FAIL before repository base/types exist.

- [ ] **Step 3: Add each local base and tables through Exposed.**

  ```kotlin
  abstract class MeterExposedJdbcRepository<E : Entity<ID>, ID : Any>(domainClass: Class<E>) :
      ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
          ExposedEntityInformationImpl(domainClass),
      )
  ```

  Define audit/tenant columns through Exposed tables; use unique indices for business identity, event ID,
  and stream version. Fixture schema setup uses `SchemaUtils.create` and cleanup uses repository/table DSL
  only.

- [ ] **Step 4: Prove architecture and PostgreSQL fixture startup.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test --console=plain`

  Expected: PASS; default tests do not start a container.

- [ ] **Step 5: Commit the persistence boundary.**

  Commit intent: `Keep service databases behind Exposed repository contracts`.

## Task 5: Build Meter authority, command receipt, and local transactional outbox

**Files:**
- Create: `commerce/usage-billing-meter-service/src/main/kotlin/.../domain/MeterCommands.kt`
- Create: `commerce/usage-billing-meter-service/src/main/kotlin/.../domain/MeterEvents.kt`
- Create: `commerce/usage-billing-meter-service/src/main/kotlin/.../application/MeterCommandService.kt`
- Create: `commerce/usage-billing-meter-service/src/main/kotlin/.../persistence/{Meter,PriceVersion,CommandReceipt,Outbox}Repository.kt`
- Create: `commerce/usage-billing-meter-service/src/main/kotlin/.../web/MeterController.kt`
- Create: `commerce/usage-billing-meter-service/src/test/kotlin/.../{application,persistence,web}/Meter*Test.kt`

- [ ] **Step 1: Write failing meter command tests.**

  Prove price activation creates immutable version plus `PriceActivated` outbox row atomically; same
  idempotency key/payload replays terminal HTTP response; same key/different fingerprint returns 409;
  a transaction failure leaves neither price version nor outbox row.

- [ ] **Step 2: Run RED HTTP and transaction tests.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test --tests '*MeterCommandServiceTest' --tests '*MeterControllerTest' --console=plain`

  Expected: FAIL because command service and endpoint are absent.

- [ ] **Step 3: Implement immutable Meter command path.**

  In one `SpringTransactionManager` transaction, acquire/fence a local command receipt, append price
  version, write a `PENDING` outbox event with envelope digest and partition key, then persist terminal
  receipt. Conditional updates include receipt owner token and status. Existing price version is never
  updated or deleted.

- [ ] **Step 4: Add PostgreSQL atomicity proof.**

  Tag `MeterOutboxPostgresIntegrationTest` as `integration`; inject a controlled failure between price
  write and receipt completion and assert both price/outbox counts remain zero after rollback.

- [ ] **Step 5: Verify focused and integration paths.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-meter-service:integrationTest --max-workers=1 --console=plain`

  Expected: PASS with non-empty XML from both tasks.

- [ ] **Step 6: Commit local price authority.**

  Commit intent: `Make price activation durable before asynchronous publication`.

## Task 6: Build Usage idempotency, price evidence inbox, and usage outbox

**Files:**
- Create: `commerce/usage-billing-usage-service/src/main/kotlin/.../domain/{UsageCommands,UsageEvents}.kt`
- Create: `commerce/usage-billing-usage-service/src/main/kotlin/.../application/{UsageCommandService,PriceEvidenceService}.kt`
- Create: `commerce/usage-billing-usage-service/src/main/kotlin/.../persistence/{Usage,PriceEvidence,CommandReceipt,Inbox,Outbox}Repository.kt`
- Create: `commerce/usage-billing-usage-service/src/main/kotlin/.../web/UsageController.kt`
- Create: `commerce/usage-billing-usage-service/src/test/kotlin/.../{application,persistence,web}/*Test.kt`

- [ ] **Step 1: Write failing usage behavior tests.**

  Cover accepted source event, same source event duplicate, conflicting source event, HTTP idempotency
  replay/conflict, missing price evidence as explicit domain rejection/defer policy, and `UsageAccepted`
  outbox row in the same commit.

- [ ] **Step 2: Run the usage RED tests.**

  Run: `./gradlew :commerce-usage-billing-usage-service:test --tests '*UsageCommandServiceTest' --console=plain`

  Expected: FAIL because acceptance/repository ports are absent.

- [ ] **Step 3: Implement local usage state and inbound price evidence.**

  `PriceEvidenceService` inserts through a local inbox unique on `(tenantId,eventId)`, validates digest,
  and stores immutable pricing evidence. `UsageCommandService` owns source uniqueness and local command
  receipt; it reads only Usage database evidence, never Meter tables.

- [ ] **Step 4: Add duplicate and restart persistence tests.**

  A fresh application context must read the persisted terminal receipt/inbox row and produce no second
  usage event/outbox row. Use Exposed repository inspection only.

- [ ] **Step 5: Verify Usage.**

  Run: `./gradlew :commerce-usage-billing-usage-service:test :commerce-usage-billing-usage-service:integrationTest --max-workers=1 --console=plain`

  Expected: PASS.

- [ ] **Step 6: Commit durable usage acceptance.**

  Commit intent: `Make usage retries safe before they reach billing`.

## Task 7: Build Billing price/usage inbox policy and append-only rating authority

**Files:**
- Create: `commerce/usage-billing-billing-service/src/main/kotlin/.../domain/{BillingPeriod,Charge,Adjustment}*.kt`
- Create: `commerce/usage-billing-billing-service/src/main/kotlin/.../application/{BillingInboxService,BillingCloseService,CorrectionService}.kt`
- Create: `commerce/usage-billing-billing-service/src/main/kotlin/.../persistence/{PricingEvidence,UsageInbox,BillingPeriod,Charge,Adjustment,Outbox}Repository.kt`
- Create: `commerce/usage-billing-billing-service/src/main/kotlin/.../worker/DeferredInboxWorker.kt`
- Create: `commerce/usage-billing-billing-service/src/test/kotlin/.../{application,persistence,worker}/*Test.kt`

- [ ] **Step 1: Write failing aggregate-version policy tests.**

  Cover expected next version -> `APPLIED`; lower version/same digest -> `DUPLICATE`; same event ID/different
  digest -> `QUARANTINED`; future version -> `DEFERRED`; missing pricing evidence -> `DEFERRED`; retry
  budget exhaustion -> `QUARANTINED` with stable reason.

- [ ] **Step 2: Run the policy RED test.**

  Run: `./gradlew :commerce-usage-billing-billing-service:test --tests '*BillingInboxServiceTest' --console=plain`

  Expected: FAIL because inbox state types and service do not exist.

- [ ] **Step 3: Implement local inbox claim/CAS and append-only charge.**

  Use row status, claim owner token, claim deadline, expected aggregate version, and conditional Exposed
  update predicates. An `APPLIED` Usage event creates one immutable charge and one `ChargeRated` outbox
  event in the same transaction as inbox completion/checkpoint advance. Generic update/delete methods
  for charge/adjustment repositories are blocked.

- [ ] **Step 4: Implement correction as compensation.**

  `CorrectionService` locates original local charge provenance, appends a debit/credit `AdjustmentPosted`,
  and enqueues one outbox event. It does not update usage, charge, prior adjustment, or invoice tables.

- [ ] **Step 5: Add PostgreSQL race/recovery tests.**

  Use `MultithreadingTester` against PostgreSQL to race two claims of one inbox event; assert one applied
  effect. Restart with a claimed-but-expired row and assert the new owner completes it once. Verify a
  deferred row becomes applied only after the predecessor/pricing evidence is accepted.

- [ ] **Step 6: Verify Billing.**

  Run: `./gradlew :commerce-usage-billing-billing-service:test :commerce-usage-billing-billing-service:integrationTest --max-workers=1 --console=plain`

  Expected: PASS.

- [ ] **Step 7: Commit append-only financial authority.**

  Commit intent: `Preserve rated charges across delayed billing events`.

## Task 8: Build Invoice consumer and immutable document lineage

**Files:**
- Create: `commerce/usage-billing-invoice-service/src/main/kotlin/.../domain/{Invoice,InvoiceLine,InvoiceCorrection}.kt`
- Create: `commerce/usage-billing-invoice-service/src/main/kotlin/.../application/InvoiceInboxService.kt`
- Create: `commerce/usage-billing-invoice-service/src/main/kotlin/.../persistence/{Invoice,InvoiceLine,Inbox,Outbox}Repository.kt`
- Create: `commerce/usage-billing-invoice-service/src/main/kotlin/.../web/InvoiceController.kt`
- Create: `commerce/usage-billing-invoice-service/src/test/kotlin/.../*Invoice*Test.kt`

- [ ] **Step 1: Write failing invoice tests.**

  Cover `ChargeRated` creates one invoice line with charge event provenance, duplicate delivery creates
  no additional line, `AdjustmentPosted` creates a new correction document/line, and finalized invoice
  rows cannot be updated or deleted through public repository methods.

- [ ] **Step 2: Run the Invoice RED tests.**

  Run: `./gradlew :commerce-usage-billing-invoice-service:test --tests '*InvoiceInboxServiceTest' --console=plain`

  Expected: FAIL because the consumer and repositories are absent.

- [ ] **Step 3: Implement invoice local inbox/effect transaction.**

  Decode Billing event, insert/claim inbox, write immutable invoice/document lineage and optional
  `InvoiceIssued`/`InvoiceCorrectionIssued` outbox event in one Exposed transaction, then complete inbox.
  Do not read Billing database or recompute a charge amount.

- [ ] **Step 4: Add tenant and correction HTTP tests.**

  Customer invoice query requires matching authenticated tenant; operator correction visibility requires
  `ROLE_OPERATOR`. Assert original invoice remains byte-for-byte/value-for-value unchanged after
  correction.

- [ ] **Step 5: Verify Invoice.**

  Run: `./gradlew :commerce-usage-billing-invoice-service:test :commerce-usage-billing-invoice-service:integrationTest --max-workers=1 --console=plain`

  Expected: PASS.

- [ ] **Step 6: Commit immutable invoice consumption.**

  Commit intent: `Materialize invoices without taking billing authority`.

## Task 9: Build Query projections, operator recovery, metrics, and security

**Files:**
- Create: `commerce/usage-billing-query-service/src/main/kotlin/.../application/{QueryInboxService,ProjectionRebuildService,QuarantineRedriveService}.kt`
- Create: `commerce/usage-billing-query-service/src/main/kotlin/.../persistence/{ReadModel,Inbox,Checkpoint,Quarantine}Repository.kt`
- Create: `commerce/usage-billing-query-service/src/main/kotlin/.../config/{QueryProperties,QueryMetrics,QueryHealthIndicator,SecurityConfiguration}.kt`
- Create: `commerce/usage-billing-query-service/src/main/kotlin/.../web/{QueryController,OperatorRecoveryController}.kt`
- Create: `commerce/usage-billing-query-service/src/test/kotlin/.../{application,config,web}/*Test.kt`

- [ ] **Step 1: Write failing Query/operator tests.**

  Cover projection dedup, per-projection checkpoint advance only with read-model mutation, projection lag,
  unknown envelope quarantine, redrive audit, customer tenant rejection, operator role rejection, and
  low-cardinality metric tags.

- [ ] **Step 2: Run Query RED tests.**

  Run: `./gradlew :commerce-usage-billing-query-service:test --tests '*QueryInboxServiceTest' --tests '*OperatorRecoveryControllerTest' --console=plain`

  Expected: FAIL because query projection/recovery endpoints are absent.

- [ ] **Step 3: Implement projection and operator boundary.**

  `QueryInboxService` persists the inbox outcome, local projection mutation, and checkpoint in one
  Exposed transaction. `OperatorRecoveryController` exposes readonly backlog/oldest-age/reason summaries
  and an explicit redrive command that records actor, attempt, old/new state, and correlation ID. It
  cannot mutate financial state.

- [ ] **Step 4: Add Micrometer/health/configuration tests.**

  Assert exact meter names and tag keys from the design; reject tenant/event/payload tags. A health
  indicator reports degraded when quarantine or oldest backlog crosses configured bounds, without
  treating consumer offset as financial truth.

- [ ] **Step 5: Verify Query.**

  Run: `./gradlew :commerce-usage-billing-query-service:test :commerce-usage-billing-query-service:integrationTest --max-workers=1 --console=plain`

  Expected: PASS.

- [ ] **Step 6: Commit observable recovery paths.**

  Commit intent: `Expose asynchronous billing recovery without cross-service writes`.

## Task 10: Wire local outbox publishers and Kafka listeners without false EOS claims

**Files:**
- Create: `commerce/usage-billing-{meter,usage,billing,invoice}-service/src/main/kotlin/.../messaging/OutboxPublisher.kt`
- Create: `commerce/usage-billing-{usage,billing,invoice,query}-service/src/main/kotlin/.../messaging/*KafkaListener.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/main/kotlin/.../config/KafkaMessagingConfiguration.kt`
- Create: `commerce/usage-billing-{meter,usage,billing,invoice,query}-service/src/test/kotlin/.../messaging/*Test.kt`

- [ ] **Step 1: Write failing messaging state-machine tests.**

  Test `PENDING -> CLAIMED -> PUBLISHED`, send failure -> `RETRY_WAIT`, expired claim -> re-claim,
  retry exhaustion -> `QUARANTINED`, crash simulation after broker acknowledgment before published mark
  -> second send, and listener acknowledgment only after durable inbox outcome.

- [ ] **Step 2: Run Meter outbox RED test.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test --tests '*OutboxPublisherTest' --console=plain`

  Expected: FAIL because publisher state machine is absent.

- [ ] **Step 3: Implement outbox publisher.**

  Claim a bounded ordered page using Exposed conditional update/lease fields, send with
  `KafkaTemplate<String, String>` using `envelope.partitionKey()`, then conditionally mark published.
  Do not wrap the producer and PostgreSQL in an XA-like transaction and do not describe this as exactly
  once. Kafka send success is not persisted as financial authority.

- [ ] **Step 4: Implement listeners and failure mapping.**

  Configure `enable-auto-commit=false`, record listener delivery, and manual/container-managed offset
  progression only after the local inbox service returns a durable terminal/deferred/quarantine outcome.
  Transient database/broker failure throws for redelivery; permanent decode failure becomes durable
  quarantine then returns normally. Do not call Kafka `Consumer` position/offset mutation APIs directly.

- [ ] **Step 5: Verify listener configuration and module tests.**

  Run: `./gradlew :commerce-usage-billing-meter-service:test :commerce-usage-billing-usage-service:test :commerce-usage-billing-billing-service:test :commerce-usage-billing-invoice-service:test :commerce-usage-billing-query-service:test --console=plain`

  Expected: PASS.

- [ ] **Step 6: Commit transport boundaries.**

  Commit intent: `Treat Kafka delivery as replayable transport rather than financial proof`.

## Task 11: Implement the composition fixture and required Kafka/PostgreSQL scenarios

**Files:**
- Create: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../fixture/UsageBillingMicroserviceFixture.kt`
- Create: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../fixture/KafkaFailureController.kt`
- Create: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../UsageBillingMicroserviceCompositionIntegrationTest.kt`
- Create: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/.../{Duplicate,Ordering,Poison,Restart,SchemaEvolution,Outage,TenantIsolation,Parity,Correction}IntegrationTest.kt`

- [ ] **Step 1: Write the first cross-service RED scenario.**

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

- [ ] **Step 2: Run the composition RED test.**

  Run: `./gradlew :commerce-usage-billing-microservices-composition-tests:integrationTest --tests '*CompositionIntegrationTest' --no-build-cache --max-workers=1 --console=plain`

  Expected: FAIL because fixture/service topology is absent.

- [ ] **Step 3: Start isolated infrastructure and contexts.**

  Fixture starts one `KafkaContainer` and five service-specific `PostgreSQLServer` launchers, then starts
  five application contexts with distinct datasource URLs, consumer group IDs, and random HTTP ports.
  It creates topics through Kafka admin APIs and seeds/observes only through HTTP/service ports/Exposed
  repositories. It never obtains a JDBC connection or accesses another service database.

- [ ] **Step 4: Add all required integration proofs.**

  Implement separate tagged tests for:

  1. publish failure then later delivery;
  2. duplicate record delivery gives one inbox outcome/financial effect;
  3. delayed/reordered event follows `DEFERRED` then `APPLIED`, or explicit quarantine;
  4. poison event quarantines one aggregate while an independent aggregate progresses and operator redrive audits;
  5. application context restart recovers expired claim/inbox/outbox/checkpoint;
  6. v1/v2 compatibility plus unsupported mandatory version quarantine;
  7. Kafka outage backlog/recovery without loss of committed command;
  8. cross-tenant command/event/query denial;
  9. #552/#553 black-box totals, invoice, and adjustment parity;
  10. cross-service correction keeps original charge/invoice immutable and adds exactly one compensating result.

- [ ] **Step 5: Add bounded concurrency and cleanup assertions.**

  Every test uses deterministic test clock/IDs, bounded Awaitility, `finally` context close, and asserts
  no leaked claimed outbox/inbox rows. Race test uses `MultithreadingTester`, not an ad hoc executor.

- [ ] **Step 6: Run fresh composition verification.**

  Run: `./gradlew :commerce-usage-billing-microservices-composition-tests:cleanIntegrationTest :commerce-usage-billing-microservices-composition-tests:integrationTest --no-build-cache --max-workers=1 --console=plain`

  Expected: PASS with all ten named scenario classes and non-empty JUnit XML.

- [ ] **Step 7: Commit the distributed correctness proof.**

  Commit intent: `Prove billing outcomes survive asynchronous service failures`.

## Task 12: Add diagrams, README decision guide, and generated visual validation

**Files:**
- Create: `commerce/usage-billing-microservices/README.md`
- Create: `commerce/usage-billing-microservices/README.ko.md`
- Create: `scripts/generate-usage-billing-microservices-diagrams.mjs`
- Create: `scripts/validate-usage-billing-microservices-readme.mjs`
- Create: `docs/images/readme-diagrams/usage-billing-microservices-{architecture,outbox-inbox-state,delivery,poison-recovery,correction,extraction}-01.{svg,png}`
- Modify: `README.md`, `README.ko.md`, `commerce/README.md`, `commerce/README.ko.md`

- [ ] **Step 1: Write failing README validator cases.**

  Require both locale files, all six diagrams, service ownership table, no-XA/no-exactly-once statement,
  modular-monolith comparison, extraction/rollback path, run commands, and every module/workflow link.

- [ ] **Step 2: Run validator RED.**

  Run: `node scripts/validate-usage-billing-microservices-readme.mjs`

  Expected: FAIL because README and assets are absent.

- [ ] **Step 3: Generate source SVG and CairoSVG PNG through `bluetape-diagram`.**

  The generator emits direct endpoint/tangent arrow polygons, rounded orthogonal connectors, no
  connector overlap, no Unicode glyph that CairoSVG changes, embedded fonts, and stable viewboxes.
  Architecture labels remain English; Korean README explains the diagram.

- [ ] **Step 4: Write the decision and operational guide.**

  Explain when #552, #553, or #555 fits; why Kafka is at-least-once transport; state each service’s DB
  authority; document duplicate/delay/poison/redrive behavior; give staged Meter/Usage -> Billing ->
  Invoice/Query extraction, parity drain criteria, and route-only rollback.

- [ ] **Step 5: Run full diagram/readme QA.**

  Run: `node scripts/generate-usage-billing-microservices-diagrams.mjs && node scripts/validate-usage-billing-microservices-readme.mjs && ./scripts/smoke-validate.sh diagram-qa`

  Expected: PASS for SVG structural audit, connector non-overlap, direct head geometry, SVG/PNG arrow
  direction parity, full-size PNG visual inspection manifest, README link/locale checks.

- [ ] **Step 6: Commit runnable operational documentation.**

  Commit intent: `Explain service recovery before readers operate the example`.

## Task 13: Register smoke/full workflows, Kover artifacts, stale checks, lessons, and reviews

**Files:**
- Modify: `scripts/smoke-validate.sh`
- Modify: `.github/workflows/Examples.yml`
- Modify: `.github/workflows/nightly.yml`
- Create: `docs/lessons/2026-07-22-issue-555-usage-billing-microservices.md`
- Create: `docs/review/2026-07-22-issue-555-usage-billing-microservices-plan-review.md`
- Create: `docs/review/2026-07-22-issue-555-usage-billing-microservices-implementation-review.md`

- [ ] **Step 1: Write failing workflow/static assertions.**

  Extend the README validator or a dedicated Node test so all five default `:test` tasks appear in the
  smoke lane, composition `:integrationTest` and `:koverXmlReport` appear in the sequential container
  and nightly lanes, and the expected XML/Kover paths exist in artifact verification/upload blocks.

- [ ] **Step 2: Run workflow guard RED.**

  Run: `node scripts/validate-usage-billing-microservices-readme.mjs && ./scripts/smoke-validate.sh stale-check`

  Expected: FAIL until every required registration is added.

- [ ] **Step 3: Update smoke/full registration.**

  Add five runtime `:test` tasks to Examples smoke and composition integration/Kover to container and
  nightly sequential commands. Add exact result/report paths, explanatory comments, `commerce` smoke
  group commands, and stale-check discovery expectations. Do not put Testcontainers tests in daily
  smoke.

- [ ] **Step 4: Record lessons and six-lens implementation review.**

  Korean lesson covers local authority, duplicate boundary, poisoning, rollback discipline, Exposed-only
  fixture rule, and diagram renderer proof. The implementation review records the exact final head,
  all six review lenses, test commands/counts, diagram evidence, and unresolved risks.

- [ ] **Step 5: Run affected workflow/docs checks.**

  Run: `./scripts/smoke-validate.sh commerce && ./scripts/smoke-validate.sh stale-check && git diff --check`

  Expected: PASS with all new module names and no stale links.

- [ ] **Step 6: Commit the repository integration chain.**

  Commit intent: `Keep distributed billing validation visible in repository automation`.

## Task 14: Final verification, risk review, and delivery evidence

**Files:**
- Modify: `docs/review/2026-07-22-issue-555-usage-billing-microservices-implementation-review.md`
- Modify: `docs/lessons/2026-07-22-issue-555-usage-billing-microservices.md`

- [ ] **Step 1: Run fresh targeted module verification.**

  Run:

  ```bash
  ./gradlew \
    :commerce-usage-billing-meter-service:cleanTest :commerce-usage-billing-meter-service:test \
    :commerce-usage-billing-usage-service:cleanTest :commerce-usage-billing-usage-service:test \
    :commerce-usage-billing-billing-service:cleanTest :commerce-usage-billing-billing-service:test \
    :commerce-usage-billing-invoice-service:cleanTest :commerce-usage-billing-invoice-service:test \
    :commerce-usage-billing-query-service:cleanTest :commerce-usage-billing-query-service:test \
    --no-build-cache --console=plain
  ```

  Expected: PASS; record executed test counts and zero default Testcontainers startup.

- [ ] **Step 2: Run fresh sequential composition and report checks.**

  Run:

  ```bash
  ./gradlew \
    :commerce-usage-billing-microservices-composition-tests:cleanIntegrationTest \
    :commerce-usage-billing-microservices-composition-tests:integrationTest \
    :commerce-usage-billing-microservices-composition-tests:koverXmlReport \
    --no-build-cache --max-workers=1 --console=plain
  ```

  Expected: PASS; assert non-empty `build/test-results/integrationTest/*.xml` and
  `build/reports/kover/report.xml`.

- [ ] **Step 3: Run static and visual completion checks.**

  Run:

  ```bash
  ./gradlew detekt detektTest --console=plain
  ./scripts/smoke-validate.sh commerce
  ./scripts/smoke-validate.sh stale-check
  ./scripts/smoke-validate.sh diagram-qa
  node scripts/validate-usage-billing-microservices-readme.mjs
  git diff --check
  ```

  Expected: PASS.

- [ ] **Step 4: Perform the final six-lens inline review.**

  Recheck exact final head for performance (bounded page/lag), stability (restart/claim/retry), security
  (tenant/operator/telemetry), Operator/Ops (quarantine/redrive/metrics), developer/API (module/envelope
  compatibility/Exposed guard), and user/caller (idempotency/totals/correction). Record P0/P1/P2 and
  remediate every P0/P1 before delivery.

- [ ] **Step 5: Create the final local commit only after all checks pass.**

  Commit intent: `Demonstrate recoverable billing delivery across service boundaries`.

  Lore trailers must name the no-XA constraint, rejected shared database/EOS alternatives, verification
  commands, and any unexecuted environment-dependent checks.

## Plan self-review

| Design requirement | Planned task |
|---|---|
| independent deployable services and DB ownership | Tasks 1-2, 4-9 |
| Exposed/JDBC-only repositories and fixtures | Task 4 plus architecture guards in every service |
| envelope evolution and aggregate partition key | Task 3 |
| local outbox and at-least-once duplicate handling | Tasks 5-10 |
| delay/reorder/poison/restart policy | Tasks 7, 9-11 |
| immutable financial correction | Tasks 7-8, 11 |
| operator metrics/recovery/security | Task 9 |
| Testcontainers required scenario matrix | Task 11 |
| diagrams/README/extraction guide | Task 12 |
| workflow, matrix, stale checks, lesson/reviews | Tasks 13-14 |

The forbidden-marker scan must produce no hits, and no behavior may be left unspecified. Type names
introduced by Tasks 3-10 are used consistently in Tasks 11-14.
