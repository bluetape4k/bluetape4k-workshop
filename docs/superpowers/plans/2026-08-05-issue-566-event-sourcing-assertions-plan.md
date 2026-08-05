# Event-sourcing assertion migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`-`) syntax for tracking.

**Goal:** Migrate the 21 event-sourcing test files in `:commerce-usage-metering-billing-event-sourcing` from JUnit/Kotlin assertion APIs to released `bluetape4k-assertions` APIs without changing production behavior, fixtures, coroutine/Awaitility timing, Testcontainers lifecycle, or workflow configuration.

**Architecture:** Keep the product diff limited to the fixed 21-file Kotlin test manifest. Replace assertions at their existing call sites with intent-specific Bluetape matchers, preserve nullable/type-narrowing behavior with explicit locals where needed, then verify unit, integration, and stress lanes using the existing mutex and serialized Gradle topology. Record migration decisions and evidence in separate Korean process artifacts after implementation.

**Tech Stack:** Kotlin 2.4.0, JUnit 5, Kotlin coroutines, Awaitility, Testcontainers, Gradle, `io.github.bluetape4k:bluetape4k-assertions:1.11.0` resolved by the `bluetape4k-dependencies:1.3.1` BOM.

---

## Fixed manifest

Only these 21 test files may receive Kotlin source changes:

```text
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/BillingEventSourcingStressTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/EventSourcingRuntimeContractTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/KotlinPatternArchitectureTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/TenantIsolationIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/CommandServicePostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/CorrectionReconciliationIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/DomainEventJsonCodecTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/AggregateReducerTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/EventContractTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/AggregateReplayTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/CanonicalEventHashTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/EventCodecRegistryTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandFingerprintTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandReceiptPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/EventStorePostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/RepositoryArchitectureTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/SnapshotPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionCoordinatorPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionGenerationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionRecoveryPostgresIntegrationTest.kt
commerce/usage-metering-billing-event-sourcing/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/web/EventSourcingHttpIntegrationTest.kt
```

No production source, build script, dependency catalog, workflow, credential, fixture lifecycle, coroutine/Awaitility code, generated report, or raw log may change.

## Assertion mapping contract

Use these released APIs and preserve expected/actual ordering:

```kotlin
actual.shouldBeEqualTo(expected)
actual.shouldNotBeEqualTo(expected)
condition.shouldBeTrue()
condition.shouldBeFalse()
value.shouldBeNull()
value.shouldNotBeNull()
value.shouldBeInstanceOf<Foo>()
text.shouldNotBeBlank()
collection.shouldBeEmpty()
collection.shouldContain(element)
number.shouldBeGreaterThan(expected)
number.shouldBeLessThan(expected)
number.shouldBeLessOrEqualTo(expected)
assertFailsWith<ExpectedException> { operation() }
```

Apply intent matchers only when the expression is an assertion argument. Preserve helper/control-flow predicates such as `if (page.isEmpty()) return`, `it.name.contains("skip")`, and `violations.isEmpty()` when they are not assertion arguments. Remove diagnostic-only JUnit message parameters, but retain response-body/header and exception-field behavior as separate assertions.

## Task 1: Lock baseline and path guard

**Files:**
- Read: `docs/superpowers/specs/2026-08-05-issue-566-event-sourcing-assertions-design.md`
- Modify: none

- [ ] **Step 1: Verify branch and clean state**

```bash
git status --short
git rev-parse HEAD
git show -s --format='%H %s' ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d
```

Expected: only the approved design/plan commits are present and no unrelated dirty files exist.

- [ ] **Step 2: Recompute manifest**

```bash
rg -l 'org\.junit\.jupiter\.api\.Assertions|kotlin\.test\.assert|assert[A-Z][A-Za-z0-9_]*\(|assertThat|org\.assertj|io\.kotest|should[A-Z][A-Za-z0-9_]*' \
  commerce/usage-metering-billing-event-sourcing/src/test/kotlin -g '*Test.kt' | sort
```

Expected: exactly the 21 paths in the fixed manifest. Stop with `PENDING` if the set differs.

- [ ] **Step 3: Capture comparable split baseline**

In a fresh `/bin/bash -Eeuo pipefail` process, run `clean`, `test`, `integrationTest`, and `stressTest` as four separate Gradle invocations with `--no-build-cache --max-workers=1`, `/usr/bin/time -p`, immediate `PIPESTATUS` capture, a repository-external temporary log, and a 15-minute supervisor timeout per invocation. Record the sum as `B_split`. Expected counts are `test=19`, `integrationTest=35`, `stressTest=1`, with failures/errors/skips all zero. Do not commit the raw log.

## Task 2: Migrate runtime, architecture, domain, event-store, and fingerprint tests

**Files:**
- Modify: `BillingEventSourcingStressTest.kt`, `EventSourcingRuntimeContractTest.kt`, `KotlinPatternArchitectureTest.kt`, `AggregateReducerTest.kt`, `EventContractTest.kt`, `AggregateReplayTest.kt`, `CanonicalEventHashTest.kt`, `EventCodecRegistryTest.kt`, `CommandFingerprintTest.kt`

- [ ] **Step 1: Replace imports**

```kotlin
// before
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

// after
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
```

Add only the matcher imports used by the file; remove all JUnit/Kotlin assertion imports after conversion.

- [ ] **Step 2: Convert equality, boolean, and null calls**

```kotlin
assertEquals(expected, actual)     // actual.shouldBeEqualTo(expected)
assertNotEquals(expected, actual) // actual.shouldNotBeEqualTo(expected)
assertTrue(condition)             // condition.shouldBeTrue()
assertFalse(condition)            // condition.shouldBeFalse()
assertNull(value)                 // value.shouldBeNull()
assertNotNull(value)              // value.shouldNotBeNull()
```

Do not alter event construction, replay state, stress coroutine structure, or fixture calls.

- [ ] **Step 3: Convert exception calls**

```kotlin
assertThrows(IllegalStateException::class.java) { operation() }
// becomes
assertFailsWith<IllegalStateException> { operation() }
```

- [ ] **Step 4: Compile and run the pure unit group**

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:compileTestKotlin \
  --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:test \
  --tests '*EventSourcingRuntimeContractTest' \
  --tests '*KotlinPatternArchitectureTest' \
  --tests '*AggregateReducerTest' \
  --tests '*EventContractTest' \
  --tests '*AggregateReplayTest' \
  --tests '*CanonicalEventHashTest' \
  --tests '*EventCodecRegistryTest' \
  --tests '*CommandFingerprintTest' \
  --no-build-cache --max-workers=1
```

Expected: compile and selected unit tests pass.

## Task 3: Migrate application, idempotency, and persistence tests

**Files:**
- Modify: `CommandServicePostgresIntegrationTest.kt`, `CorrectionReconciliationIntegrationTest.kt`, `DomainEventJsonCodecTest.kt`, `CommandReceiptPostgresIntegrationTest.kt`, `EventStorePostgresIntegrationTest.kt`, `RepositoryArchitectureTest.kt`, `SnapshotPostgresIntegrationTest.kt`

- [ ] **Step 1: Preserve nullable semantics**

```kotlin
// before
assertNotNull(finding)
assertEquals(expected, finding!!.eventId)

// after
val actualFinding = finding.shouldNotBeNull()
actualFinding.eventId.shouldBeEqualTo(expected)
```

Use `shouldBeNull()` for null-only checks. Use a local returned by `shouldNotBeNull()` whenever subsequent code needs a narrowed value.

- [ ] **Step 2: Preserve Java Class narrowing**

```kotlin
// before
assertInstanceOf(CommandAcquireResult.Owned::class.java, second)
service.succeed(second as CommandAcquireResult.Owned, 201, "{}", acquiredAt)

// after
val secondOwned = second.shouldBeInstanceOf<CommandAcquireResult.Owned>()
service.succeed(secondOwned, 201, "{}", acquiredAt)
```

- [ ] **Step 3: Preserve exception fields**

```kotlin
val failure = assertThrows(IllegalArgumentException::class.java) { codec.decode(payload) }
assertEquals("event_payload_too_large", failure.message)

// after
val failure = assertFailsWith<IllegalArgumentException> { codec.decode(payload) }
failure.message.shouldBeEqualTo("event_payload_too_large")
```

For `assertEquals(200, response.status.value(), diagnostic)`, remove only `diagnostic`; keep all body/header checks.

- [ ] **Step 4: Compile the application/persistence group**

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:compileTestKotlin \
  --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:test \
  --tests '*DomainEventJsonCodecTest' \
  --tests '*RepositoryArchitectureTest' \
  --no-build-cache --max-workers=1
```

Expected: compile and selected unit tests pass; tagged PostgreSQL tests remain unchanged except assertion syntax.

## Task 4: Migrate tenant, projection, HTTP, and stress assertions

**Files:**
- Modify: `TenantIsolationIntegrationTest.kt`, `ProjectionCoordinatorPostgresIntegrationTest.kt`, `ProjectionGenerationTest.kt`, `ProjectionRecoveryPostgresIntegrationTest.kt`, `EventSourcingHttpIntegrationTest.kt`
- Review: `BillingEventSourcingStressTest.kt` (already changed in Task 2; no second unrelated edit)

- [ ] **Step 1: Preserve tenant and authorization invariants**

Keep existing tenant-scoped `Owned` results, tenant totals, projection/reconciliation checks, admin `403`, operator success, response headers/body, and unauthenticated health behavior. Only syntax changes are allowed:

```kotlin
assertEquals(1, fixture.executor.transaction { eventStore.load(stream(TENANT_A)).size })
// becomes
fixture.executor.transaction { eventStore.load(stream(TENANT_A)).size }.shouldBeEqualTo(1)
```

Do not add new tenant-b negative cases or receipt-ID assertions.

- [ ] **Step 2: Convert string predicates by intent**

```kotlin
assertTrue(commandId.isNotBlank())                 // commandId.shouldNotBeBlank()
assertFalse(actuatorBody.contains("projectionPosition")) // actuatorBody.shouldNotContain("projectionPosition")
assertTrue(operatorHealthBody.contains("projectionPosition"), operatorHealthBody) // operatorHealthBody.shouldContain("projectionPosition")
```

Drop only diagnostic-only messages; preserve MockK field variables, response values, coroutine/Awaitility polling, fixtures, and timestamps.

- [ ] **Step 3: Convert projection exception and predicate calls**

Use `assertFailsWith<T>`, `shouldBeTrue/shouldBeFalse`, `shouldBeEqualTo`, and existing numeric matchers for ordering assertions. Do not change production or lifecycle code.

- [ ] **Step 4: Compile after the complete source conversion**

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:compileTestKotlin \
  --no-build-cache --max-workers=1
```

Expected: compilation succeeds and no forbidden assertion import remains in the manifest.

## Task 5: Run complete split verification and residual audit

**Files:**
- Read: all 21 Kotlin test files and generated JUnit XML/reports
- Modify: none during the first verification pass

- [ ] **Step 1: Run security-matrix integration tests first**

Run `integrationTest` filtered to `TenantIsolationIntegrationTest` and `EventSourcingHttpIntegrationTest`, with `--no-build-cache --max-workers=1`. Expected: both pass and XML contains the existing tenant-isolation and authorization test cases.

- [ ] **Step 2: Run canonical lanes sequentially**

Use the approved Bash capture and supervisor timeout:

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:clean --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:test --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:integrationTest --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:stressTest --no-build-cache --max-workers=1
```

Expected: counts `19/35/1`, failures/errors/skips all zero, no timeout or fixture residue, and final split wall-clock no greater than `2 × B_split`.

- [ ] **Step 3: Audit assertion residue and allowlist**

```bash
rg -n 'org\.junit\.jupiter\.api\.Assertions|kotlin\.test\.assert|assert[A-Z][A-Za-z0-9_]*\(|assertThat|org\.assertj|io\.kotest' \
  commerce/usage-metering-billing-event-sourcing/src/test/kotlin -g '*Test.kt'
git diff --name-only ad91ca06ecc1cbe5de99bfdeb8f425d03a35088d -- commerce/usage-metering-billing-event-sourcing/src/test/kotlin
git diff --check
```

Expected: no forbidden assertion API remains (JUnit annotations are allowed), source diff paths equal the 21-file manifest, and diff check passes.

- [ ] **Step 4: Apply the Kotlin-pattern final checklist**

Record receiver-oriented assertions, no nullable semantic drift, unchanged structured concurrency, MockK field declarations, no production/dependency changes, and non-applicable rows as `N/A` with reasons in the migration record.

## Task 6: Create migration record and Korean lesson

**Files:**
- Create: `docs/review/2026-08-05-issue-566-event-sourcing-assertions-migration-record.md`
- Create: `docs/lessons/2026-08-05-issue-566-event-sourcing-assertions.md`

- [ ] **Step 1: Write migration record**

Use exactly these headings: `Context`, `Base commit`, `Manifest`, `Resolved artifact`, `Unmapped API table`, `Diagnostic-only message table`, `Security traceability`, `Test evidence`, `Redaction audit`, `Owner`, `Status`. Include the exact artifact pair, 21 paths, B_split/final timing, XML counts, residual scan, and exact-head CI `N/A` when applicable. Never copy credentials, headers, request bodies, or raw logs.

- [ ] **Step 2: Write the Korean lesson**

Use `Context / Decision / Outcome / Evidence / Misses / Future guard`. Explain matcher mapping, Java `Class<T>` narrowing, diagnostic-message handling, control-flow preservation, and future manifest/redaction guards. Include only redacted commands and counts.

- [ ] **Step 3: Run documentation/security validation**

Run `git diff --check`, the approved scanner against raw log plus migration/spec-review/lesson documents, and the XML/report inventory. The second scanner pass must exit 0, then the raw log must be deleted and absent. Missing or unredacted evidence remains `PENDING`.

## Task 7: Reviewable commit and external-side-effect gate

**Files:**
- Modify: the 21 Kotlin files and the two evidence documents only

- [ ] **Step 1: Inspect staged diff**

```bash
git diff --stat
git diff --check
git status --short
```

Expected: only the allowlisted paths are staged; no generated report, credential, workflow, production, or dependency file is staged.

- [ ] **Step 2: Commit with Lore trailers**

Use an intent-first message with `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, and `Not-tested`. Name any remaining exact-head CI or timing gap in `Not-tested`.

- [ ] **Step 3: Stop before external side effects**

Do not create/update a PR, dispatch Nightly/Examples, create a follow-up issue, merge, tag, publish, or delete the worktree until exact implementation `HEAD`, checks, reviews, and the separate approval for that action are re-read.

## Self-review

- [ ] Confirm the manifest has exactly 21 real paths.
- [ ] Confirm every task has exact paths, commands, expected outcomes, and no vague placeholder.
- [ ] Confirm design coverage: API mapping, nullable semantics, message/exception preservation, tenant/security invariants, redaction, split timing, rollback, exact-head, and independent side-effect approvals.
- [ ] Confirm no task permits production, dependency, workflow, credential, fixture-lifecycle, coroutine, or Awaitility changes.
