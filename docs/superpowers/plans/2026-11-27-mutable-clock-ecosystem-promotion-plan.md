# MutableClock ecosystem 승격 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `shared`의 wall-clock 테스트 구현을 `bluetape4k-junit5`의 공개 `MutableClock`으로 이관하고 Projects와 Workshop 소비자가 upstream artifact를 사용하게 한다.

**Architecture:** `MutableClock`은 `bluetape4k-projects/testing/junit5`의 main source에 `java.time.Clock` 구현으로 추가한다. `AtomicReference<Instant>` 하나를 zone view가 공유하며 `advance(Duration)`과 `advanceSeconds(Long)`만 공개한다. Projects의 private clock과 Workshop의 shared fixture를 순서대로 제거하고, release 전 검증은 local Maven artifact와 의존성 보고서로 수행한다.

**Tech Stack:** Kotlin 2.4, Java 25, Gradle 9.8.1, JUnit 5, `java.time.Clock`, `AtomicReference`, Maven local verification.

---

### Task 1: Upstream `MutableClock` RED 계약 테스트

**Files:**
- Create: `/Users/debop/work/bluetape4k/bluetape4k-projects/testing/junit5/src/test/kotlin/io/bluetape4k/junit5/time/MutableClockTest.kt`

- [x] **Step 1: Add the failing contract test**

  Add the following test class. It fixes the public package, constructor, zone-sharing, negative-duration, seconds, and atomic update contract before implementation.

  ```kotlin
  package io.bluetape4k.junit5.time

  import io.bluetape4k.assertions.shouldBeEqualTo
  import java.time.Duration
  import java.time.Instant
  import java.time.ZoneId
  import java.time.ZoneOffset
  import java.util.concurrent.Executors
  import java.util.concurrent.TimeUnit
  import org.junit.jupiter.api.Test

  class MutableClockTest {
      @Test
      fun `initial instant and zone are observable`() {
          val initial = Instant.parse("2026-11-27T00:00:00Z")
          val clock = MutableClock(initial, ZoneId.of("Asia/Seoul"))

          clock.instant() shouldBeEqualTo initial
          clock.zone shouldBeEqualTo ZoneId.of("Asia/Seoul")
      }

      @Test
      fun `duration and seconds advance share one timeline`() {
          val initial = Instant.parse("2026-11-27T00:00:00Z")
          val clock = MutableClock(initial)

          clock.advance(Duration.ofMinutes(2)) shouldBeEqualTo initial.plusSeconds(120)
          clock.advanceSeconds(30) shouldBeEqualTo initial.plusSeconds(150)
          clock.instant() shouldBeEqualTo initial.plusSeconds(150)
      }

      @Test
      fun `zone view shares timeline and supports negative duration`() {
          val initial = Instant.parse("2026-11-27T00:00:00Z")
          val clock = MutableClock(initial)
          val view = clock.withZone(ZoneOffset.ofHours(9))

          view.zone shouldBeEqualTo ZoneOffset.ofHours(9)
          clock.advanceSeconds(60)
          view.instant() shouldBeEqualTo initial.plusSeconds(60)
          view.advance(Duration.ofSeconds(-10)) shouldBeEqualTo initial.plusSeconds(50)
          clock.instant() shouldBeEqualTo initial.plusSeconds(50)
      }

      @Test
      fun `concurrent advances are atomic`() {
          val initial = Instant.parse("2026-11-27T00:00:00Z")
          val clock = MutableClock(initial)
          val executor = Executors.newFixedThreadPool(8)

          try {
              val futures = List(256) { executor.submit { clock.advanceSeconds(1) } }
              futures.forEach { it.get(5, TimeUnit.SECONDS) }
          } finally {
              executor.shutdownNow()
          }

          clock.instant() shouldBeEqualTo initial.plusSeconds(256)
      }
  }
  ```

- [x] **Step 2: Run the RED proof**

  Run from `bluetape4k-projects`:

  ```bash
  ./gradlew :bluetape4k-junit5:test --tests 'io.bluetape4k.junit5.time.MutableClockTest' --no-daemon --console=plain
  ```

  Expected: compilation fails because `io.bluetape4k.junit5.time.MutableClock` does not exist.

### Task 2: Implement the upstream public clock

**Files:**
- Create: `/Users/debop/work/bluetape4k/bluetape4k-projects/testing/junit5/src/main/kotlin/io/bluetape4k/junit5/time/MutableClock.kt`

- [x] **Step 1: Add the minimal implementation**

  Add this implementation and Korean KDoc. Do not add `advanceBy`, epoch-millisecond constructors, scheduler methods, or monotonic behavior.

  ```kotlin
  package io.bluetape4k.junit5.time

  import java.time.Clock
  import java.time.Duration
  import java.time.Instant
  import java.time.ZoneId
  import java.time.ZoneOffset
  import java.util.concurrent.atomic.AtomicReference

  /**
   * 테스트에서 wall-clock timeline을 원자적으로 조정하는 [Clock] 구현입니다.
   *
   * [withZone]으로 만든 view는 동일한 timeline을 공유합니다. 이 클래스는
   * monotonic elapsed-time이나 scheduler tick을 모델링하지 않습니다.
   */
  open class MutableClock private constructor(
      private val current: AtomicReference<Instant>,
      private val zone: ZoneId,
  ) : Clock() {
      constructor(initial: Instant, zone: ZoneId = ZoneOffset.UTC) : this(AtomicReference(initial), zone)

      override fun getZone(): ZoneId = zone

      override fun withZone(zone: ZoneId): MutableClock =
          if (zone == this.zone) this else MutableClock(current, zone)

      override fun instant(): Instant = current.get()

      fun advance(duration: Duration): Instant = current.updateAndGet { it.plus(duration) }

      fun advanceSeconds(seconds: Long): Instant = advance(Duration.ofSeconds(seconds))
  }
  ```

- [x] **Step 2: Run the GREEN proof**

  Run the targeted test and class compilation:

  ```bash
  ./gradlew :bluetape4k-junit5:test --tests 'io.bluetape4k.junit5.time.MutableClockTest' --no-daemon --console=plain
  ./gradlew :bluetape4k-junit5:compileKotlin --no-daemon --console=plain
  ```

  Expected: all four contract tests pass and the main source compiles on Java 25.

- [x] **Step 3: Inspect the public JVM surface**

  Run:

  ```bash
  javap -classpath testing/junit5/build/classes/kotlin/main -public io.bluetape4k.junit5.time.MutableClock
  ```

  Expected: the public surface contains the `Instant`/`ZoneId` constructor, `advance`, `advanceSeconds`, `getZone`, `withZone`, and `instant`; no Workshop package appears.

### Task 3: Replace Projects private clocks

**Files:**
- Modify: `/Users/debop/work/bluetape4k/bluetape4k-projects/io/tink/src/test/kotlin/io/bluetape4k/tink/keyset/redis/LettuceVersionedKeysetStoreTest.kt`
- Modify: `/Users/debop/work/bluetape4k/bluetape4k-projects/io/tink/src/test/kotlin/io/bluetape4k/tink/keyset/redis/RedissonVersionedKeysetStoreTest.kt`
- Modify: `/Users/debop/work/bluetape4k/bluetape4k-projects/utils/idgenerators/src/test/kotlin/io/bluetape4k/idgenerators/flake/FlakeTest.kt`

- [x] **Step 1: Replace the two Tink test clocks**

  Add `import io.bluetape4k.junit5.time.MutableClock`, replace `clock.advanceBy(Duration.ofDays(2))` with `clock.advance(Duration.ofDays(2))`, and delete each file's private `MutableClock` class plus imports used only by that class (`ZoneId` and the private clock's `Clock` override where unused). Keep Redis setup, default instant, and all assertions unchanged.

- [x] **Step 2: Replace the Flake test clock**

  Add `import io.bluetape4k.junit5.time.MutableClock`, change `MutableClock(initialMillis)` to `MutableClock(Instant.ofEpochMilli(initialMillis))`, replace `clock.advanceBy(Duration.ofMillis(1))` with `clock.advance(Duration.ofMillis(1))`, and delete the private `MutableClock` class and its `AtomicLong`/private-zone imports. Retain `ZoneOffset.UTC` for the existing `Clock.fixed` test.

- [x] **Step 3: Compile the migrated tests**

  Run sequentially from `bluetape4k-projects`:

  ```bash
  ./gradlew :bluetape4k-tink:compileTestKotlin --no-daemon --console=plain
  ./gradlew :bluetape4k-idgenerators:compileTestKotlin --no-daemon --console=plain
  ```

  Expected: no private `MutableClock` declaration remains in these three files and both test source sets compile.

- [x] **Step 4: Run focused behavior tests**

  ```bash
  ./gradlew :bluetape4k-tink:test --tests '*LettuceVersionedKeysetStoreTest' --tests '*RedissonVersionedKeysetStoreTest' --no-daemon --console=plain
  ./gradlew :bluetape4k-idgenerators:test --tests '*FlakeTest' --no-daemon --console=plain
  ```

  Expected: the existing key-rotation and Flake timestamp assertions pass without changing their expected values.

### Task 4: Switch Workshop consumers to the released API

**Files:**
- Modify: `shared/src/test/kotlin/io/bluetape4k/workshop/shared/time/MutableClockTest.kt`
- Delete: `shared/src/testFixtures/kotlin/io/bluetape4k/workshop/shared/time/MutableClock.kt`
- Modify: `operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/fixture/JobConsoleFixtureClock.kt`
- Modify: `aws/kinesis-coroutines/src/test/kotlin/io/bluetape4k/workshop/aws/kinesis/KinesisConsumerFlowTest.kt`
- Modify: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/VoucherAdmissionGateTest.kt`
- Modify: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationServiceTest.kt`
- Modify: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/VoucherPoolAdmissionGateTest.kt`
- Modify: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/idempotency/CommandReceiptPostgresIntegrationTest.kt`
- Modify: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceOutboxFencingTest.kt`
- Modify: affected module `build.gradle.kts` files only where `testFixtures(project(":shared"))` is no longer needed by that module

- [x] **Step 1: Make the upstream artifact resolvable for local verification**

  From `bluetape4k-projects`, publish only `bluetape4k-junit5` to Maven local:

  ```bash
  ./gradlew :bluetape4k-junit5:publishToMavenLocal --no-daemon --console=plain
  ```

  Keep the repository's normal resolver configuration unchanged. For Workshop verification, use a temporary Gradle init script that prepends `mavenLocal()` to project repositories; do not commit that init script or add `mavenLocal()` to `settings.gradle.kts`.

- [x] **Step 2: Change imports and the operations wrapper**

  Replace `io.bluetape4k.workshop.shared.time.MutableClock` imports with `io.bluetape4k.junit5.time.MutableClock`. Keep every consumer's initial instant and method call. `JobConsoleFixtureClock` continues to extend `MutableClock` so its named test fixture and default instant remain unchanged.

- [x] **Step 3: Remove only the obsolete shared clock source/test**

  Delete the shared implementation after all imports compile. Retain other `shared` tests and fixtures. The `build-logic` monotonic test clocks and operations `VirtualClock` must remain unchanged.

- [x] **Step 4: Adjust fixture dependencies conservatively**

  Remove `testImplementation(testFixtures(project(":shared")))` only from modules whose remaining source no longer imports any shared fixture. Keep it in modules that still use another shared contract. All affected modules already declare `libs.bluetape4k.junit5`; add it only if a module lacks that test dependency.

- [x] **Step 5: Compile the Workshop migration with the local artifact**

  Run the affected compile tasks sequentially with the temporary init script:

  ```bash
  ./gradlew :shared:test :aws-kinesis-coroutines:compileTestKotlin :commerce-promotion-voucher-campaign:compileTestKotlin :commerce-pre-generated-voucher-pool:compileTestKotlin :commerce-usage-metering-billing-ledger:compileTestKotlin :optimization-field-service-dispatch:compileTestKotlin :operations-job-console-core:testFixturesClasses --no-daemon --console=plain
  ```

  Expected: no source references `io.bluetape4k.workshop.shared.time.MutableClock`, and the deleted shared fixture is not required by any migrated module.

### Task 5: Verify scope and record the train

**Files:**
- Create: `.bluetape/inputs/mutable-clock-ecosystem-promotion-evidence.json`
- Modify: `docs/superpowers/plans/2026-11-27-mutable-clock-ecosystem-promotion-plan.md`

- [x] **Step 1: Run scope scans**

  Run from Workshop:

  ```bash
  rg -n "workshop\.shared\.time\.MutableClock|class MutableClock|private class MutableClock" --glob '*.kt' .
  rg -n "VirtualClock|nanoTime|advanceMillis" build-logic operations --glob '*.kt'
  git diff --check
  ```

  Expected: only the upstream `io.bluetape4k.junit5.time.MutableClock` declaration remains outside excluded monotonic/`VirtualClock` implementations; the second scan confirms those excluded clocks were not changed.

- [x] **Step 2: Run the focused verification matrix**

  Record the exact commands, commit SHAs, local artifact version, pass/fail status, and any pre-existing failure separately. The upstream commit is `1d729cf66`; local Workshop resolution used `2.2.0-SNAPSHOT`. The focused matrix passed: AWS Kinesis 4, promotion voucher 12, pre-generated voucher pool 3, usage metering integration 4, field-service integration 1. The upstream junit5 suite baseline was 347 tests, the new clock contract was 4 tests, Tink was 28 tests, and Flake was 27 tests. The root Workshop `detekt` task remains red on existing MaxLineLength findings in `commerce/pre-generated-voucher-pool` and unrelated commerce modules; module-local `detekt` tasks are not registered for these Workshop modules. Do not treat an unpublished artifact or skipped test as a successful Workshop migration.

- [ ] **Step 3: Commit the upstream and Workshop changes separately**

  Use Korean Lore messages. The upstream commit must explain the public test API decision; the Workshop commit must explain consumer migration and shared deletion. Do not publish a tag, create a release, or merge a PR in this plan.

- [x] **Step 4: Update this plan with evidence**

  Mark only verified steps complete and record unresolved publication or downstream resolver gaps before starting the Kafka train.

## Self-review

- The plan covers the design contract, Projects duplicate clocks, Workshop consumers, release-before-delete boundary, excluded monotonic clocks, ABI inspection, and dependency leakage checks.
- Every source change names an exact file and every verification step includes an executable Gradle or `rg` command with an expected result.
- `advance(Duration)` is the single canonical increment API; the plan does not introduce an unplanned `advanceBy` alias.
