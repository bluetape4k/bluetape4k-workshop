# Leader Backend Comparison Lab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `leader/backend-comparison-lab`, a deterministic workshop module that compares Redis, ZooKeeper, and Kubernetes Lease leader-election backends without replacing existing runnable backend modules.

**Architecture:** The module uses immutable backend profiles plus a small deterministic failover lab service. Production code explains learner-visible backend behavior; tests prove the catalog and scenario reports without starting Redis, ZooKeeper, or Kubernetes. README diagrams carry the architecture and sequence explanations, while existing backend modules remain the real integration practice path.

**Tech Stack:** Kotlin 2.4, Java 21, Spring Boot 4, bluetape4k leader/core/logging/assertions/junit5, Micrometer terminology from the existing K8s module, generated SVG+PNG README diagrams, GitHub Actions smoke validation.

---

## File Structure

- Create `leader/backend-comparison-lab/build.gradle.kts`
  - Spring Boot 4 application module.
  - Versionless dependencies only.
  - Production dependencies stay limited to the deterministic comparison lab.
    Backend-specific Redis/ZooKeeper/Kubernetes modules remain linked practice
    targets, not unused transitive runtime dependencies.
  - Default `test` task is deterministic and excludes future backend-heavy tags if added.
- Create `leader/backend-comparison-lab/README.md`
  - English learner guide, backend matrix, scenario flow, test/run commands, real backend links.
- Create `leader/backend-comparison-lab/README.ko.md`
  - Korean source-equivalent learner guide.
- Create `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/BackendComparisonLabApp.kt`
  - Minimal Spring Boot entry point.
- Create `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/BackendProfile.kt`
  - Serializable immutable backend profile and support enums.
- Create `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/BackendCapability.kt`
  - Serializable immutable backend capability value type.
- Create `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/LeaderScenario.kt`
  - Serializable scenario request/value types.
- Create `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/domain/LeaderScenarioReport.kt`
  - Serializable report/event value types.
- Create `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/LeaderBackendCatalog.kt`
  - Source-backed catalog for Redis, ZooKeeper, and Kubernetes Lease.
- Create `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/LeaderFailoverLab.kt`
  - Deterministic report generator for steady leader, contention skip, action failure, and backend-loss handoff scenarios.
- Create test files under `leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/`.
- Create `src/test/resources/junit-platform.properties` and `logback-test.xml`.
- Create diagrams:
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.png`
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.png`
- Modify root `README.md` and `README.ko.md`.
- Modify `scripts/smoke-validate.sh`.
- Modify `.github/workflows/Examples.yml`.
- Modify `.github/workflows/nightly.yml` only if smoke path coverage requires it after workflow scan.
- Create `docs/review/2026-07-02-issue-328-leader-backend-comparison-code-review.md`.
- Create `docs/lessons/2026-07-02-issue-328-leader-backend-comparison.md`.

## Task 1: Build Skeleton And Failing Catalog Tests

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

**Files:**
- Create: `leader/backend-comparison-lab/build.gradle.kts`
- Create: `leader/backend-comparison-lab/src/test/resources/junit-platform.properties`
- Create: `leader/backend-comparison-lab/src/test/resources/logback-test.xml`
- Create: `leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/service/LeaderBackendCatalogTest.kt`

- [ ] **Step 1: Add module build skeleton**

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.leader.backendcomparison.BackendComparisonLabAppKt")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        excludeTags("backend-heavy")
    }
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.logging)

    implementation(libs.spring.boot.autoconfigure.lib)
    implementation(libs.spring.boot.starter.actuator)
    annotationProcessor(libs.spring.boot.autoconfigure.processor)
    annotationProcessor(libs.spring.boot.configuration.processor)
    runtimeOnly(libs.spring.boot.devtools)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
```

- [ ] **Step 2: Add test resources**

`junit-platform.properties`:

```properties
junit.jupiter.execution.parallel.enabled=false
junit.jupiter.testinstance.lifecycle.default=per_class
```

`logback-test.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

- [ ] **Step 3: Write failing catalog tests before production code**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderBackendCatalogTest {

    private val catalog = LeaderBackendCatalog()

    @Test
    fun `catalog contains Redis ZooKeeper and Kubernetes Lease profiles`() {
        val profiles = catalog.all()

        profiles.map { it.id } shouldBeEqualTo listOf("redis-lettuce", "zookeeper-curator", "kubernetes-lease")
        profiles.map { it.status } shouldContain BackendStatus.STABLE
        profiles.shouldNotBeEmpty()
    }

    @Test
    fun `Redis profile documents TTL based failover and event observation`() {
        val redis = catalog.findById("redis-lettuce")

        redis.failoverTrigger shouldBeEqualTo "Lease TTL expiry or explicit release"
        redis.metricsAndEvents shouldContain "LeaderElectionEvent Flow"
        redis.practiceModulePath shouldBeEqualTo "leader/leader-election"
    }

    @Test
    fun `ZooKeeper profile documents session based failover and group leadership`() {
        val zookeeper = catalog.findById("zookeeper-curator")

        zookeeper.failoverTrigger shouldBeEqualTo "ZooKeeper session loss"
        zookeeper.capabilities.map { it.label } shouldContain "Group leadership"
        zookeeper.practiceModulePath shouldBeEqualTo "leader/leader-zookeeper"
    }

    @Test
    fun `Kubernetes profile documents opt-in Lease and Micrometer path`() {
        val kubernetes = catalog.findById("kubernetes-lease")

        kubernetes.status shouldBeEqualTo BackendStatus.PREVIEW_OPT_IN
        kubernetes.metricsAndEvents shouldContain "leader-micrometer meters"
        kubernetes.practiceModulePath shouldBeEqualTo "leader/k8s-lease-micrometer"
    }
}
```

- [ ] **Step 4: Run RED check**

Run:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderBackendCatalogTest' --no-build-cache --rerun-tasks
```

Expected: FAIL because `LeaderBackendCatalog`, `BackendStatus`, and profile types do not exist.

## Task 2: Implement Backend Catalog

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Create: `BackendComparisonLabApp.kt`
- Create: `domain/BackendCapability.kt`
- Create: `domain/BackendProfile.kt`
- Create: `service/LeaderBackendCatalog.kt`

- [ ] **Step 1: Implement app entrypoint**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BackendComparisonLabApp

fun main(args: Array<String>) {
    runApplication<BackendComparisonLabApp>(*args)
}
```

- [ ] **Step 2: Implement backend capability model**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

data class BackendCapability(
    val label: String,
    val detail: String,
) : Serializable {
    init {
        label.requireNotBlank("label")
        detail.requireNotBlank("detail")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **Step 3: Implement backend profile model**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import java.io.Serializable

enum class BackendStatus {
    STABLE,
    PREVIEW_OPT_IN,
}

data class BackendProfile(
    val id: String,
    val displayName: String,
    val status: BackendStatus,
    val primitive: String,
    val failoverTrigger: String,
    val tuningKnob: String,
    val metricsAndEvents: List<String>,
    val bestFor: String,
    val avoidWhen: String,
    val practiceModulePath: String,
    val capabilities: List<BackendCapability>,
) : Serializable {
    init {
        id.requireNotBlank("id")
        displayName.requireNotBlank("displayName")
        primitive.requireNotBlank("primitive")
        failoverTrigger.requireNotBlank("failoverTrigger")
        tuningKnob.requireNotBlank("tuningKnob")
        bestFor.requireNotBlank("bestFor")
        avoidWhen.requireNotBlank("avoidWhen")
        practiceModulePath.requireNotBlank("practiceModulePath")
        metricsAndEvents.requireNotEmpty("metricsAndEvents")
        capabilities.requireNotEmpty("capabilities")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **Step 4: Implement source-backed catalog**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendCapability
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendProfile
import io.bluetape4k.workshop.leader.backendcomparison.domain.BackendStatus
import org.springframework.stereotype.Service

@Service
class LeaderBackendCatalog {

    private val profiles: List<BackendProfile> = listOf(
        BackendProfile(
            id = "redis-lettuce",
            displayName = "Redis + Lettuce",
            status = BackendStatus.STABLE,
            primitive = "Redis key with lease TTL",
            failoverTrigger = "Lease TTL expiry or explicit release",
            tuningKnob = "waitTime, leaseTime, autoExtend",
            metricsAndEvents = listOf("LeaderElectionEvent Flow", "listener callbacks", "skip/elected/revoked events"),
            bestFor = "Services that already operate Redis and need fast, simple scheduled-job leadership.",
            avoidWhen = "Redis outage should not affect scheduler ownership or a session-bound lock is required.",
            practiceModulePath = "leader/leader-election",
            capabilities = listOf(
                BackendCapability("Blocking and coroutine APIs", "LettuceLeaderElector plus LettuceSuspendLeaderElector."),
                BackendCapability("TTL recovery", "Leadership can recover after lease expiry when an owner disappears."),
                BackendCapability("Event observation", "ListeningLeaderElector exposes callbacks and Flow events."),
            ),
        ),
        BackendProfile(
            id = "zookeeper-curator",
            displayName = "ZooKeeper + Curator",
            status = BackendStatus.STABLE,
            primitive = "Ephemeral znode / Curator mutex",
            failoverTrigger = "ZooKeeper session loss",
            tuningKnob = "sessionTimeoutMs, connectionTimeoutMs, groupMaxLeaders",
            metricsAndEvents = listOf("single-leader result", "group-leader slot result", "Curator connection state"),
            bestFor = "Services already depending on ZooKeeper or needing session-bound ownership.",
            avoidWhen = "A Redis-style lock TTL or auto-extension behavior is expected.",
            practiceModulePath = "leader/leader-zookeeper",
            capabilities = listOf(
                BackendCapability("Single leadership", "One candidate owns the lock path and executes."),
                BackendCapability("Group leadership", "Up to groupMaxLeaders candidates may enter."),
                BackendCapability("Session recovery", "Ownership follows ZooKeeper session lifecycle, not a TTL field."),
            ),
        ),
        BackendProfile(
            id = "kubernetes-lease",
            displayName = "Kubernetes Lease",
            status = BackendStatus.PREVIEW_OPT_IN,
            primitive = "coordination.k8s.io/v1 Lease object",
            failoverTrigger = "Lease expiry and resource-version update",
            tuningKnob = "namespace, identity, leaseTime, retryDelay, autoExtend",
            metricsAndEvents = listOf("leader-micrometer meters", "workshop.k8s.lease.* meters", "Prometheus scrape"),
            bestFor = "Kubernetes-native workloads that can grant Lease RBAC and expose Micrometer metrics.",
            avoidWhen = "Local tests must exercise a real backend without a cluster or service-account setup.",
            practiceModulePath = "leader/k8s-lease-micrometer",
            capabilities = listOf(
                BackendCapability("Opt-in real backend", "Default workshop path stays disabled without Kubernetes credentials."),
                BackendCapability("Micrometer visibility", "Application meters and leader-micrometer decorator meters are documented."),
                BackendCapability("Kubernetes ownership", "Lease records namespace and identity for operational inspection."),
            ),
        ),
    )

    fun all(): List<BackendProfile> = profiles

    fun findById(id: String): BackendProfile {
        id.requireNotBlank("id")
        return profiles.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown leader backend id: $id")
    }
}
```

- [ ] **Step 5: Run GREEN check**

Run:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderBackendCatalogTest' --no-build-cache --rerun-tasks
```

Expected: PASS.

## Task 3: Add Failing Failover Scenario Tests

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`, `$test-driven-development`

**Files:**
- Create: `domain/LeaderScenario.kt`
- Create: `domain/LeaderScenarioReport.kt`
- Create: `service/LeaderFailoverLab.kt`
- Create: `src/test/kotlin/.../LeaderFailoverLabTest.kt`

- [ ] **Step 1: Write failing scenario tests**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenario
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderFailoverLabTest {

    private val lab = LeaderFailoverLab(LeaderBackendCatalog())

    @Test
    fun `steady leader scenario executes one node and skips follower`() {
        val report = lab.run(LeaderScenario.steadyLeader("redis-lettuce"))

        report.backendId shouldBeEqualTo "redis-lettuce"
        report.events.map { it.outcome } shouldBeEqualTo listOf("executed", "skipped")
        report.summary shouldBeEqualTo "node-a executed report-sync; node-b skipped because Redis lock is held."
    }

    @Test
    fun `action failure scenario records release then next eligible run`() {
        val report = lab.run(LeaderScenario.actionFailure("zookeeper-curator"))

        report.events.map { it.outcome } shouldContain "failed"
        report.events.map { it.outcome } shouldContain "executed-after-recovery"
        report.handoffTrigger shouldBeEqualTo "ZooKeeper session loss"
    }

    @Test
    fun `backend loss handoff uses backend specific trigger`() {
        val report = lab.run(LeaderScenario.backendLossHandoff("kubernetes-lease"))

        report.handoffTrigger shouldBeEqualTo "Lease expiry and resource-version update"
        report.metricsToInspect shouldContain "leader-micrometer meters"
        report.summary shouldBeEqualTo "pod-a loses the Lease; pod-b observes expiry and executes after the next guarded tick."
    }
}
```

- [ ] **Step 2: Run RED check**

Run:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderFailoverLabTest' --no-build-cache --rerun-tasks
```

Expected: FAIL because scenario/report/lab types do not exist.

## Task 4: Implement Deterministic Failover Lab

**Complexity:** high

**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Create: `domain/LeaderScenario.kt`
- Create: `domain/LeaderScenarioReport.kt`
- Create: `service/LeaderFailoverLab.kt`

- [ ] **Step 1: Implement scenario value object**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

enum class LeaderScenarioKind {
    STEADY_LEADER,
    CONTENTION_SKIP,
    ACTION_FAILURE,
    BACKEND_LOSS_HANDOFF,
}

data class LeaderScenario(
    val backendId: String,
    val kind: LeaderScenarioKind,
    val jobName: String = "report-sync",
) : Serializable {
    init {
        backendId.requireNotBlank("backendId")
        jobName.requireNotBlank("jobName")
    }

    companion object {
        fun steadyLeader(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.STEADY_LEADER)

        fun contentionSkip(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.CONTENTION_SKIP)

        fun actionFailure(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.ACTION_FAILURE)

        fun backendLossHandoff(backendId: String): LeaderScenario =
            LeaderScenario(backendId, LeaderScenarioKind.BACKEND_LOSS_HANDOFF)

        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **Step 2: Implement report model**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

data class LeaderScenarioEvent(
    val actor: String,
    val outcome: String,
    val detail: String,
) : Serializable {
    init {
        actor.requireNotBlank("actor")
        outcome.requireNotBlank("outcome")
        detail.requireNotBlank("detail")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class LeaderScenarioReport(
    val backendId: String,
    val scenario: LeaderScenarioKind,
    val handoffTrigger: String,
    val events: List<LeaderScenarioEvent>,
    val metricsToInspect: List<String>,
    val summary: String,
) : Serializable {
    init {
        backendId.requireNotBlank("backendId")
        handoffTrigger.requireNotBlank("handoffTrigger")
        events.requireNotEmpty("events")
        metricsToInspect.requireNotEmpty("metricsToInspect")
        summary.requireNotBlank("summary")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **Step 3: Implement lab service**

```kotlin
package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenario
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioEvent
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioKind
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioReport
import org.springframework.stereotype.Service

@Service
class LeaderFailoverLab(
    private val catalog: LeaderBackendCatalog,
) {

    fun run(scenario: LeaderScenario): LeaderScenarioReport {
        val profile = catalog.findById(scenario.backendId)
        val metrics = profile.metricsAndEvents
        return when (scenario.kind) {
            LeaderScenarioKind.STEADY_LEADER ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "executed", "${scenario.jobName} ran inside runIfLeader."),
                        LeaderScenarioEvent("node-b", "skipped", skipDetail(profile.id)),
                    ),
                    metricsToInspect = metrics,
                    summary = "node-a executed ${scenario.jobName}; node-b skipped because ${skipSummary(profile.id)}.",
                )

            LeaderScenarioKind.CONTENTION_SKIP ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "executed", "First contender owns the guarded tick."),
                        LeaderScenarioEvent("node-b", "skipped", skipDetail(profile.id)),
                        LeaderScenarioEvent("node-c", "skipped", skipDetail(profile.id)),
                    ),
                    metricsToInspect = metrics,
                    summary = "one contender executes; remaining contenders receive the skip signal.",
                )

            LeaderScenarioKind.ACTION_FAILURE ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "failed", "${scenario.jobName} failed inside the elected block."),
                        LeaderScenarioEvent("node-a", "released", releaseDetail(profile.id)),
                        LeaderScenarioEvent("node-b", "executed-after-recovery", "Next eligible guarded tick can run."),
                    ),
                    metricsToInspect = metrics,
                    summary = "node-a failure is visible; the next eligible run can recover through ${profile.failoverTrigger}.",
                )

            LeaderScenarioKind.BACKEND_LOSS_HANDOFF ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent(primaryActor(profile.id), "lost-leadership", profile.failoverTrigger),
                        LeaderScenarioEvent(secondaryActor(profile.id), "executed-after-handoff", "Next guarded tick observes available leadership."),
                    ),
                    metricsToInspect = metrics,
                    summary = handoffSummary(profile.id),
                )
        }
    }

    private fun skipDetail(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Redis lock is held until release or TTL expiry."
            "zookeeper-curator" -> "ZooKeeper mutex path is owned by another session."
            "kubernetes-lease" -> "Lease holder identity has not expired yet."
            else -> "Leadership is already owned."
        }

    private fun skipSummary(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Redis lock is held"
            "zookeeper-curator" -> "ZooKeeper session owns the znode"
            "kubernetes-lease" -> "the Lease holder is still current"
            else -> "the backend reports an active leader"
        }

    private fun releaseDetail(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Lettuce elector releases the lock in the action boundary."
            "zookeeper-curator" -> "Curator session or mutex ownership is cleared before the next run."
            "kubernetes-lease" -> "Lease ownership remains observable until expiry or update."
            else -> "Backend releases or expires ownership."
        }

    private fun primaryActor(backendId: String): String =
        if (backendId == "kubernetes-lease") "pod-a" else "node-a"

    private fun secondaryActor(backendId: String): String =
        if (backendId == "kubernetes-lease") "pod-b" else "node-b"

    private fun handoffSummary(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "node-a disappears; node-b executes after the Redis lease expires."
            "zookeeper-curator" -> "node-a loses its ZooKeeper session; node-b executes after the ephemeral znode is gone."
            "kubernetes-lease" -> "pod-a loses the Lease; pod-b observes expiry and executes after the next guarded tick."
            else -> "a new candidate executes after the backend exposes leadership loss."
        }
}
```

Do not start real backend clients in this service. It is a deterministic
comparison model backed by source-verified semantics from the real modules.

- [ ] **Step 4: Run GREEN check**

Run:

```bash
./gradlew :leader-backend-comparison-lab:test --tests '*LeaderFailoverLabTest' --no-build-cache --rerun-tasks
```

Expected: PASS.

## Task 5: README Locale Set And Diagrams

**Complexity:** high

**Applies:** `$bluetape4k-blog`, `$bluetape4k-diagram`

**Files:**
- Create: module `README.md`
- Create: module `README.ko.md`
- Create: two SVG+PNG diagram pairs under `docs/images/readme-diagrams/`
- Modify: root `README.md`
- Modify: root `README.ko.md`

- [ ] **Step 1: Create architecture diagram**

Use the current best-practices architecture family as reference and render:

```bash
~/.local/bin/cairosvg docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.svg \
  -o docs/images/readme-diagrams/leader-backend-comparison-lab-readme-architecture-01.png -s 2
```

Required checks:

- full-size PNG visual inspection;
- XML parse;
- card text alignment;
- connector legend if solid/dashed styles differ;
- rounded connector and terminal-segment checks;
- no card intrusion, no unnecessary crossings, no broken icon links.

- [ ] **Step 2: Create sequence diagram**

Use best-practices sequence references and render:

```bash
~/.local/bin/cairosvg docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.svg \
  -o docs/images/readme-diagrams/leader-backend-comparison-lab-readme-sequence-01.png -s 2
```

Required checks:

- numbered visible call labels above their own call lines;
- labels do not cover lines;
- arrowhead colors match call lines in PNG;
- transparent `alt`/`else` bodies;
- muted best-practices palette;
- participant headers, lifelines, activation bars, row height, and branch colors match reference style.

- [ ] **Step 3: Write README files**

`README.md` must include:

- language switch;
- purpose and non-replacement note;
- architecture and sequence images;
- backend selection matrix;
- failover/handoff scenario table;
- metric/event comparison table;
- run/test commands;
- links to `leader-election`, `leader-zookeeper`, and `k8s-lease-micrometer`.

`README.ko.md` must be source-equivalent and natural Korean.

- [ ] **Step 4: Update root module catalog**

Add `leader-backend-comparison-lab` to root README locale tables near the other
leader examples.

## Task 6: CI, Smoke, And Validation Registration

**Complexity:** medium

**Applies:** `$bluetape4k-code-patterns`

**Files:**
- Modify: `scripts/smoke-validate.sh`
- Modify: `.github/workflows/Examples.yml`
- Modify: `.github/workflows/nightly.yml` if needed

- [ ] **Step 1: Register module in smoke validation**

Add `:leader-backend-comparison-lab:test` to `all-smoke`, and update expected
project count in `stale-check` after `./gradlew projects --console=plain`
confirms the count.

- [ ] **Step 2: Register Examples workflow paths and smoke job**

Add:

```yaml
- 'leader/backend-comparison-lab/**'
```

to push and pull request path filters, add `:leader-backend-comparison-lab:test`
to the smoke examples Gradle command, and add its test result artifact paths.

- [ ] **Step 3: Run workflow validation**

Run:

```bash
rg -n "\\\\'" .github/workflows
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml
```

Expected: no escaped workflow expression quotes and actionlint PASS.

## Task 7: Full Verification And Review

**Complexity:** high

**Applies:** `$verification-before-completion`, `$bluetape4k-code-patterns`, `$bluetape4k-diagram`

**Files:**
- Create: `docs/review/2026-07-02-issue-328-leader-backend-comparison-code-review.md`
- Create: `docs/lessons/2026-07-02-issue-328-leader-backend-comparison.md`

- [ ] **Step 1: Run targeted module verification**

```bash
./gradlew :leader-backend-comparison-lab:compileKotlin :leader-backend-comparison-lab:compileTestKotlin --warning-mode all
./gradlew :leader-backend-comparison-lab:test --no-build-cache --rerun-tasks
```

- [ ] **Step 2: Run repo validation**

```bash
./gradlew projects --console=plain
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
git diff --check
```

- [ ] **Step 3: Run Step 6-R review**

Review diff from `origin/develop...HEAD` across performance, stability,
security, operator, developer/API, user/caller, and current-session integration.
Record P0/P1/P2/P3 counts in
`docs/review/2026-07-02-issue-328-leader-backend-comparison-code-review.md`.
P0/P1 must be zero before PR.

- [ ] **Step 4: Write and commit lessons**

Create `docs/lessons/2026-07-02-issue-328-leader-backend-comparison.md` with
source-backed decisions, diagram/validation evidence, and future guidance.

## Task 8: Commit, PR, CI, And DoD

**Complexity:** medium

**Applies:** `$verification-before-completion`, `$bluetape4k-workflow`

**Files:**
- PR body temp file following `bluetape4k-workflow/templates/pr-body-step-dod.md`

- [ ] **Step 1: Commit with Lore trailers**

Use English commit messages and include `Tested:` / `Not-tested:` trailers.

- [ ] **Step 2: Push branch and create PR**

PR requirements:

- title in English;
- `Closes #328`;
- assignee `debop`;
- milestone `1.3.1`;
- issue labels mirrored where GitHub supports them;
- final PR body section is `## DoD Status`.

- [ ] **Step 3: Verify live PR metadata/body**

```bash
gh pr view <number> --repo bluetape4k/bluetape4k-workshop --json body,assignees,labels,milestone
```

Expected: final Markdown `##` heading is `## DoD Status`.

- [ ] **Step 4: Run post-PR review and CI gate**

Run Step 7-R against the actual PR diff, then verify CI with:

```bash
gh pr view <number> --repo bluetape4k/bluetape4k-workshop --json statusCheckRollup
```

Expected: all required checks `SUCCESS` or `SKIPPED`.
