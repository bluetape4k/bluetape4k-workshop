# Kafka recovery conformance fixture ecosystem 승격 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Workshop의 `KafkaRecoveryConformanceFixture`를 `bluetape4k-kafka`의 독립 `testFixtures` artifact로 승격하고 Kafka 4 Workshop 소비자와 usage-billing 소비자가 upstream fixture를 사용하게 한다.

**Architecture:** `bluetape4k-projects/infra/kafka`에 `java-test-fixtures` source set을 추가하고 fixture를 `io.bluetape4k.kafka.test.conformance` 패키지로 공개한다. fixture는 `java.time.Duration`과 Kotlin 표준 타입만 사용하며 Kafka 3 client, Spring Kafka, Testcontainers, broker topology를 참조하지 않는다. Workshop은 외부 `testFixtures(libs.bluetape4k.kafka)`로 전환하고 18-field JSONL validator와 topology별 integration test는 Workshop에 유지한다.

**Tech Stack:** Kotlin 2.4, Java 25, Gradle 9.8.2, `java-test-fixtures`, JUnit 5, Kafka 3/4 consumer tests, Maven local verification.

---

### Task 1: Upstream fixture source set과 RED 계약 테스트

**Files:**
- Modify: `/Users/debop/work/bluetape4k/bluetape4k-projects/.worktrees/feat-kafka-recovery-fixture-promotion/infra/kafka/build.gradle.kts`
- Create: `/Users/debop/work/bluetape4k/bluetape4k-projects/.worktrees/feat-kafka-recovery-fixture-promotion/infra/kafka/src/test/kotlin/io/bluetape4k/kafka/test/conformance/KafkaRecoveryConformanceFixtureTest.kt`

- [x] **Step 1: Enable test fixtures and make the fixture output testable**

  Add the `java-test-fixtures` plugin to the existing `plugins` block. Add the following configuration so the module's own `test` task compiles against its `testFixtures` output without adding a self-project dependency:

  ```kotlin
  sourceSets {
      named("test") {
          compileClasspath += sourceSets.named("testFixtures").get().output
          runtimeClasspath += sourceSets.named("testFixtures").get().output
      }
  }
  ```

- [x] **Step 2: Add the failing conformance test**

  Create a normal module test (the fixture itself remains in the `testFixtures` source set) for the initial RED proof. The test must import the final package and exercise all public methods:

  ```kotlin
  package io.bluetape4k.kafka.test.conformance

  import java.time.Duration
  import org.junit.jupiter.api.Test
  import kotlin.test.assertFailsWith

  class KafkaRecoveryConformanceFixtureTest {
      private val fixture = KafkaRecoveryConformanceFixture(Duration.ofSeconds(15))
      private val ids = setOf("event-1", "event-2")

      @Test
      fun `transport recovery accepts only transport evidence`() {
          fixture.assertTransportRecovery(observation(KafkaRecoveryPath.TRANSPORT_INTERRUPTION))
      }

      @Test
      fun `leader and coordinator failover keep their evidence boundaries`() {
          fixture.assertBrokerLeaderFailover(observation(KafkaRecoveryPath.BROKER_LEADER_FAILOVER))
          fixture.assertBrokerCoordinatorFailover(observation(KafkaRecoveryPath.BROKER_COORDINATOR_FAILOVER))
      }

      @Test
      fun `dedup boundary requires replay and unique effects`() {
          fixture.assertDedupBoundary(ids, listOf("event-1", "event-2", "event-1"), ids)
      }

      @Test
      fun `deadline and path mismatch are rejected`() {
          assertFailsWith<IllegalStateException> {
              fixture.assertTransportRecovery(observation(KafkaRecoveryPath.BROKER_LEADER_FAILOVER))
          }
          assertFailsWith<IllegalStateException> {
              fixture.assertBrokerLeaderFailover(observation(KafkaRecoveryPath.BROKER_LEADER_FAILOVER, recoveryElapsed = Duration.ofSeconds(16)))
          }
      }

      private fun observation(
          path: KafkaRecoveryPath,
          recoveryElapsed: Duration = Duration.ofSeconds(1),
      ): KafkaRecoveryObservation = KafkaRecoveryObservation(
          path = path,
          logicalEventIds = ids,
          deliveredEventIds = if (path == KafkaRecoveryPath.TRANSPORT_INTERRUPTION) ids.toList() else ids.toList(),
          appliedEventIds = ids,
          conflictCount = 0,
          recoveryElapsed = recoveryElapsed,
          recovered = true,
          transportInterrupted = path == KafkaRecoveryPath.TRANSPORT_INTERRUPTION,
          leaderChanged = path == KafkaRecoveryPath.BROKER_LEADER_FAILOVER,
          coordinatorChanged = path == KafkaRecoveryPath.BROKER_COORDINATOR_FAILOVER,
          replacementReady = path != KafkaRecoveryPath.TRANSPORT_INTERRUPTION,
          isrRestored = path != KafkaRecoveryPath.TRANSPORT_INTERRUPTION,
      )
  }
  ```

  If the implementation is not yet present, compilation must fail with unresolved references to the three fixture types.

- [x] **Step 3: Run the RED proof**

  Run from the Kafka worktree:

  ```bash
  ./gradlew :bluetape4k-kafka:test --tests 'io.bluetape4k.kafka.test.conformance.KafkaRecoveryConformanceFixtureTest' --no-daemon --console=plain
  ```

  Expected: compilation fails because the fixture classes do not exist.

### Task 2: Implement and test the version-neutral fixture

**Files:**
- Create: `/Users/debop/work/bluetape4k/bluetape4k-projects/.worktrees/feat-kafka-recovery-fixture-promotion/infra/kafka/src/testFixtures/kotlin/io/bluetape4k/kafka/test/conformance/KafkaRecoveryConformanceFixture.kt`
- Modify: `/Users/debop/work/bluetape4k/bluetape4k-projects/.worktrees/feat-kafka-recovery-fixture-promotion/infra/kafka/src/test/kotlin/io/bluetape4k/kafka/test/conformance/KafkaRecoveryConformanceFixtureTest.kt`

- [x] **Step 1: Move the existing black-box contract without semantic changes**

  Copy the existing Workshop implementation from `shared/src/testFixtures/.../KafkaRecoveryConformanceFixture.kt`, change only the package to `io.bluetape4k.kafka.test.conformance`, and keep these exact public members:

  ```kotlin
  class KafkaRecoveryConformanceFixture(private val recoveryDeadline: Duration) {
      fun assertTransportRecovery(observation: KafkaRecoveryObservation)
      fun assertBrokerLeaderFailover(observation: KafkaRecoveryObservation)
      fun assertBrokerCoordinatorFailover(observation: KafkaRecoveryObservation)
      fun assertDedupBoundary(
          logicalEventIds: Set<String>,
          deliveredEventIds: List<String>,
          appliedEventIds: Set<String>,
      )
  }

  enum class KafkaRecoveryPath {
      TRANSPORT_INTERRUPTION,
      BROKER_LEADER_FAILOVER,
      BROKER_COORDINATOR_FAILOVER,
  }

  data class KafkaRecoveryObservation(
      val path: KafkaRecoveryPath,
      val logicalEventIds: Set<String>,
      val deliveredEventIds: List<String>,
      val appliedEventIds: Set<String>,
      val conflictCount: Int,
      val recoveryElapsed: Duration,
      val recovered: Boolean,
      val transportInterrupted: Boolean,
      val leaderChanged: Boolean,
      val coordinatorChanged: Boolean,
      val replacementReady: Boolean,
      val isrRestored: Boolean,
  )
  ```

  Preserve positive deadline, non-negative conflict/elapsed validation, exact path guards, replay duplicate requirement, and all existing failure messages. Do not import any `org.apache.kafka`, Spring, Testcontainers, or Workshop package.

- [x] **Step 2: Run the GREEN contract test**

  ```bash
  ./gradlew :bluetape4k-kafka:test --tests 'io.bluetape4k.kafka.test.conformance.KafkaRecoveryConformanceFixtureTest' --no-daemon --console=plain
  ```

  Expected: all four tests pass and the fixture implementation is resolved from
  the module's `testFixtures` output.

- [x] **Step 3: Verify the fixture bytecode and dependency boundary**

  Run:

  ```bash
  javap -classpath infra/kafka/build/classes/kotlin/testFixtures -public io.bluetape4k.kafka.test.conformance.KafkaRecoveryConformanceFixture io.bluetape4k.kafka.test.conformance.KafkaRecoveryPath io.bluetape4k.kafka.test.conformance.KafkaRecoveryObservation
  ./gradlew :bluetape4k-kafka:dependencies --configuration testFixturesApiElements --no-daemon --console=plain
  ```

  Expected: public signatures contain only Kotlin/JDK types; the fixture configuration has no `org.apache.kafka`, Spring Kafka, or Testcontainers dependency introduced by the fixture itself.

### Task 3: Publish the upstream test fixture locally

**Files:**
- Modify: none

- [x] **Step 1: Publish the exact catalog version to Maven local**

  Run from the Kafka worktree:

  ```bash
  ./gradlew :bluetape4k-kafka:publishToMavenLocal -PbaseVersion=2.2.0-SNAPSHOT --no-daemon --console=plain
  ```

  Confirm that `~/.m2/repository/io/github/bluetape4k/bluetape4k-kafka/2.2.0-SNAPSHOT/` contains the main POM/module metadata and the test-fixtures variant. Do not publish a tag or external release in this plan.

### Task 4: Switch Workshop Kafka consumers

**Files:**
- Modify: `messaging/kafka-multi-broker-failover/build.gradle.kts`
- Modify: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaMultiBrokerFailoverIntegrationTest.kt`
- Modify: `messaging/kafka-multi-broker-failover/src/test/kotlin/io/bluetape4k/workshop/messaging/kafka/multibroker/failover/KafkaRecoveryConformanceFixtureTest.kt`
- Modify: `commerce/usage-billing-microservices-composition-tests/build.gradle.kts`
- Modify: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/io/bluetape4k/workshop/commerce/usagebilling/composition/BrokerPathRecoveryIntegrationTest.kt`
- Modify: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/io/bluetape4k/workshop/commerce/usagebilling/composition/UsageBillingMicroserviceCompositionIntegrationTest.kt`
- Delete: `shared/src/testFixtures/kotlin/io/bluetape4k/workshop/shared/messaging/KafkaRecoveryConformanceFixture.kt`
- Modify: `docs/conformance/kafka-recovery-conformance.md`

- [x] **Step 1: Replace project fixture dependencies with external test fixtures**

  In both Workshop modules, replace `testImplementation(testFixtures(project(":shared")))` with:

  ```kotlin
  testImplementation(testFixtures(libs.bluetape4k.kafka))
  ```

  Remove `testImplementation(project(":shared"))` from `commerce/usage-billing-microservices-composition-tests` only if the post-migration import scan confirms no other `shared` main source is used.

- [x] **Step 2: Replace imports and keep integration evidence local**

  Replace `io.bluetape4k.workshop.shared.messaging.*` imports with:

  ```kotlin
  import io.bluetape4k.kafka.test.conformance.KafkaRecoveryConformanceFixture
  import io.bluetape4k.kafka.test.conformance.KafkaRecoveryObservation
  import io.bluetape4k.kafka.test.conformance.KafkaRecoveryPath
  ```

  Do not change broker setup, Kafka 4 client usage, Spring wiring, evidence JSONL, or the validator.

- [x] **Step 3: Update the conformance document**

  Change the dependency example from `testImplementation(testFixtures(project(":shared")))` to the external `testFixtures(libs.bluetape4k.kafka)` contract. State that the fixture owns only black-box observation assertions and that the Workshop validator owns redacted JSONL schema checks.

- [x] **Step 4: Remove the obsolete Workshop fixture only after compile succeeds**

  Delete the shared fixture source after all imports resolve against the local upstream artifact. Do not delete unrelated shared fixtures.

- [x] **Step 5: Compile and run the pure fixture test**

  Use the same temporary local-Maven init script pattern as Train A, without committing it:

  ```bash
  ./gradlew -I .bluetape/local-maven.init.gradle.kts :messaging-kafka-multi-broker-failover:compileTestKotlin :commerce-usage-billing-microservices-composition-tests:compileTestKotlin --no-daemon --console=plain
  ./gradlew -I .bluetape/local-maven.init.gradle.kts :messaging-kafka-multi-broker-failover:test --tests '*KafkaRecoveryConformanceFixtureTest' --no-daemon --console=plain
  ```

  Expected: the fixture test runs from the external artifact and no source imports the deleted Workshop package.

### Task 5: Verify Kafka 3/4 leakage and integration consumers

**Files:**
- Create: `.bluetape/inputs/kafka-recovery-fixture-ecosystem-promotion-evidence.json`
- Modify: `docs/superpowers/plans/2026-11-27-kafka-recovery-fixture-ecosystem-promotion-plan.md`

- [x] **Step 1: Inspect dependency graphs**

  Run:

  ```bash
  ./gradlew -I .bluetape/local-maven.init.gradle.kts :messaging-kafka-multi-broker-failover:dependencies --configuration testRuntimeClasspath --no-daemon --console=plain
  ./gradlew -I .bluetape/local-maven.init.gradle.kts :commerce-usage-billing-microservices-composition-tests:dependencies --configuration testRuntimeClasspath --no-daemon --console=plain
  ```

  Confirm the external fixture is selected, Kafka 4 remains the consumer runtime, and the fixture does not add a conflicting Kafka 3 runtime edge.

- [x] **Step 2: Run the Kafka conformance/integration tests sequentially**

  ```bash
  ./gradlew -I .bluetape/local-maven.init.gradle.kts :messaging-kafka-multi-broker-failover:test --tests '*KafkaRecoveryConformanceFixtureTest' --no-daemon --console=plain
  ./gradlew -I .bluetape/local-maven.init.gradle.kts :commerce-usage-billing-microservices-composition-tests:integrationTest --tests '*BrokerPathRecoveryIntegrationTest' --tests '*UsageBillingMicroserviceCompositionIntegrationTest' --no-daemon --console=plain
  ```

  Keep container-backed Kafka tests sequential and record skipped/failed runs separately from a passing pure fixture test.

- [x] **Step 3: Run scope and documentation checks**

  ```bash
  rg -n "workshop\.shared\.messaging\.KafkaRecovery|testFixtures\(project\(\":shared\"\)\)" messaging/kafka-multi-broker-failover commerce/usage-billing-microservices-composition-tests shared docs/conformance --glob '*.kt' --glob '*.kts' --glob '*.md' || true
  git diff --check
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/conformance/kafka-recovery-conformance.md
  ```

  Expected: no stale package import or shared fixture dependency remains for these Kafka consumers; the validator command reports no terminology finding.

- [ ] **Step 4: Commit upstream and Workshop changes separately**

  Use Korean Lore messages. Do not publish a tag, create a release, or merge a PR in this plan.

## 검증 증거

- RED: upstream fixture 타입을 추가하기 전 `compileTestKotlin`이 세 fixture 타입의
  unresolved reference로 실패했다.
- GREEN: upstream `KafkaRecoveryConformanceFixtureTest` 4건 성공.
- upstream commit: `ebbe81baf` (`feat/kafka-recovery-fixture-promotion`).
- `javap -public`: fixture API가 `Duration`, Kotlin/JDK collection, fixture 타입만
  노출하며 Kafka client/Spring/Testcontainers 타입은 노출하지 않는다.
- `testFixturesApiElements`: `No dependencies`.
- Workshop 두 소비자의 `compileTestKotlin` 성공; 외부
  `bluetape4k-kafka-2.2.0-SNAPSHOT-test-fixtures.jar`가 test classpath에 포함됨.
- Kafka 4 multi-broker failover 2건, usage-billing composition integration 4건,
  순수 fixture 2건 성공.
- Kafka 4 runtime dependency graph는 `org.apache.kafka:kafka-clients:4.2.2`로
  수렴했고, fixture가 Kafka 3 runtime edge를 추가하지 않았다.
- stale import/dependency scan, `git diff --check`, Korean terminology audit 성공.
- upstream Kafka `detekt`는 기존 `KafkaCodec`, `SuspendKafkaProducerTemplate`,
  `WordCountExamples` 등의 finding을 보고했으며 새 fixture 경로 finding은 없었다.

## Self-review

- The plan covers the `java-test-fixtures` source set, version-neutral public signatures, Workshop consumers, documentation, dependency leakage, and sequential container verification.
- The Kafka 3/4 boundary is explicit: no Kafka client type is allowed in the fixture API or fixture-specific dependency edge.
- The Workshop JSONL validator and broker topology remain local, so moving the assertion fixture does not claim a new Kafka topology guarantee.
